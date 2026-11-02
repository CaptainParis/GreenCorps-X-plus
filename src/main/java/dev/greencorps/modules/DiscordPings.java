package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.util.LoadoutReader;
import dev.greencorps.util.WorldAllowlist;
import dev.greencorps.discord.DiscordWebhook;
import dev.greencorps.discord.PingStore;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Private Discord pings to your own webhook: rockets or elytras running low,
 * heavy damage, totem pops and deaths (with the server's death message).
 * Posts straight from the game; nothing goes through the map. Messages never
 * carry coordinates (anything that looks like them is refused).
 */
public class DiscordPings extends Module {
	/** Entity status for a totem of undying going off. */
	private static final byte TOTEM_STATUS = 35;
	private static final int LOADOUT_EVERY_TICKS = 10;
	private static final int DAMAGE_WINDOW_TICKS = 20;
	private static final long TOTEM_QUIETS_DAMAGE_MS = 2_000;
	private static final long DEATH_CHAT_WAIT_MS = 3_000;
	private static final long CHAT_KEEP_MS = 3_000;

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgRockets = settings.createGroup("Rockets");
	private final SettingGroup sgElytra = settings.createGroup("Elytra");
	private final SettingGroup sgDamage = settings.createGroup("Damage");

	private final Setting<Boolean> only2b2t = sgGeneral.add(new BoolSetting.Builder()
		.name("2b2t-only")
		.description("Only ping while on 2b2t or a listed 2b2t proxy. Singleplayer and other servers stay quiet.")
		.defaultValue(true)
		.build()
	);

	private final Setting<List<String>> twobProxyAddresses = sgGeneral.add(new StringListSetting.Builder()
		.name("2b2t-proxy-addresses")
		.description("Join addresses that mean 2b2t (Zenith proxy IP/host). Proxies listed in God's Eye Companion count too.")
		.defaultValue(List.of())
		.visible(only2b2t::get)
		.build()
	);

	private final Setting<Boolean> mentionUrgent = sgGeneral.add(new BoolSetting.Builder()
		.name("mention-urgent")
		.description("@mention you on totem pops, deaths and heavy damage (needs your Discord user ID). Low rockets / elytra post quietly.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> repeatMinutes = sgGeneral.add(new IntSetting.Builder()
		.name("repeat-minutes")
		.description("A low-rockets / low-elytra ping repeats at most this often, and only after the value recovered once.")
		.defaultValue(5)
		.min(1)
		.sliderRange(1, 60)
		.build()
	);

	private final Setting<Boolean> totemPings = sgGeneral.add(new BoolSetting.Builder()
		.name("totem-pops")
		.description("💛 Ping when you pop a totem, with how many are left.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> deathPings = sgGeneral.add(new BoolSetting.Builder()
		.name("deaths")
		.description("💀 Ping when you die, with the server's death message from chat.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> rocketPings = sgRockets.add(new BoolSetting.Builder()
		.name("rockets-low")
		.description("🚀 Ping when rockets run low.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> rocketsMin = sgRockets.add(new IntSetting.Builder()
		.name("inventory-below")
		.description("Rockets loose in your inventory and offhand.")
		.defaultValue(64)
		.min(0)
		.sliderRange(0, 576)
		.build()
	);

	private final Setting<Integer> rocketsTotalMin = sgRockets.add(new IntSetting.Builder()
		.name("total-below")
		.description("Rockets counting carried shulkers and your ender chest.")
		.defaultValue(128)
		.min(0)
		.sliderRange(0, 2304)
		.build()
	);

	private final Setting<Boolean> elytraPings = sgElytra.add(new BoolSetting.Builder()
		.name("elytra-low")
		.description("🪽 Ping when the usable elytra pool runs low (uses ElytraSwap's durability threshold).")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> elytraPoolMin = sgElytra.add(new IntSetting.Builder()
		.name("pool-below-percent")
		.description("Usable elytra pool across worn and carried elytras.")
		.defaultValue(20)
		.min(0)
		.max(100)
		.sliderRange(0, 100)
		.build()
	);

	private final Setting<Boolean> damagePings = sgDamage.add(new BoolSetting.Builder()
		.name("heavy-damage")
		.description("💥 Ping on a big hit or low health.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> damageDrop = sgDamage.add(new IntSetting.Builder()
		.name("lost-within-1s")
		.description("Health + absorption lost within one second (2 = one heart).")
		.defaultValue(8)
		.min(1)
		.sliderRange(1, 40)
		.build()
	);

	private final Setting<Integer> lowHealth = sgDamage.add(new IntSetting.Builder()
		.name("health-at-or-below")
		.description("Ping once health drops to this (2 = one heart). Re-arms after healing 4 above it.")
		.defaultValue(6)
		.min(1)
		.sliderRange(1, 20)
		.build()
	);

	private final Setting<Integer> damageCooldown = sgDamage.add(new IntSetting.Builder()
		.name("cooldown-seconds")
		.description("At most one heavy-damage ping this often.")
		.defaultValue(60)
		.min(5)
		.sliderRange(5, 600)
		.build()
	);

	private final PingStore store = new PingStore();
	private final DiscordWebhook webhook = new DiscordWebhook("greencorps-discord-pings",
		msg -> mc.execute(() -> ChatUtils.warning("Discord Pings: " + msg)));

	private WLabel webhookLabel;
	private WLabel mentionLabel;

	private int loadoutTicks;
	private boolean rocketsArmed = true;
	private boolean elytraArmed = true;
	private long rocketsSentMs;
	private long elytraSentMs;

	private final float[] hpRing = new float[DAMAGE_WINDOW_TICKS];
	private int hpIndex;
	private int hpFilled;
	private boolean lowHealthArmed = true;
	private long damageSentMs;
	private long totemAtMs;
	private int totemReportTicks = -1;

	private boolean deathHandled;
	private long deathDeadlineMs;
	private String deathFallback;
	private final Deque<long[]> chatTimes = new ArrayDeque<>();
	private final Deque<String> chatLines = new ArrayDeque<>();

	public DiscordPings() {
		super(GreenCorps.CATEGORY, "discord-pings",
			"Private Discord pings to your own webhook: low rockets, low elytra, heavy damage, totem pops and deaths. "
				+ "Copy the webhook URL, then press Paste webhook. Never sends coordinates.");
		WorldAllowlist.registerProxySource(twobProxyAddresses::get);
	}

	@Override
	public void onActivate() {
		resetState();
		if (store.webhook().isEmpty()) {
			ChatUtils.warning("Discord Pings: copy your webhook URL, then press Paste webhook in the settings.");
		}
	}

	private void resetState() {
		loadoutTicks = 0;
		rocketsArmed = true;
		elytraArmed = true;
		hpFilled = 0;
		hpIndex = 0;
		lowHealthArmed = true;
		totemReportTicks = -1;
		deathHandled = false;
		deathDeadlineMs = 0;
		chatTimes.clear();
		chatLines.clear();
	}

	// ------------------------------------------------------------------ GUI --

	@Override
	public WWidget getWidget(GuiTheme theme) {
		WVerticalList list = theme.verticalList();
		webhookLabel = list.add(theme.label(webhookText())).expandX().widget();
		WTable hooks = list.add(theme.table()).expandX().widget();
		WButton paste = hooks.add(theme.button("Paste webhook")).expandX().widget();
		paste.action = this::pasteWebhook;
		WButton clear = hooks.add(theme.button("Clear webhook")).expandX().widget();
		clear.action = () -> {
			store.setWebhook("");
			ChatUtils.info("Discord Pings: webhook cleared.");
			refreshLabels();
		};
		WButton test = hooks.add(theme.button("Send test ping")).expandX().widget();
		test.action = () -> {
			if (send("✅ **Discord Pings connected** · alerts for " + playerName() + " arrive here", true)) {
				ChatUtils.info("Discord Pings: test ping sent.");
			}
		};
		list.add(theme.label("")).widget();
		mentionLabel = list.add(theme.label(mentionText())).expandX().widget();
		list.add(theme.label("Discord → Settings → Advanced → Developer Mode on, then right click your name → Copy User ID.")).expandX();
		WTable ids = list.add(theme.table()).expandX().widget();
		WButton pasteId = ids.add(theme.button("Paste Discord user ID")).expandX().widget();
		pasteId.action = this::pasteMention;
		WButton clearId = ids.add(theme.button("Clear user ID")).expandX().widget();
		clearId.action = () -> {
			store.setMentionId("");
			ChatUtils.info("Discord Pings: no more mentions.");
			refreshLabels();
		};
		return list;
	}

	private String webhookText() {
		return "Webhook: " + PingStore.mask(store.webhook());
	}

	private String mentionText() {
		String id = store.mentionId();
		return "Mention: " + (id.isEmpty() ? "nobody" : "<@" + PingStore.mask(id) + ">");
	}

	private void refreshLabels() {
		if (webhookLabel != null) webhookLabel.set(webhookText());
		if (mentionLabel != null) mentionLabel.set(mentionText());
	}

	/** Clipboard text, then the clipboard is wiped so the secret does not linger there. */
	private String takeClipboard() {
		String clip = mc.keyboard.getClipboard();
		mc.keyboard.setClipboard("");
		return clip == null ? "" : clip.trim();
	}

	private void pasteWebhook() {
		String clip = takeClipboard();
		if (!DiscordWebhook.isDiscordWebhookUrl(clip)) {
			ChatUtils.warning("Discord Pings: the clipboard does not hold a Discord webhook URL (https://discord.com/api/webhooks/…).");
			return;
		}
		store.setWebhook(clip);
		ChatUtils.info("Discord Pings: webhook saved (" + PingStore.mask(clip) + "). Press Send test ping.");
		refreshLabels();
	}

	private void pasteMention() {
		String clip = takeClipboard();
		if (!clip.matches("\\d{5,32}")) {
			ChatUtils.warning("Discord Pings: copy your Discord user ID (digits only) first.");
			return;
		}
		store.setMentionId(clip);
		ChatUtils.info("Discord Pings: urgent pings will mention you.");
		refreshLabels();
	}

	// --------------------------------------------------------------- events --

	@EventHandler
	private void onPacket(PacketEvent.Receive event) {
		// Network thread: hand over to the game thread before touching the world.
		if (event.packet instanceof EntityStatusS2CPacket p && p.getStatus() == TOTEM_STATUS) {
			mc.execute(() -> {
				if (mc.world == null || mc.player == null) return;
				Entity e = p.getEntity(mc.world);
				if (e == mc.player) onTotemPop();
			});
		} else if (event.packet instanceof DeathMessageS2CPacket p) {
			mc.execute(() -> {
				if (mc.player != null && p.playerId() == mc.player.getId()) {
					armDeath(p.message() == null ? null : p.message().getString());
				}
			});
		}
	}

	@EventHandler
	private void onChat(ReceiveMessageEvent event) {
		if (event.getMessage() == null) return;
		String line = Formatting.strip(event.getMessage().getString());
		if (line == null || line.isBlank()) return;
		String name = playerName();
		if (name.isEmpty() || !line.contains(name)) return;
		long now = System.currentTimeMillis();
		chatTimes.addLast(new long[] {now});
		chatLines.addLast(line.trim());
		while (chatTimes.size() > 8) {
			chatTimes.pollFirst();
			chatLines.pollFirst();
		}
		// A death already armed: the server's line about us is the death message.
		if (deathDeadlineMs > 0) sendDeath(line.trim());
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		if (mc.player == null || mc.world == null) return;
		long now = System.currentTimeMillis();

		boolean dead = mc.player.getHealth() <= 0 || mc.player.isDead() || mc.currentScreen instanceof DeathScreen;
		if (dead) {
			if (!deathHandled && deathDeadlineMs == 0) armDeath(null);
		} else if (deathHandled && deathDeadlineMs == 0) {
			// Respawned: ready for the next one.
			deathHandled = false;
			hpFilled = 0;
			lowHealthArmed = true;
		}
		if (deathDeadlineMs > 0 && now >= deathDeadlineMs) {
			sendDeath(deathFallback);
		}

		if (totemReportTicks > 0 && --totemReportTicks == 0) reportTotem();

		if (dead || !allowedHere()) return;
		trackDamage(now);
		if (++loadoutTicks >= LOADOUT_EVERY_TICKS) {
			loadoutTicks = 0;
			checkLoadout(now);
		}
	}

	// --------------------------------------------------------------- alerts --

	private void checkLoadout(long now) {
		LoadoutReader.Loadout l = LoadoutReader.read(mc.player);
		long repeatMs = repeatMinutes.get() * 60_000L;

		if (rocketPings.get()) {
			boolean low = l.rockets() < rocketsMin.get() || l.rocketsAccessible() < rocketsTotalMin.get();
			if (!low) {
				rocketsArmed = true;
			} else if (rocketsArmed && now - rocketsSentMs >= repeatMs) {
				rocketsArmed = false;
				rocketsSentMs = now;
				send("🚀 **Rockets low** · " + l.rockets() + " in inventory · "
					+ l.rocketsAccessible() + " incl. shulkers & ender chest", false);
			}
		}

		if (elytraPings.get() && l.elytraCount() > 0) {
			boolean low = l.elytraPoolPct() < elytraPoolMin.get();
			if (!low) {
				elytraArmed = true;
			} else if (elytraArmed && now - elytraSentMs >= repeatMs) {
				elytraArmed = false;
				elytraSentMs = now;
				String worn = l.elytraPct() >= 0 ? " · worn " + l.elytraPct() + "%" : " · none worn";
				send("🪽 **Elytra low** · pool " + l.elytraPoolPct() + "%" + worn
					+ " · " + l.usableSpares() + " usable spare" + (l.usableSpares() == 1 ? "" : "s"), false);
			}
		}
	}

	private void trackDamage(long now) {
		float hp = mc.player.getHealth();
		float total = hp + mc.player.getAbsorptionAmount();
		hpRing[hpIndex] = total;
		hpIndex = (hpIndex + 1) % hpRing.length;
		if (hpFilled < hpRing.length) hpFilled++;
		if (!damagePings.get()) return;

		float peak = total;
		for (int i = 0; i < hpFilled; i++) peak = Math.max(peak, hpRing[i]);
		int lost = (int) Math.floor(peak - total);

		if (hp > lowHealth.get() + 4) lowHealthArmed = true;
		boolean bigHit = lost >= damageDrop.get();
		boolean lowNow = hp <= lowHealth.get() && lowHealthArmed;
		if (!bigHit && !lowNow) return;
		// The totem ping already covers the hit that popped it.
		if (now - totemAtMs < TOTEM_QUIETS_DAMAGE_MS) return;
		if (now - damageSentMs < damageCooldown.get() * 1000L) return;
		damageSentMs = now;
		if (lowNow) lowHealthArmed = false;
		StringBuilder msg = new StringBuilder("💥 **Heavy damage**");
		if (lost > 0) msg.append(" · −").append(lost).append(" HP in 1s");
		msg.append(" · HP ").append((int) Math.ceil(hp)).append('/').append((int) Math.ceil(mc.player.getMaxHealth()));
		send(msg.toString(), true);
		// Do not count the same hit again next tick.
		hpFilled = 0;
	}

	private void onTotemPop() {
		if (!totemPings.get() || !allowedHere()) return;
		totemAtMs = System.currentTimeMillis();
		// The inventory updates a moment after the pop; count what is left then.
		totemReportTicks = 5;
	}

	private void reportTotem() {
		if (mc.player == null) return;
		int left = LoadoutReader.read(mc.player).totems();
		String emoji = left == 0 ? "🛡️" : "💛";
		String tail = left == 0 ? " · **no totems left**" : " · " + left + " left";
		send(emoji + " **Totem popped**" + tail + " · HP " + (int) Math.ceil(mc.player.getHealth()), true);
	}

	private void armDeath(String vanillaMessage) {
		if (deathHandled || !deathPings.get() || !allowedHere()) {
			deathHandled = true;
			return;
		}
		if (vanillaMessage != null && !vanillaMessage.isBlank()) deathFallback = vanillaMessage;
		if (deathDeadlineMs > 0) return;
		long now = System.currentTimeMillis();
		// The server's own line may have arrived just before the death packet.
		String recent = null;
		var times = chatTimes.descendingIterator();
		var lines = chatLines.descendingIterator();
		while (times.hasNext() && lines.hasNext()) {
			long at = times.next()[0];
			String line = lines.next();
			if (now - at <= CHAT_KEEP_MS) {
				recent = line;
				break;
			}
		}
		if (recent != null) {
			deathDeadlineMs = 1;
			sendDeath(recent);
		} else {
			deathDeadlineMs = now + DEATH_CHAT_WAIT_MS;
		}
	}

	private void sendDeath(String message) {
		if (deathHandled && deathDeadlineMs == 0) return;
		deathHandled = true;
		deathDeadlineMs = 0;
		String text = message == null || message.isBlank() ? "You died" : message.trim();
		if (text.length() > 300) text = text.substring(0, 300) + "…";
		if (!send("💀 **Died** · \"" + text.replace("\"", "'") + "\"", true)) {
			// Refused (e.g. the line looked like coordinates): still say it happened.
			send("💀 **Died** · (death message withheld)", true);
		}
		deathFallback = null;
	}

	// -------------------------------------------------------------- helpers --

	private boolean allowedHere() {
		if (!only2b2t.get()) return true;
		return WorldAllowlist.allowsJoin(WorldAllowlist.joinedHost(mc), WorldAllowlist.proxyAddresses());
	}

	private String playerName() {
		if (mc.player != null) return mc.player.getGameProfile().getName();
		return mc.getSession() == null ? "" : mc.getSession().getUsername();
	}

	/** @return true when queued for sending. */
	private boolean send(String content, boolean urgent) {
		String id = store.mentionId();
		List<String> mention = urgent && mentionUrgent.get() && !id.isEmpty() ? List.of(id) : List.of();
		String body = mention.isEmpty() ? content : content + " · <@" + id + ">";
		String why = webhook.postAsync(store.webhook(), "GreenCorps · " + playerName(), body, mention);
		if (why != null) {
			mc.execute(() -> ChatUtils.warning("Discord Pings: not sent, " + why + "."));
			return false;
		}
		return true;
	}
}
