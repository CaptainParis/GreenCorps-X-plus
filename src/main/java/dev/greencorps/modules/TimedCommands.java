package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.gui.TimedCommandEditScreen;
import dev.greencorps.gui.TimedCommandsScreen;
import dev.greencorps.schedule.Automation;
import dev.greencorps.schedule.AutomationStore;
import dev.greencorps.schedule.DayCycle;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.MeteorStarscript;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.starscript.Script;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.world.World;

/**
 * Runs saved chat/command lines when the day clock, a world tick, or
 * sunrise/sunset (plus offset) is reached. Edit anytime; fire only while active.
 * Optional early-send (manual ticks or tab-list ping) aims the packet at the
 * intended in-game tick; not true millisecond lock.
 */
public class TimedCommands extends Module {
	public enum LeadMode {
		OFF("Off"),
		MANUAL("Manual"),
		AUTO_PING("Auto ping");

		private final String label;

		LeadMode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgTiming = settings.createGroup("Timing");

	private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
		.name("chat-feedback")
		.description("Announce when an automation runs.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> allowChat = sgGeneral.add(new BoolSetting.Builder()
		.name("allow-chat")
		.description("Allow plain chat lines (no / and no client command prefix). Off by default.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Integer> catchUpTicks = sgTiming.add(new IntSetting.Builder()
		.name("catch-up-ticks")
		.description("Max forward jump still counted as crossing a trigger (sleep skips larger jumps).")
		.defaultValue(DayCycle.DEFAULT_CATCH_UP)
		.min(1)
		.max(200)
		.sliderRange(1, 80)
		.build()
	);

	private final Setting<LeadMode> leadMode = sgTiming.add(new EnumSetting.Builder<LeadMode>()
		.name("lead-mode")
		.description("Send early so the packet is in flight at the target tick. Off = fire on the tick itself.")
		.defaultValue(LeadMode.AUTO_PING)
		.build()
	);

	private final Setting<Integer> manualLeadTicks = sgTiming.add(new IntSetting.Builder()
		.name("manual-lead-ticks")
		.description("Ticks to fire early when lead-mode is Manual.")
		.defaultValue(0)
		.min(0)
		.max(40)
		.sliderRange(0, 20)
		.visible(() -> leadMode.get() == LeadMode.MANUAL)
		.build()
	);

	private final Setting<Integer> autoLeadMax = sgTiming.add(new IntSetting.Builder()
		.name("auto-lead-max")
		.description("Max ticks for Auto ping lead (latency_ms / 50, clamped).")
		.defaultValue(10)
		.min(0)
		.max(40)
		.sliderRange(0, 20)
		.visible(() -> leadMode.get() == LeadMode.AUTO_PING)
		.build()
	);

	private AutomationStore store;
	private Long prevTimeOfDay;
	private Long prevWorldAge;
	private boolean primed;
	private int cachedPingMs;
	private int cachedLeadTicks;

	public TimedCommands() {
		super(GreenCorps.CATEGORY, "timed-commands",
			"Run saved commands at a day time, world tick, or sunrise/sunset with offset.");
	}

	public static TimedCommands get() {
		return Modules.get().get(TimedCommands.class);
	}

	public AutomationStore store() {
		ensureStore();
		return store;
	}

	private void ensureStore() {
		if (store == null) store = AutomationStore.loadOrCreate();
	}

	@Override
	public void onActivate() {
		ensureStore();
		primed = false;
		prevTimeOfDay = null;
		prevWorldAge = null;
		refreshLeadCache();
	}

	@Override
	public void onDeactivate() {
		primed = false;
		prevTimeOfDay = null;
		prevWorldAge = null;
	}

	@Override
	public WWidget getWidget(GuiTheme theme) {
		ensureStore();
		refreshLeadCache();
		WVerticalList list = theme.verticalList();
		list.add(theme.label(DayCycle.nowLine()));
		list.add(theme.label(leadStatusLine()));

		WButton add = list.add(theme.button("Add automation")).expandX().widget();
		add.action = this::openNewAutomation;

		WButton edit = list.add(theme.button("Edit automations…")).expandX().widget();
		edit.action = this::openEditor;

		int n = store.list().size();
		long on = store.list().stream().filter(a -> a.enabled).count();
		list.add(theme.label(n + " saved (" + on + " enabled)"));
		return list;
	}

	/** Status for widget / Refresh time row. */
	public String leadStatusLine() {
		refreshLeadCache();
		return switch (leadMode.get()) {
			case OFF -> "lead=0 (off)";
			case MANUAL -> "lead=" + cachedLeadTicks + " (manual)";
			case AUTO_PING -> "lead=" + cachedLeadTicks + " (ping ~" + cachedPingMs + "ms)";
		};
	}

	public void openEditor() {
		ensureStore();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		Screen cur = client.currentScreen;
		TimedCommandsScreen next = new TimedCommandsScreen(this, cur);
		client.execute(() -> client.setScreen(next));
	}

	public void openNewAutomation() {
		ensureStore();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		Screen cur = client.currentScreen;
		TimedCommandEditScreen next = new TimedCommandEditScreen(this, store.newBlank(), cur);
		client.execute(() -> client.setScreen(next));
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (!isActive() || mc == null || mc.world == null || mc.player == null) return;
		ensureStore();
		refreshLeadCache();

		World world = mc.world;
		long tod = world.getTimeOfDay();
		long age = world.getTime();
		boolean fixed = world.getDimension().hasFixedTime();
		int catchUp = catchUpTicks.get();
		int lead = cachedLeadTicks;

		if (!primed || prevTimeOfDay == null || prevWorldAge == null) {
			prevTimeOfDay = tod;
			prevWorldAge = age;
			primed = true;
			return;
		}

		long prevTod = prevTimeOfDay;
		long prevAge = prevWorldAge;
		prevTimeOfDay = tod;
		prevWorldAge = age;

		for (Automation a : store.list()) {
			if (!a.enabled) continue;
			if (a.commands == null || a.commands.isEmpty()) continue;
			if (shouldFire(a, fixed, prevTod, tod, prevAge, age, catchUp, lead)) {
				runAutomation(a, tod, age);
			}
		}
	}

	private boolean shouldFire(Automation a, boolean fixedTime,
		long prevTod, long tod, long prevAge, long age, int catchUp, int lead) {
		return switch (a.trigger) {
			case TIME_OF_DAY, DAY_TICK, SUNRISE, SUNSET -> {
				if (fixedTime) yield false;
				int logical = a.resolvedDayTick();
				long day = DayCycle.dayIndex(tod);
				if (a.lastFiredDay == day && a.lastFiredResolvedTick == logical) yield false;
				int fireAt = DayCycle.leadTarget(logical, lead);
				yield DayCycle.crossedDayTick(prevTod, tod, fireAt, catchUp);
			}
			case WORLD_TICK -> {
				long logical = a.timeValue;
				if (a.lastFiredWorldAge >= 0 && a.lastFiredWorldAge >= logical) yield false;
				long fireAt = DayCycle.leadWorldAge(logical, lead);
				yield DayCycle.crossedWorldAge(prevAge, age, fireAt, catchUp);
			}
			case EVERY_N_TICKS -> {
				// Interval stays on world age edges; lead does not apply.
				yield DayCycle.crossedEveryN(prevAge, age, Math.max(1, a.offsetTicks));
			}
		};
	}

	private void runAutomation(Automation a, long tod, long age) {
		if (chatFeedback.get()) info("Timed: " + a.name);
		dispatchLines(a);
		if (a.trigger.isDayCycle()) {
			a.lastFiredDay = DayCycle.dayIndex(tod);
			a.lastFiredResolvedTick = a.resolvedDayTick();
		} else {
			a.lastFiredWorldAge = age;
		}
		store.markFired(a);
	}

	/** Manual run from the editor (ignores schedule; still respects allow-chat). */
	public void runNow(Automation a) {
		if (a == null) return;
		if (mc == null || mc.player == null) {
			info("Join a world to run commands.");
			return;
		}
		if (chatFeedback.get()) info("Timed (manual): " + a.name);
		dispatchLines(a);
	}

	private void refreshLeadCache() {
		cachedPingMs = readPingMs();
		cachedLeadTicks = switch (leadMode.get()) {
			case OFF -> 0;
			case MANUAL -> Math.max(0, manualLeadTicks.get());
			case AUTO_PING -> DayCycle.leadFromPingMs(cachedPingMs, autoLeadMax.get());
		};
	}

	private int readPingMs() {
		if (mc == null || mc.player == null || mc.getNetworkHandler() == null) return 0;
		PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
		if (entry == null) return 0;
		return Math.max(0, entry.getLatency());
	}

	private void dispatchLines(Automation a) {
		if (a.commands == null) return;
		String meteorPrefix = Config.get().prefix.get();
		String baritonePrefix = BaritoneUtils.IS_AVAILABLE ? BaritoneUtils.getPrefix() : null;

		for (String raw : a.commands) {
			if (raw == null) continue;
			String line = raw.trim();
			if (line.isEmpty()) continue;

			Script script = MeteorStarscript.compile(line);
			String message = script != null ? MeteorStarscript.run(script) : line;
			if (message == null || message.isBlank()) continue;
			message = message.trim();

			if (!isAllowedMessage(message, meteorPrefix, baritonePrefix)) {
				if (chatFeedback.get()) {
					warning("Blocked chat line in \"" + a.name + "\" (enable Allow chat)");
				}
				continue;
			}
			ChatUtils.sendPlayerMsg(message);
		}
	}

	private boolean isAllowedMessage(String message, String meteorPrefix, String baritonePrefix) {
		if (message.startsWith("/")) return true;
		if (meteorPrefix != null && !meteorPrefix.isEmpty() && message.startsWith(meteorPrefix)) return true;
		if (baritonePrefix != null && !baritonePrefix.isEmpty() && message.startsWith(baritonePrefix)) return true;
		return allowChat.get();
	}
}
