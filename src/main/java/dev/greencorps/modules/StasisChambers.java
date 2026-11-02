package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.backup.Home;
import dev.greencorps.chat.StasisChat;
import dev.greencorps.gui.PanelReload;
import dev.greencorps.gui.StasisHomePromptScreen;
import dev.greencorps.gui.StasisMissingDialog;
import dev.greencorps.gui.StasisOverviewScreen;
import dev.greencorps.gui.StasisPanel;
import dev.greencorps.gui.StasisRemindDialog;
import dev.greencorps.mixin.ModuleScreenAccessor;
import dev.greencorps.stasis.ChamberKind;
import dev.greencorps.stasis.ChamberScanner;
import dev.greencorps.stasis.PearlSettleTracker;
import dev.greencorps.stasis.StasisChamberSlot;
import dev.greencorps.stasis.StasisGeometry;
import dev.greencorps.stasis.StasisHome;
import dev.greencorps.stasis.SignOwnerProbe;
import dev.greencorps.stasis.StasisPearl;
import dev.greencorps.stasis.StasisPerf;
import dev.greencorps.stasis.StasisStore;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import org.joml.Vector3d;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Detects installed ender pearls in stasis chambers, pins them in Xaero,
 * tracks homes / quotas, and alerts when pearls are missing.
 */
public class StasisChambers extends Module {
	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgAlerts = settings.createGroup("Alerts");
	private final SettingGroup sgRender = settings.createGroup("Render");

	private final SettingGroup sgProfiles = settings.createGroup("Profiles");
	private final SettingGroup sgDebug = settings.createGroup("Debug");

	private final Setting<Boolean> perfLog = sgDebug.add(new BoolSetting.Builder()
		.name("perf-log")
		.description("Log Stasis Monitor timing to latest.log every 5s (plus spikes over 20ms). Read live with .stasis perf.")
		.defaultValue(false)
		.onChanged(StasisPerf::setEnabled)
		.build()
	);

	private final Setting<Integer> defaultPearls = sgProfiles.add(new IntSetting.Builder()
		.name("default-pearls")
		.description("Seed required pearls for a new Main profile / new homes when profiles have no defaults. Edit accounts in the monitor Profiles section.")
		.defaultValue(1)
		.min(1)
		.sliderRange(1, 8)
		.build()
	);

	private final Setting<Integer> homeRadius = sgGeneral.add(new IntSetting.Builder()
		.name("home-radius")
		.description("Default geofence radius (chunks) when creating a stasis home from the panel or map.")
		.defaultValue(2)
		.min(1)
		.sliderRange(1, 4)
		.build()
	);

	private final Setting<Integer> settleTicks = sgGeneral.add(new IntSetting.Builder()
		.name("settle-ticks")
		.description("How long a pearl must stay in water or beside honey before it counts as installed.")
		.defaultValue(40)
		.min(5)
		.sliderRange(10, 120)
		.build()
	);

	private final Setting<Double> settleBox = sgGeneral.add(new DoubleSetting.Builder()
		.name("settle-box")
		.description("How far (blocks) a pearl can drift while settling before the timer resets.")
		.defaultValue(1.5)
		.min(0.5)
		.sliderRange(0.5, 3.0)
		.build()
	);

	private final Setting<Boolean> trackOthers = sgGeneral.add(new BoolSetting.Builder()
		.name("track-others")
		.description("List other players' pearls under each home for awareness. They never count toward your required pearls.")
		.defaultValue(true)
		.build()
	);

	public enum OwnerMarker {
		Signs("Signs"),
		Banner("Banner"),
		Skull("Skull");

		private final String label;

		OwnerMarker(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private final Setting<Boolean> signOwnerFallback = sgGeneral.add(new BoolSetting.Builder()
		.name("sign-owner-fallback")
		.description("When entity owner is missing, read exact IGN from the owner marker around the seat. No fuzzy match.")
		.defaultValue(true)
		.build()
	);

	private final Setting<OwnerMarker> ownerMarker = sgGeneral.add(new EnumSetting.Builder<OwnerMarker>()
		.name("owner-marker")
		.description("Stealth chambers: Signs (default) = standing/wall/hanging signs. Banner or Skull = anvil-rename that block to the exact IGN and place it above/beside the seat. Planks and plain blocks cannot keep anvil names. Sign-search clients miss banners/skulls; a full block-entity dump still could. Live throw owner always wins.")
		.defaultValue(OwnerMarker.Signs)
		.visible(signOwnerFallback::get)
		.build()
	);

	private final Setting<Boolean> chatOwnUpdates = sgAlerts.add(new BoolSetting.Builder()
		.name("chat-own-updates")
		.description("Client chat when one of your tracked pearls appears or vanishes while that chamber is in view. Does not send server chat.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> chatOthersUpdates = sgAlerts.add(new BoolSetting.Builder()
		.name("chat-others-updates")
		.description("Client chat when other/unknown pearls appear or vanish in view. Needs Track Others. Quiet by default.")
		.defaultValue(false)
		.visible(trackOthers::get)
		.build()
	);

	private final Setting<Boolean> chatMissing = sgAlerts.add(new BoolSetting.Builder()
		.name("chat-missing")
		.description("Periodic client chat when a loaded stasis home is short of your pearls. Panel still shows the count if this is off.")
		.defaultValue(true)
		.build()
	);

	public enum WarningMode {
		Dialog("Dialog"),
		Passive("Passive");

		private final String label;

		WarningMode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** Module panel table mode. */
	public enum PanelViewMode {
		CHAMBERS,
		PEARLS
	}

	private final Setting<WarningMode> missingWarning = sgAlerts.add(new EnumSetting.Builder<WarningMode>()
		.name("missing-warning")
		.description("Dialog = Understood screen when idle on ground (default). Passive = client chat + short toast (never blocks flight/combat).")
		.defaultValue(WarningMode.Dialog)
		.build()
	);

	public enum ReminderInterval {
		S30(30, "30s"),
		M1(60, "1min"),
		M2(120, "2min"),
		M3(180, "3min"),
		M5(300, "5min"),
		M10(600, "10min"),
		M15(900, "15min"),
		M20(1200, "20min"),
		M30(1800, "30min");

		public final int seconds;
		private final String label;

		ReminderInterval(int seconds, String label) {
			this.seconds = seconds;
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public enum SummaryScope {
		Current("Current home"),
		All("All homes");

		private final String label;

		SummaryScope(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private final Setting<ReminderInterval> reminderInterval = sgAlerts.add(new EnumSetting.Builder<ReminderInterval>()
		.name("reminder-interval")
		.description("Time between missing-quota reminders for the same home.")
		.defaultValue(ReminderInterval.M2)
		.build()
	);

	private final Setting<SummaryScope> summaryScope = sgAlerts.add(new EnumSetting.Builder<SummaryScope>()
		.name("summary-scope")
		.description("Current home = only the geofence you stand in. All homes = one named reminder per short home (never one summed viable count).")
		.defaultValue(SummaryScope.Current)
		.build()
	);

	private final Setting<Boolean> leaveAlert = sgAlerts.add(new BoolSetting.Builder()
		.name("leave-alert")
		.description("Alert once when you leave a short-quota home beyond the leave distance (not only on the timer).")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> leaveAlertBlocks = sgAlerts.add(new IntSetting.Builder()
		.name("leave-alert-blocks")
		.description("Blocks past the home geofence edge before the leave alert fires. Min 16 (1 chunk); default 32 (2 chunks).")
		.defaultValue(32)
		.min(16)
		.sliderRange(16, 128)
		.visible(leaveAlert::get)
		.build()
	);

	private final Setting<Boolean> pullRemind = sgAlerts.add(new BoolSetting.Builder()
		.name("pull-remind")
		.description("After a long teleport that was not your own pearl throw (and not phasing/rubberband), remind you to place a pearl again. Self-throws are ignored.")
		.defaultValue(true)
		.build()
	);

	/**
	 * Hard pull threshold: max horizontal pearl throw in 1.21.2+ is 148.5
	 * (1.5 * 0.99 first step, geometric series). Anything farther must be a stasis pull.
	 */
	public static final double PULL_MIN_BLOCKS = 148.5;

	private final Setting<ReminderInterval> pullRemindInterval = sgAlerts.add(new EnumSetting.Builder<ReminderInterval>()
		.name("pull-remind-interval")
		.description("Time between pull reminders so one pull does not reopen forever.")
		.defaultValue(ReminderInterval.M5)
		.visible(pullRemind::get)
		.build()
	);

	private final Setting<Boolean> hubWaypoint = sgGeneral.add(new BoolSetting.Builder()
		.name("stasis-hub-waypoint")
		.description("One Xaero hub pin per stasis home at the seat centroid, one block above the pearls. Removes legacy per-pearl STASIS CHAMBER pins inside homes.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> alertSound = sgAlerts.add(new BoolSetting.Builder()
		.name("alert-sound")
		.description("Soft client sound for your install, missing, and pull alerts. Others stay silent unless you enable their chat.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> pearlLabels = sgRender.add(new BoolSetting.Builder()
		.name("pearl-labels")
		.description("Show Pearl: <IGN> nametags on thrown ender pearls (assumed owners append *). IGN uses ESP occupancy colours (yours mint / others blue).")
		.defaultValue(true)
		.build()
	);

	private final Setting<Double> pearlLabelScale = sgRender.add(new DoubleSetting.Builder()
		.name("pearl-label-scale")
		.description("Nametag size for thrown-pearl labels. Default is near the minimum.")
		.defaultValue(1.0)
		.min(0.75)
		.max(2.0)
		.sliderRange(0.75, 2.0)
		.visible(pearlLabels::get)
		.build()
	);

	private final Setting<Boolean> esp = sgRender.add(new BoolSetting.Builder()
		.name("esp")
		.description("Highlight chamber geometry in view: honey beside the pearl, and water/bubble column plus soul sand under it. Mint = your loaded pearl, grey = empty.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> hideEmptyEsp = sgRender.add(new BoolSetting.Builder()
		.name("hide-empty-esp")
		.description("Skip ESP for empty seats (spawn-kit chambers you only load while PvPing). Monitor list still shows them. Leave off when stocking a base so free seats stay visible.")
		.defaultValue(false)
		.visible(esp::get)
		.build()
	);

	private final Setting<Boolean> espSeatOnly = sgRender.add(new BoolSetting.Builder()
		.name("esp-seat-only")
		.description("Only highlight the pearl seat block instead of the full water/honey column.")
		.defaultValue(false)
		.visible(esp::get)
		.build()
	);

	private final Setting<SettingColor> loadedColor = sgRender.add(new ColorSetting.Builder()
		.name("loaded-color")
		.description("ESP color when one of your pearls is present.")
		.defaultValue(new SettingColor(152, 255, 204, 255))
		.visible(esp::get)
		.build()
	);

	private final Setting<SettingColor> emptyColor = sgRender.add(new ColorSetting.Builder()
		.name("empty-color")
		.description("ESP color when your chamber chunk is loaded but the pearl is gone.")
		.defaultValue(new SettingColor(140, 140, 140, 255))
		.visible(esp::get)
		.build()
	);

	private final Setting<Boolean> espOthers = sgRender.add(new BoolSetting.Builder()
		.name("esp-others")
		.description("Also draw dim columns for other players' / unknown pearls. Needs Track Others for new ones.")
		.defaultValue(false)
		.visible(esp::get)
		.build()
	);

	private final Setting<SettingColor> otherColor = sgRender.add(new ColorSetting.Builder()
		.name("other-color")
		.description("ESP color for other players' pearls when ESP Others is on.")
		.defaultValue(new SettingColor(100, 140, 180, 180))
		.visible(() -> esp.get() && espOthers.get())
		.build()
	);

	private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
		.name("shape-mode")
		.description("ESP column fill vs lines.")
		.defaultValue(ShapeMode.Both)
		.visible(esp::get)
		.build()
	);

	private final Setting<Integer> fillOpacity = sgRender.add(new IntSetting.Builder()
		.name("fill-opacity")
		.description("ESP fill alpha (0-255).")
		.defaultValue(50)
		.min(0)
		.max(255)
		.sliderRange(0, 255)
		.visible(esp::get)
		.build()
	);

	private StasisStore store;
	private PearlSettleTracker settleTracker;
	private final Color lineColor = new Color();
	private final Color sideColor = new Color();
	private final Vector3d pearlTagPos = new Vector3d();
	private static final Color PEARL_LABEL_BG = new Color(0, 0, 0, 75);
	private static final Color PEARL_LABEL_WHITE = new Color(255, 255, 255, 255);

	/** Window after local pearl use; only NEW entity UUIDs may be claimed. */
	private long localThrowUntilMs;
	/** Pearl entity UUIDs already in-world when the local throw started. */
	private Set<UUID> pearlsAtLocalThrow = Set.of();
	/** At most one heuristic claim per throw (entity UUID). */
	private UUID localThrowClaimedEntity;
	private UUID localPlayerUuid;
	private String localPlayerIgn;

	/** homeId -> last missing-reminder epoch ms */
	private final Map<String, Long> lastReminderMs = new HashMap<>();
	/** Homes where we were inside while short — leave alert armed until beyond threshold. */
	private final Set<String> leaveAlertArmed = new HashSet<>();
	/** Pearl entity UUID -> remembered pearl id (live this session) */
	private final Map<UUID, String> liveEntityToPearl = new HashMap<>();
	/** Pearl ids prompted for home create this session */
	private final Set<String> promptedPearlIds = new HashSet<>();
	/** pearlId -> last notified loaded state (dedupe instant chat) */
	private final Map<String, Boolean> lastNotifiedLoaded = new HashMap<>();
	/** Recent small position-look hops for phase/rubberband detection */
	private final ArrayDeque<Long> recentSmallHopMs = new ArrayDeque<>();
	private long lastPullRemindMs;
	private boolean dialogOpen;
	private int tickCounter;
	private Integer lastSettleTicks;
	private Double lastSettleBox;
	private boolean hadPlayerPos;
	/** pearlId -> ms when last seen live (grace against flicker empty). */
	private final Map<String, Long> pearlLastLiveMs = new HashMap<>();
	/** slotId -> ms when a matching pearl entity was last seen. */
	private final Map<String, Long> slotLastLiveMs = new HashMap<>();
	/** Short grace so Empty/grey ESP feels realtime after pull. */
	private static final long EMPTY_GRACE_MS = 200L;
	/** Passive warning overlay duration. */
	private static final long PASSIVE_TOAST_MS = 5_000L;
	/** Vanilla ender-pearl entity tracking is 4 chunks; never observe beyond that. */
	private static final int PEARL_TRACK_CHUNKS = 4;
	private static final int OBSERVE_INSET_BLOCKS = 8;
	private Boolean lastHubWaypoint;
	private final ArrayDeque<PassiveToast> passiveToasts = new ArrayDeque<>();
	/** Rare root sweep (~30s); realtime state comes from pearls. */
	private static final int SLOW_VALIDATE_TICKS = 600;
	private static final int MAX_VALIDATE_PER_PASS = 16;
	private static final int DISCOVER_PER_TICK = 4;
	private static final int DISCOVER_QUEUE_CAP = 32;
	/** Consecutive failed validates before marking Broken. */
	private static final int GEOMETRY_FAIL_STREAK = 2;
	/** Session start for Assumed-owner labeling (owners confirmed before this are assumed). */
	private long sessionStartMs;
	/** Chamber Slots vs Pearls Summary in the module panel (not persisted). */
	private PanelViewMode panelViewMode = PanelViewMode.CHAMBERS;
	/** One soft warn if SignOwnerProbe class load / probe fails (corrupt jar mid-session). */
	private boolean signOwnerProbeFailWarned;

	/** Debounced block positions for seat-local chamber discover. */
	private final ArrayDeque<BlockPos> discoverQueue = new ArrayDeque<>();
	/** slotId -> consecutive geometry fail count. */
	private final Map<String, Integer> geometryFailStreak = new HashMap<>();

	/** Load-time home repair to announce once chat is available. */
	private StasisStore.HomeRepair pendingRepairNotice;
	/** pearlId -> entity that matched it this tick (one record per live pearl). */
	private final Map<String, UUID> pearlClaimsThisTick = new HashMap<>();
	/** Entity UUID -> resolved owner; tick fills, render only reads (no block probes per frame). */
	private final Map<UUID, CachedOwner> ownerCache = new HashMap<>();
	private static final long OWNER_CACHE_MS = 1_000L;
	/** slotId -> owner of the live pearl seated there, rebuilt each tick for ESP. */
	private Map<String, OwnerInfo> liveSlotOwnersTick = Map.of();
	/** slotId -> ms / packed pearl cell of the last seat heal (heal only on change or every 5s). */
	private final Map<String, Long> slotLastHealMs = new HashMap<>();
	private final Map<String, Long> slotLastHealCell = new HashMap<>();
	private static final long HEAL_INTERVAL_MS = 5_000L;

	/** Budgeted chunk scans: near-known-chamber box jobs first, full-fence passes after. */
	private final ArrayDeque<ScanJob> scanQueue = new ArrayDeque<>();
	private final Set<String> scanQueued = new HashSet<>();
	/** dim|home|cx|cz given a full-fence pass this session. */
	private final Set<String> fullScannedChunks = new HashSet<>();
	private static final long SCAN_BUDGET_NS = 2_000_000L;
	private static final int SCAN_QUEUE_CAP = 1024;
	/** Primed homes rescan this far around known chambers on chunk load. */
	private static final int PRIMED_PAD_XZ = 8;
	private static final int PRIMED_PAD_DOWN = 4;
	private static final int PRIMED_PAD_UP = 8;

	public StasisChambers() {
		// Display title: Stasis Monitor. Alias keeps old search / .stasis-chambers habits.
		super(GreenCorps.CATEGORY, "stasis-monitor",
			"Monitors stasis pearls, homes, and quotas. Left click or the module keybind opens Chamber Slots Overview / Pearl Summary. Right click opens settings (module stays on; turn ESP off under Render). Tip: a sign with the exact owner IGN above the pearl or one block beside the chamber helps match owners when entity owner is missing - no fuzzy match.",
			"stasis-chambers");
	}

	public static StasisChambers get() {
		return Modules.get().get(StasisChambers.class);
	}

	public boolean ensureStore() {
		try {
			Path base = StasisStore.defaultBaseDir();
			if (store == null) {
				store = new StasisStore(base);
				store.setDeferredWrites(true);
				StasisStore.HomeRepair repair = store.lastLoadRepair();
				if (repair.any()) {
					pendingRepairNotice = repair;
					for (String line : repair.lines()) GreenCorps.LOG.info("[Stasis] home repair: {}", line);
				}
			}
			bootstrapProfilesIfNeeded();
			return true;
		} catch (Exception e) {
			StasisChat.warn("Failed to open stasis store: " + e.getClass().getSimpleName());
			return false;
		}
	}

	private void bootstrapProfilesIfNeeded() {
		if (store == null) return;
		try {
			String uuid = localUuidString();
			if (uuid == null && mc != null && mc.player != null) {
				localPlayerUuid = mc.player.getUuid();
				localPlayerIgn = mc.player.getName().getString();
				uuid = localUuidString();
			}
			String ign = localPlayerIgn;
			store.ensureBootstrapProfile(uuid, ign, defaultPearls.get());
		} catch (Exception ignored) {}
	}

	public StasisStore store() {
		return store;
	}

	public int defaultPearls() {
		return defaultPearls.get();
	}

	public int homeRadiusChunks() {
		return homeRadius.get();
	}

	public String localUuidString() {
		return localPlayerUuid != null ? localPlayerUuid.toString() : null;
	}

	public int ownLoadedCount(StasisHome home) {
		if (store == null || home == null) return 0;
		return store.homeCapacity(home, localUuidString(), localPlayerIgn()).ownLoaded();
	}

	public int ownMissingCount(StasisHome home) {
		if (store == null || home == null) return 0;
		return store.homeCapacity(home, localUuidString(), localPlayerIgn()).alertMissing();
	}

	public StasisStore.HomeCapacity capacityFor(StasisHome home) {
		if (store == null || home == null) {
			return new StasisStore.HomeCapacity(List.of(), 0, 0, 0, 0, 0, 0, 0, 0, false);
		}
		return store.homeCapacity(home, localUuidString(), localPlayerIgn());
	}

	/** GUI color for Loaded state (matches ESP loaded-color). */
	public meteordevelopment.meteorclient.utils.render.color.Color loadedGuiColor() {
		return toGuiColor(loadedColor.get());
	}

	/** GUI color for Empty state (matches ESP empty-color). */
	public meteordevelopment.meteorclient.utils.render.color.Color emptyGuiColor() {
		return toGuiColor(emptyColor.get());
	}

	private static meteordevelopment.meteorclient.utils.render.color.Color toGuiColor(SettingColor c) {
		if (c == null) return new meteordevelopment.meteorclient.utils.render.color.Color(200, 200, 200);
		return new meteordevelopment.meteorclient.utils.render.color.Color(c.r, c.g, c.b, c.a);
	}

	/** Covering home, else nearest same-dim home by XZ (for panel expand). */
	public StasisHome nearestHome() {
		if (store == null || mc.player == null || mc.world == null) return null;
		String dim = mc.world.getRegistryKey().getValue().toString();
		Optional<StasisHome> covering = store.findCoveringHome(dim, mc.player.getX(), mc.player.getZ());
		if (covering.isPresent()) return covering.get();
		StasisHome best = null;
		double bestD = Double.MAX_VALUE;
		for (StasisHome h : store.homes()) {
			if (!h.sameDimension(dim)) continue;
			double dx = h.centerX - mc.player.getX();
			double dz = h.centerZ - mc.player.getZ();
			double d = dx * dx + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = h;
			}
		}
		return best;
	}

	/** Setting titles for ModuleScreen label-column alignment. */
	public List<String> settingTitles() {
		List<String> titles = new ArrayList<>();
		for (SettingGroup g : settings.groups) {
			for (Setting<?> s : g) {
				if (s.title != null && !s.title.isBlank()) titles.add(s.title);
			}
		}
		return titles;
	}

	@Override
	public void onActivate() {
		StasisPerf.setEnabled(perfLog.get());
		ensureStore();
		rebuildTracker();
		dialogOpen = false;
		sessionStartMs = System.currentTimeMillis();
		hadPlayerPos = mc.player != null;
		if (mc.player != null) {
			localPlayerUuid = mc.player.getUuid();
			localPlayerIgn = mc.player.getName().getString();
		}
	}

	@EventHandler
	private void onGameLeft(GameLeftEvent event) {
		flushStore();
		clearSessionCaches();
	}

	private void flushStore() {
		if (store == null) return;
		try {
			store.flushNow();
		} catch (IOException e) {
			StasisChat.warn("Stasis save failed: " + e.getClass().getSimpleName());
		}
	}

	private void clearSessionCaches() {
		ownerCache.clear();
		liveSlotOwnersTick = Map.of();
		scanQueue.clear();
		scanQueued.clear();
		fullScannedChunks.clear();
		slotLastHealMs.clear();
		slotLastHealCell.clear();
	}

	/** {@code .stasis audit}: per-home slot / pearl links plus any out-of-fence wiring (dry run). */
	public List<String> auditLines() {
		List<String> out = new ArrayList<>();
		if (store == null) return out;
		for (StasisHome home : store.homes()) {
			int viable = 0, bound = 0, loadedBound = 0;
			for (StasisChamberSlot s : store.slotsForHome(home.id)) {
				if (Boolean.FALSE.equals(s.geometryOk)) continue;
				viable++;
				if (s.pearlId == null) continue;
				bound++;
				StasisPearl p = store.findPearlById(s.pearlId).orElse(null);
				if (p != null && p.loaded) loadedBound++;
			}
			int records = 0, loadedRecords = 0;
			for (StasisPearl p : store.pearls()) {
				if (!home.id.equals(p.homeId)) continue;
				records++;
				if (p.loaded) loadedRecords++;
			}
			out.add(home.name + ": slots " + viable + " (bound " + bound + ", loaded " + loadedBound
				+ "), pearl records " + records + " (loaded " + loadedRecords + "), r=" + home.radiusChunks);
		}
		try {
			StasisStore.HomeRepair dry = store.repairHomeAssignments(false);
			if (!dry.any()) {
				out.add("Home links OK (every slot / pearl sits inside its home's fence).");
			} else {
				out.add("Out-of-fence links: " + dry.slotsMoved() + " slots, " + dry.pearlsMoved()
					+ " pearls (run .stasis audit fix to repair):");
				out.addAll(dry.lines().subList(0, Math.min(10, dry.lines().size())));
			}
		} catch (IOException ignored) {}
		return out;
	}

	/** {@code .stasis audit fix}: apply the home-link repair now. */
	public StasisStore.HomeRepair repairHomesNow() throws IOException {
		if (store == null) return new StasisStore.HomeRepair(0, 0, List.of());
		StasisStore.HomeRepair r = store.repairHomeAssignments(true);
		for (String line : r.lines()) GreenCorps.LOG.info("[Stasis] home repair: {}", line);
		PanelReload.reloadModuleScreen();
		return r;
	}

	/** Perf toggle from {@code .stasis perf on|off}. */
	public void setPerfLog(boolean on) {
		perfLog.set(on);
		StasisPerf.setEnabled(on);
	}

	public long sessionStartMs() {
		return sessionStartMs > 0 ? sessionStartMs : System.currentTimeMillis();
	}

	public String localPlayerIgn() {
		return localPlayerIgn;
	}

	public PanelViewMode panelViewMode() {
		return panelViewMode;
	}

	public void setPanelViewMode(PanelViewMode mode) {
		if (mode != null) panelViewMode = mode;
	}

	public void cyclePanelViewMode() {
		panelViewMode = panelViewMode == PanelViewMode.PEARLS
			? PanelViewMode.CHAMBERS : PanelViewMode.PEARLS;
	}

	/**
	 * Left click / keybind: turn on if off, otherwise open the monitor.
	 * Active checkbox on the module settings screen still toggles for real.
	 */
	@Override
	public void toggle() {
		if (isSettingsToggle()) {
			super.toggle();
			return;
		}
		if (!isActive()) {
			super.toggle();
			return;
		}
		openOverview();
	}

	/** After config load, keep monitoring on without opening the window. */
	public void ensureAlwaysOn() {
		if (!isActive()) super.toggle();
	}

	private boolean isSettingsToggle() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !(client.currentScreen instanceof meteordevelopment.meteorclient.gui.screens.ModuleScreen screen)) {
			return false;
		}
		return ((ModuleScreenAccessor) (Object) screen).greencorps$module() == this;
	}

	@Override
	public void onDeactivate() {
		flushStore();
		clearSessionCaches();
		if (settleTracker != null) settleTracker.clear();
		liveEntityToPearl.clear();
		lastNotifiedLoaded.clear();
		pearlLastLiveMs.clear();
		slotLastLiveMs.clear();
		recentSmallHopMs.clear();
		hadPlayerPos = false;
		dialogOpen = false;
	}

	@Override
	public WWidget getWidget(GuiTheme theme) {
		ensureStore();
		return StasisPanel.build(theme, this);
	}

	public void openOverview() {
		ensureStore();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		if (meteordevelopment.meteorclient.gui.GuiThemes.get() == null) return;
		if (client.currentScreen instanceof StasisOverviewScreen) return;
		var screen = new StasisOverviewScreen(this);
		screen.parent = client.currentScreen;
		client.setScreen(screen);
	}

	public StasisHome addHomeHere(String name, int radiusChunks, int pearls) throws IOException {
		if (mc.player == null || mc.world == null) throw new IOException("no player");
		return addHomeAt(
			mc.player.getBlockX(),
			mc.player.getBlockY(),
			mc.player.getBlockZ(),
			mc.world.getRegistryKey().getValue().toString(),
			name,
			radiusChunks,
			pearls
		);
	}

	public StasisHome addHomeAt(int x, int y, int z, String dimension, String name,
								int radiusChunks, int pearls) throws IOException {
		if (!ensureStore()) throw new IOException("store not open");
		String n = (name == null || name.isBlank())
			? "stasis-" + (x >> 4) + "-" + (z >> 4)
			: name.trim();
		int r = Math.min(4, Math.max(1, radiusChunks));
		int p = Math.min(8, Math.max(1, pearls));
		StasisHome h = new StasisHome(n, dimension, x, y, z, r, p);
		store.addHome(h);
		// Do not seed lastReminderMs — first shortfall should alert ASAP.
		scanHomeLoadedChunks(h);
		scanHomeLivePearls(h);
		return h;
	}

	/**
	 * Scan already-loaded chunks inside a home fence (same path as chunk-load discover).
	 * Called after home create so slots appear without leave/reenter.
	 */
	/** Manual Scan from the monitor: rescan loaded home chunks and chat capacity lines. */
	public void manualScanHomes() {
		if (!ensureStore() || mc.world == null) {
			StasisChat.warn("Manual scan failed: store or world unavailable.");
			return;
		}
		String dim = mc.world.getRegistryKey().getValue().toString();
		int scanned = 0;
		try {
			store.collapseDuplicateColumns();
			for (StasisHome home : store.homes()) {
				if (!home.sameDimension(dim)) continue;
				if (!anyHomeChunkLoaded(home)) continue;
				scanHomeLoadedChunks(home);
				scanHomeLivePearls(home);
				slowValidateHome(home);
				StasisStore.HomeCapacity cap = capacityFor(home);
				StasisChat.info(home.name + ": " + cap.summaryLine());
				scanned++;
			}
		} catch (IOException e) {
			StasisChat.warn("Manual scan save failed: " + e.getClass().getSimpleName());
			return;
		}
		if (scanned == 0) {
			StasisChat.info("Manual scan: no loaded stasis homes in this dimension.");
		} else {
			StasisChat.info("Manual scan done (" + scanned + " home" + (scanned == 1 ? "" : "s") + ").");
		}
		PanelReload.reloadModuleScreen();
	}

	private void slowValidateHome(StasisHome home) throws IOException {
		if (home == null || store == null) return;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return;
		for (StasisChamberSlot slot : store.slotsForHome(home.id)) {
			if (!isChunkLoaded(slot.rootX, slot.rootZ) && !isChunkLoaded(slot.x, slot.z)) continue;
			applyGeometryValidate(cw, slot);
		}
	}

	public void scanHomeLoadedChunks(StasisHome home) {
		if (home == null || store == null || mc.world == null) return;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return;
		String dim = cw.getRegistryKey().getValue().toString();
		if (!home.sameDimension(dim)) return;
		int rcx = home.centerChunkX();
		int rcz = home.centerChunkZ();
		int rad = Math.max(0, home.radiusChunks);
		try {
			for (int dx = -rad; dx <= rad; dx++) {
				for (int dz = -rad; dz <= rad; dz++) {
					int cx = rcx + dx;
					int cz = rcz + dz;
					if (!home.containsChunk(cx, cz)) continue;
					WorldChunk chunk = cw.getChunkManager().getWorldChunk(cx, cz);
					if (chunk == null) continue;
					for (ChamberScanner.Found found : ChamberScanner.scanChunk(cw, chunk, home)) {
						upsertFound(dim, home, found);
					}
				}
			}
		} catch (IOException e) {
			StasisChat.warn("Home scan save failed: " + e.getClass().getSimpleName());
		}
	}

	/** One-pass bind of live ender pearls already inside the home fence. */
	private void scanHomeLivePearls(StasisHome home) {
		if (home == null || store == null || mc.world == null) return;
		String dim = mc.world.getRegistryKey().getValue().toString();
		if (!home.sameDimension(dim)) return;
		long now = System.currentTimeMillis();
		pearlClaimsThisTick.clear();
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof EnderPearlEntity pearl)) continue;
			UUID eid = pearl.getUuid();
			int bx = (int) Math.floor(pearl.getX());
			int by = (int) Math.floor(pearl.getY());
			int bz = (int) Math.floor(pearl.getZ());
			if (!home.containsBlock(bx, bz)) continue;
			Optional<StasisChamberSlot> matched = matchCachedSlot(dim, bx, by, bz);
			OwnerInfo owner = resolveOwner(pearl, matched.map(s -> s.x).orElse(bx),
				matched.map(s -> s.y).orElse(by), matched.map(s -> s.z).orElse(bz));
			boolean isOwn = isOwnOwner(owner);
			boolean isOtherKnown = (owner.uuid != null || (owner.ign != null && !owner.ign.isBlank())) && !isOwn;
			if (isOtherKnown && !trackOthers.get()) continue;
			try {
				if (matched.isPresent()) {
					StasisGeometry.Match geom = StasisChamberSlot.KIND_HONEY.equals(matched.get().kind)
						? StasisGeometry.Match.HONEY : StasisGeometry.Match.WATER;
					healSlotGeometry(matched.get(), bx, by, bz);
					instantLoadMatched(matched.get(), eid, bx, by, bz, dim, owner, isOwn, geom, now);
				} else {
					StasisGeometry.Match geom = probeGeometry(pearl.getX(), pearl.getY(), pearl.getZ());
					if (!StasisGeometry.isInstalled(geom)) continue;
					PearlSettleTracker.Settled settled = new PearlSettleTracker.Settled(
						eid, pearl.getX(), pearl.getY(), pearl.getZ(), dim,
						owner.uuidStr, owner.ign, geom);
					onPearlInstalled(settled, isOwn, null);
				}
			} catch (IOException ignored) {}
		}
	}

	public StasisHome addHomeFromWaypoint(String name, int x, Integer y, int z, String dimension,
										  int radiusChunks, int pearls) throws IOException {
		int yy = y != null ? y : (mc.player != null ? mc.player.getBlockY() : 64);
		return addHomeAt(x, yy, z, dimension, name, radiusChunks, pearls);
	}

	@EventHandler
	private void onInteractItem(InteractItemEvent event) {
		if (mc.player == null || mc.world == null) return;
		if (mc.player.getStackInHand(event.hand).getItem() != Items.ENDER_PEARL) return;
		localPlayerUuid = mc.player.getUuid();
		localPlayerIgn = mc.player.getName().getString();
		localThrowUntilMs = System.currentTimeMillis() + 8000;
		localThrowClaimedEntity = null;
		// Snapshot so sitting pearls without getOwner are never blamed on this throw.
		Set<UUID> snap = new HashSet<>();
		for (Entity entity : mc.world.getEntities()) {
			if (entity instanceof EnderPearlEntity) snap.add(entity.getUuid());
		}
		pearlsAtLocalThrow = snap;
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		long t0 = StasisPerf.start();
		try {
			tickMonitor();
		} finally {
			StasisPerf.endTick(t0);
		}
	}

	private void tickMonitor() {
		if (mc.world == null || mc.player == null) return;
		if (pendingRepairNotice != null) {
			StasisChat.info("Repaired home links (" + pendingRepairNotice.slotsMoved() + " slots, "
				+ pendingRepairNotice.pearlsMoved() + " pearls). Details: .stasis audit / latest.log");
			pendingRepairNotice = null;
		}
		if (!ensureStore()) return;
		rebuildTrackerIfNeeded();

		String dim = mc.world.getRegistryKey().getValue().toString();
		localPlayerUuid = mc.player.getUuid();
		localPlayerIgn = mc.player.getName().getString();

		Set<UUID> seen = new HashSet<>();
		Map<String, Boolean> pearlStillLive = new HashMap<>();
		Set<String> slotsWithPearl = new HashSet<>();
		Map<String, OwnerInfo> liveOwners = new HashMap<>();
		long now = System.currentTimeMillis();

		long tLoop = StasisPerf.start();
		pearlClaimsThisTick.clear();
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof EnderPearlEntity pearl)) continue;
			UUID eid = pearl.getUuid();
			int bx = (int) Math.floor(pearl.getX());
			int by = (int) Math.floor(pearl.getY());
			int bz = (int) Math.floor(pearl.getZ());

			// Realtime watch: home perimeter only (skip far pearls / block probe).
			if (!inAnyHome(dim, bx, bz)) continue;
			seen.add(eid);

			Optional<StasisChamberSlot> matched = matchCachedSlot(dim, bx, by, bz);
			int seatX = matched.map(s -> s.x).orElse(bx);
			int seatY = matched.map(s -> s.y).orElse(by);
			int seatZ = matched.map(s -> s.z).orElse(bz);
			OwnerInfo owner = ownerFor(pearl, seatX, seatY, seatZ, now);
			if (matched.isPresent()) liveOwners.put(matched.get().id, owner);
			boolean isOwn = isOwnOwner(owner);
			boolean isOtherKnown = (owner.uuid != null || (owner.ign != null && !owner.ign.isBlank())) && !isOwn;
			if (isOtherKnown && !trackOthers.get()) continue;

			StasisGeometry.Match geom;
			boolean geomOk;
			if (matched.isPresent()) {
				geom = StasisChamberSlot.KIND_HONEY.equals(matched.get().kind)
					? StasisGeometry.Match.HONEY : StasisGeometry.Match.WATER;
				geomOk = true;
				slotsWithPearl.add(matched.get().id);
				slotLastLiveMs.put(matched.get().id, now);
				// Cached seat: instant Loaded (no settle wait).
				try {
					maybeHealSlotGeometry(matched.get(), bx, by, bz, now);
					instantLoadMatched(matched.get(), eid, bx, by, bz, dim, owner, isOwn, geom, now);
				} catch (IOException ignored) {}
			} else {
				geom = probeGeometry(pearl.getX(), pearl.getY(), pearl.getZ());
				geomOk = StasisGeometry.isInstalled(geom);
				PearlSettleTracker.Settled settled = settleTracker.observe(new PearlSettleTracker.Sample(
					eid, pearl.getX(), pearl.getY(), pearl.getZ(), dim,
					owner.uuidStr, owner.ign, geomOk, geom));
				if (settled != null) {
					onPearlInstalled(settled, isOwn, null);
				}
			}

			if (!geomOk) continue;

			String rememberedId = liveEntityToPearl.get(eid);
			if (rememberedId != null) {
				pearlStillLive.put(rememberedId, true);
				pearlLastLiveMs.put(rememberedId, now);
			}
		}
		StasisPerf.stop(StasisPerf.Bucket.ENTITY_LOOP, tLoop);
		liveSlotOwnersTick = liveOwners;

		settleTracker.endTick(seen);

		long tEmpty = StasisPerf.start();
		// Empty every tick (short grace) — realtime grey / Empty edge.
		// Only while the player can still observe the pearl entity (tracking range).
		for (StasisPearl p : store.pearls()) {
			if (!p.sameDimension(dim)) continue;
			if (!isChunkLoaded(p.x, p.z)) continue;
			boolean live = pearlStillLive.containsKey(p.id);
			if (live) {
				pearlLastLiveMs.put(p.id, now);
				continue;
			}
			if (!p.loaded) continue;
			if (!canObservePearl(p.x, p.y, p.z)) continue;
			Long lastLive = pearlLastLiveMs.get(p.id);
			if (lastLive != null && now - lastLive < EMPTY_GRACE_MS) continue;
			p.loaded = false;
			p.lastSeenMs = now;
			try {
				store.updatePearl(p);
				syncHubForPearl(p);
				notifyPearlState(p, false);
				Optional<StasisChamberSlot> slot = store.findSlotNear(dim, p.x, p.y, p.z, 2);
				if (slot.isEmpty() && p.homeId != null) {
					for (StasisChamberSlot s : store.slotsForHome(p.homeId)) {
						if (p.id.equals(s.pearlId)) {
							slot = Optional.of(s);
							break;
						}
					}
				}
				slot.ifPresent(this::validateSlotOnEdge);
			} catch (IOException ignored) {}
		}

		for (StasisChamberSlot slot : store.slots()) {
			if (!slot.sameDimension(dim)) continue;
			if (!isChunkLoaded(slot.x, slot.z)) continue;
			if (slotsWithPearl.contains(slot.id)) continue;
			if (slot.pearlId == null) continue;
			StasisPearl bound = store.findPearlById(slot.pearlId).orElse(null);
			if (bound == null || !bound.loaded) continue;
			if (!canObservePearl(slot.x, slot.y, slot.z)) continue;
			Long last = slotLastLiveMs.get(slot.id);
			if (last != null && now - last < EMPTY_GRACE_MS) continue;
			if (pearlStillLive.containsKey(bound.id)) continue;
			Long pearlLive = pearlLastLiveMs.get(bound.id);
			if (pearlLive != null && now - pearlLive < EMPTY_GRACE_MS) continue;
			bound.loaded = false;
			bound.lastSeenMs = now;
			try {
				store.updatePearl(bound);
				syncHubForPearl(bound);
				notifyPearlState(bound, false);
				validateSlotOnEdge(slot);
			} catch (IOException ignored) {}
		}

		StasisPerf.stop(StasisPerf.Bucket.EMPTY_SCAN, tEmpty);
		long tDisc = StasisPerf.start();
		processDiscoverQueue(dim);
		StasisPerf.stop(StasisPerf.Bucket.DISCOVER, tDisc);
		processScanQueue(dim);

		tickCounter++;
		long tPeriodic = StasisPerf.start();
		if (tickCounter % 20 == 0) {
			checkMissingHomes(dim);
			checkLeaveMissingHomes(dim);
		}
		if (tickCounter % SLOW_VALIDATE_TICKS == 0) {
			slowValidateRenderSlots(dim);
		}
		if (tickCounter % 20 == 0) pruneOwnerCache(now);
		StasisPerf.stop(StasisPerf.Bucket.PERIODIC, tPeriodic);
		long tHub = StasisPerf.start();
		maintainHubWaypoints();
		StasisPerf.stop(StasisPerf.Bucket.HUB_SYNC, tHub);
		long tFlush = StasisPerf.start();
		store.flushIfDue(now);
		StasisPerf.stop(StasisPerf.Bucket.FLUSH, tFlush);
	}

	/**
	 * Owner for a live pearl entity, cached ~1s per entity so ticks and render frames do not
	 * re-probe signs / the player list. {@code anySeat} (labels) accepts the cached answer
	 * even when resolved at the slot seat rather than the entity's own cell.
	 */
	private OwnerInfo ownerFor(EnderPearlEntity pearl, int seatX, int seatY, int seatZ, long now) {
		return ownerFor(pearl, seatX, seatY, seatZ, now, false);
	}

	private OwnerInfo ownerFor(EnderPearlEntity pearl, int seatX, int seatY, int seatZ, long now, boolean anySeat) {
		UUID eid = pearl.getUuid();
		long seatKey = BlockPos.asLong(seatX, seatY, seatZ);
		CachedOwner c = ownerCache.get(eid);
		// A brand-new entity during an unclaimed local throw may still become ours: no cache.
		boolean throwWindow = now < localThrowUntilMs && localThrowClaimedEntity == null
			&& !pearlsAtLocalThrow.contains(eid);
		if (c != null && now < c.untilMs() && !throwWindow && (anySeat || c.seatKey() == seatKey)) {
			return c.info();
		}
		OwnerInfo info = resolveOwner(pearl, seatX, seatY, seatZ);
		ownerCache.put(eid, new CachedOwner(info, seatKey, now + OWNER_CACHE_MS));
		return info;
	}

	private void pruneOwnerCache(long now) {
		ownerCache.values().removeIf(c -> now >= c.untilMs());
	}

	/** Heal a seated slot only when it is not OK, the pearl cell moved, or every 5s. */
	private void maybeHealSlotGeometry(StasisChamberSlot slot, int bx, int by, int bz, long now)
		throws IOException {
		long cell = BlockPos.asLong(bx, by, bz);
		Long last = slotLastHealMs.get(slot.id);
		Long lastCell = slotLastHealCell.get(slot.id);
		boolean due = !Boolean.TRUE.equals(slot.geometryOk)
			|| lastCell == null || lastCell != cell
			|| last == null || now - last >= HEAL_INTERVAL_MS;
		if (!due) return;
		slotLastHealMs.put(slot.id, now);
		slotLastHealCell.put(slot.id, cell);
		long t0 = StasisPerf.start();
		healSlotGeometry(slot, bx, by, bz);
		StasisPerf.stop(StasisPerf.Bucket.HEAL, t0);
	}

	private boolean claimedByOther(String pearlId, UUID eid) {
		UUID holder = pearlClaimsThisTick.get(pearlId);
		return holder != null && !holder.equals(eid);
	}

	/** Instant Loaded when a live pearl sits in a cached chamber seat. */
	private void instantLoadMatched(StasisChamberSlot slot, UUID eid, int bx, int by, int bz,
									String dim, OwnerInfo owner, boolean isOwn,
									StasisGeometry.Match geom, long now) throws IOException {
		// Record identity: this entity's own record, then the slot's, then a free one nearby.
		// Never take a record another live entity already holds this tick (neighbour swap).
		StasisPearl pearl = null;
		String remembered = liveEntityToPearl.get(eid);
		if (remembered != null && !claimedByOther(remembered, eid)) {
			pearl = store.findPearlById(remembered).orElse(null);
		}
		if (pearl == null && slot.pearlId != null && !claimedByOther(slot.pearlId, eid)) {
			pearl = store.findPearlById(slot.pearlId).orElse(null);
		}
		if (pearl == null) {
			pearl = store.findFreePearlNear(dim, bx, by, bz, 2, slot.id, pearlClaimsThisTick.keySet())
				.orElse(null);
		}
		boolean created = false;
		boolean wasLoaded;
		if (pearl == null) {
			if (!isOwn && !trackOthers.get()) return;
			pearl = new StasisPearl(dim, bx, by, bz);
			pearl.chamberKind = StasisGeometry.chamberKind(geom);
			pearl.loaded = true;
			pearl.homeId = slot.homeId;
			applyOwner(pearl, owner, now);
			store.addPearl(pearl);
			created = true;
			wasLoaded = false;
		} else {
			wasLoaded = pearl.loaded;
			String beforeUuid = pearl.ownerUuid;
			String beforeIgn = pearl.ownerIgn;
			boolean beforeConfirmed = pearl.ownerConfirmedMs > 0;
			String beforeKind = pearl.chamberKind;
			boolean beforeFirstSeen = pearl.firstSeenMs > 0;
			pearl.loaded = true;
			pearl.lastSeenMs = now;
			if (pearl.firstSeenMs <= 0) pearl.firstSeenMs = now;
			if (!wasLoaded) {
				pearl.x = bx;
				pearl.y = by;
				pearl.z = bz;
			}
			pearl.chamberKind = StasisGeometry.chamberKind(geom);
			applyOwner(pearl, owner, now);
			retractFalseLocalOwner(pearl, eid, owner);
			boolean changed = !wasLoaded
				|| !java.util.Objects.equals(beforeUuid, pearl.ownerUuid)
				|| !java.util.Objects.equals(beforeIgn, pearl.ownerIgn)
				|| beforeConfirmed != (pearl.ownerConfirmedMs > 0)
				|| !java.util.Objects.equals(beforeKind, pearl.chamberKind)
				|| beforeFirstSeen != (pearl.firstSeenMs > 0);
			// Heartbeat-only (lastSeenMs / ownerConfirmedMs) stays in memory until a slow flush.
			if (changed) store.updatePearl(pearl);
			else store.markHeartbeat();
		}
		liveEntityToPearl.put(eid, pearl.id);
		pearlClaimsThisTick.put(pearl.id, eid);
		pearlLastLiveMs.put(pearl.id, now);
		slotLastLiveMs.put(slot.id, now);
		bindPearlToSlot(pearl, slot);

		if (isOwn && created) {
			Optional<StasisHome> home = store.findCoveringHome(dim, bx, bz);
			if (home.isEmpty() && !promptedPearlIds.contains(pearl.id)) {
				promptedPearlIds.add(pearl.id);
				promptAddHome(pearl);
			}
		}

		if (!wasLoaded) {
			syncHubForPearl(pearl);
			notifyPearlState(pearl, true);
			if (isOwn) playAlert(1.2f);
			PanelReload.reloadModuleScreen();
		}
	}

	private void applyOwner(StasisPearl pearl, OwnerInfo owner, long now) {
		if (owner == null) return;
		boolean hasUuid = owner.uuidStr != null && !owner.uuidStr.isBlank();
		boolean hasIgn = owner.ign != null && !owner.ign.isBlank();
		if (!hasUuid && !hasIgn) return;

		if (owner.confirmed) {
			// Projectile getOwner is ground truth — always update.
			if (hasUuid) pearl.ownerUuid = owner.uuidStr;
			if (hasIgn) pearl.ownerIgn = owner.ign;
			pearl.ownerConfirmedMs = now;
			return;
		}

		// Heuristic / sign: fill blank only; never overwrite a different owner UUID.
		boolean blankUuid = pearl.ownerUuid == null || pearl.ownerUuid.isBlank();
		if (!blankUuid && hasUuid && !pearl.ownerUuid.equals(owner.uuidStr)) return;
		if (blankUuid && hasUuid) pearl.ownerUuid = owner.uuidStr;
		boolean blankIgn = pearl.ownerIgn == null || pearl.ownerIgn.isBlank();
		if (blankIgn && hasIgn) pearl.ownerIgn = owner.ign;
		else if (hasIgn && blankUuid && pearl.ownerIgn != null
			&& !pearl.ownerIgn.equalsIgnoreCase(owner.ign) && !owner.confirmed) {
			// Do not replace an existing different Assumed IGN with another Assumed.
			return;
		}
		// Leave ownerConfirmedMs alone — stays Assumed until getOwner confirms.
	}

	/**
	 * Drop poisoned "this is mine" when the live pearl has no projectile owner and
	 * was not the single local-throw claim. EntityOwner-null ≠ local player.
	 */
	private void retractFalseLocalOwner(StasisPearl pearl, UUID eid, OwnerInfo live) {
		if (pearl == null || live == null) return;
		if (live.uuid != null) return;
		if (eid != null && eid.equals(localThrowClaimedEntity)) return;
		String me = localUuidString();
		if (me == null || !pearl.ownedBy(me)) return;
		pearl.ownerUuid = null;
		pearl.ownerIgn = null;
		pearl.ownerConfirmedMs = 0;
	}

	@EventHandler
	private void onChunkData(ChunkDataEvent event) {
		if (mc.world == null || store == null) return;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return;
		WorldChunk chunk = event.chunk();
		if (chunk == null) return;
		String dim = cw.getRegistryKey().getValue().toString();
		int cx = chunk.getPos().x;
		int cz = chunk.getPos().z;
		for (StasisHome home : store.homes()) {
			if (!home.sameDimension(dim)) continue;
			if (!home.containsChunk(cx, cz)) continue;
			ChamberScanner.Box box = primedBox(home);
			if (box != null && boxTouchesChunk(box, cx, cz)) {
				enqueueScan(new ScanJob(dim, home.id, cx, cz, box), true);
			}
			// Unprimed homes walk the fence; primed homes still get one lazy full pass per session.
			String fullKey = dim + "|" + home.id + "|" + cx + "|" + cz;
			boolean firstFull = fullScannedChunks.add(fullKey);
			if (box == null || firstFull) {
				enqueueScan(new ScanJob(dim, home.id, cx, cz, null), false);
			}
		}
	}

	/** Known chambers of a home, padded; null when the home has no viable slots yet. */
	private ChamberScanner.Box primedBox(StasisHome home) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		boolean any = false;
		for (StasisChamberSlot s : store.slotsForHome(home.id)) {
			if (Boolean.FALSE.equals(s.geometryOk)) continue;
			any = true;
			minX = Math.min(minX, Math.min(s.x, s.rootX));
			maxX = Math.max(maxX, Math.max(s.x, s.rootX));
			minY = Math.min(minY, Math.min(s.y, s.rootY));
			maxY = Math.max(maxY, Math.max(s.y, s.rootY));
			minZ = Math.min(minZ, Math.min(s.z, s.rootZ));
			maxZ = Math.max(maxZ, Math.max(s.z, s.rootZ));
		}
		if (!any) return null;
		return new ChamberScanner.Box(
			minX - PRIMED_PAD_XZ, minY - PRIMED_PAD_DOWN, minZ - PRIMED_PAD_XZ,
			maxX + PRIMED_PAD_XZ, maxY + PRIMED_PAD_UP, maxZ + PRIMED_PAD_XZ);
	}

	private static boolean boxTouchesChunk(ChamberScanner.Box box, int cx, int cz) {
		int x0 = cx << 4;
		int z0 = cz << 4;
		return box.maxX() >= x0 && box.minX() <= x0 + 15
			&& box.maxZ() >= z0 && box.minZ() <= z0 + 15;
	}

	private void enqueueScan(ScanJob job, boolean front) {
		if (!scanQueued.add(job.key())) return;
		if (scanQueue.size() >= SCAN_QUEUE_CAP) {
			ScanJob dropped = scanQueue.removeLast();
			scanQueued.remove(dropped.key());
		}
		if (front) scanQueue.addFirst(job);
		else scanQueue.addLast(job);
	}

	/** Drain chunk scans section by section within {@link #SCAN_BUDGET_NS} per tick. */
	private void processScanQueue(String dim) {
		if (scanQueue.isEmpty() || store == null) return;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return;
		StasisPerf.gauge(StasisPerf.Counter.SCAN_QUEUE, scanQueue.size());
		long t0 = System.nanoTime();
		long deadline = t0 + SCAN_BUDGET_NS;
		try {
			while (!scanQueue.isEmpty() && System.nanoTime() < deadline) {
				ScanJob job = scanQueue.peekFirst();
				WorldChunk chunk = job.dim.equals(dim)
					? cw.getChunkManager().getWorldChunk(job.cx, job.cz) : null;
				StasisHome home = chunk == null ? null : store.findHomeById(job.homeId).orElse(null);
				if (chunk == null || home == null) {
					finishScan(job);
					continue;
				}
				int sections = chunk.getSectionArray().length;
				if (job.nextSection < sections && job.found.size() < ChamberScanner.MAX_ROOTS_PER_CHUNK) {
					int visited = ChamberScanner.scanSection(cw, chunk, home, job.nextSection++, job.box, job.found);
					StasisPerf.add(StasisPerf.Counter.SCAN_BLOCKS, visited);
					continue;
				}
				finishScan(job);
				for (ChamberScanner.Found found : job.found) {
					upsertFound(dim, home, found);
				}
			}
		} catch (IOException e) {
			StasisChat.warn("Chamber discover save failed: " + e.getClass().getSimpleName());
		}
		if (StasisPerf.enabled()) StasisPerf.record(StasisPerf.Bucket.CHUNK_SCAN, System.nanoTime() - t0);
	}

	/** {@code job} is always the queue head here. */
	private void finishScan(ScanJob job) {
		scanQueue.pollFirst();
		scanQueued.remove(job.key());
	}

	@EventHandler
	private void onBlockUpdate(BlockUpdateEvent event) {
		if (!isActive() || mc.world == null || store == null || event.pos == null) return;
		if (!ChamberScanner.isInterestingChange(event.oldState, event.newState)) return;
		String dim = mc.world.getRegistryKey().getValue().toString();
		if (!inAnyHome(dim, event.pos.getX(), event.pos.getZ())) return;
		boolean markerish = isOwnerMarkerBlock(event.newState) || isOwnerMarkerBlock(event.oldState);
		if (markerish) {
			SignOwnerProbe.clearCache();
			ownerCache.clear();
		}
		enqueueDiscover(event.pos);
	}

	private static boolean isOwnerMarkerBlock(BlockState state) {
		if (state == null) return false;
		Block b = state.getBlock();
		return b instanceof net.minecraft.block.AbstractSignBlock
			|| b instanceof net.minecraft.block.AbstractBannerBlock
			|| b instanceof net.minecraft.block.SkullBlock
			|| b instanceof net.minecraft.block.WallSkullBlock;
	}

	private void enqueueDiscover(BlockPos pos) {
		if (pos == null) return;
		for (BlockPos p : discoverQueue) {
			if (p.equals(pos)) return;
		}
		while (discoverQueue.size() >= DISCOVER_QUEUE_CAP) discoverQueue.removeFirst();
		discoverQueue.addLast(pos.toImmutable());
	}

	private void processDiscoverQueue(String dim) {
		if (discoverQueue.isEmpty() || store == null) return;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return;
		int n = 0;
		try {
			while (n < DISCOVER_PER_TICK && !discoverQueue.isEmpty()) {
				BlockPos pos = discoverQueue.removeFirst();
				n++;
				Optional<StasisHome> home = store.findCoveringHome(dim, pos.getX(), pos.getZ());
				if (home.isEmpty()) continue;
				for (ChamberScanner.Found found : ChamberScanner.discoverNear(cw, pos)) {
					if (!home.get().containsBlock(found.rootX(), found.rootZ())) continue;
					upsertFound(dim, home.get(), found);
				}
			}
		} catch (IOException e) {
			StasisChat.warn("Chamber live-discover save failed: " + e.getClass().getSimpleName());
		}
	}

	private void upsertFound(String dim, StasisHome home, ChamberScanner.Found found) throws IOException {
		Optional<StasisChamberSlot> existing =
			store.findSlotByRoot(dim, found.rootX(), found.rootY(), found.rootZ());
		if (existing.isEmpty()) {
			existing = store.findSlotByColumn(dim, found.kind(), found.rootX(), found.rootZ());
		}
		if (existing.isPresent()) {
			StasisChamberSlot s = existing.get();
			// Found by this home's scan inside its fence: claim slots wired to a foreign home.
			if (!store.homeCovers(s.homeId, dim, found.rootX(), found.rootZ())) {
				store.reassignSlotHome(s, home.id);
			}
			s.geometryOk = true;
			s.rootX = found.rootX();
			s.rootY = found.rootY();
			s.rootZ = found.rootZ();
			s.x = found.seatX();
			s.y = found.seatY();
			s.z = found.seatZ();
			geometryFailStreak.remove(s.id);
			store.upsertSlot(s);
		} else {
			StasisChamberSlot created = new StasisChamberSlot(
				home.id, dim, found.seatX(), found.seatY(), found.seatZ(),
				found.rootX(), found.rootY(), found.rootZ(), found.kind());
			store.upsertSlot(created);
		}
	}

	private boolean inAnyHome(String dim, int blockX, int blockZ) {
		if (store == null) return false;
		for (StasisHome h : store.homes()) {
			if (h.sameDimension(dim) && h.containsBlock(blockX, blockZ)) return true;
		}
		return false;
	}

	/** Prefer seat proximity, then water/honey column match against cached roots. */
	private Optional<StasisChamberSlot> matchCachedSlot(String dim, int x, int y, int z) {
		if (store == null) return Optional.empty();
		StasisChamberSlot best = null;
		int bestD = Integer.MAX_VALUE;
		for (StasisChamberSlot s : store.slots()) {
			if (!s.sameDimension(dim)) continue;
			if (!s.matchesPearl(x, y, z)) continue;
			int dx = s.x - x;
			int dy = s.y - y;
			int dz = s.z - z;
			int d = dx * dx + dy * dy + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return Optional.ofNullable(best);
	}

	private void bindPearlToSlot(StasisPearl pearl, StasisChamberSlot slot) throws IOException {
		if (pearl == null || slot == null) return;
		for (StasisChamberSlot s : store.slots()) {
			if (pearl.id.equals(s.pearlId) && !s.id.equals(slot.id)) {
				s.pearlId = null;
				store.updateSlot(s);
			}
		}
		if (!pearl.id.equals(slot.pearlId)) {
			slot.pearlId = pearl.id;
			store.updateSlot(slot);
		}
		if (slot.homeId != null && !slot.homeId.equals(pearl.homeId)
			&& store.homeCovers(slot.homeId, pearl.dimension, pearl.x, pearl.z)) {
			pearl.homeId = slot.homeId;
			store.updatePearl(pearl);
		}
	}

	/** Root-only check for one slot after Empty (or rare sweep). */
	private void validateSlotOnEdge(StasisChamberSlot slot) {
		if (mc.world == null || store == null || slot == null) return;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return;
		try {
			applyGeometryValidate(cw, slot);
		} catch (IOException ignored) {}
	}

	/** Slow sweep: render-distance slots only (~30s). */
	private void slowValidateRenderSlots(String dim) {
		if (mc.world == null || store == null) return;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return;
		int checked = 0;
		try {
			for (StasisChamberSlot slot : store.slots()) {
				if (checked >= MAX_VALIDATE_PER_PASS) break;
				if (!slot.sameDimension(dim)) continue;
				BlockPos pos = new BlockPos(slot.x, slot.y, slot.z);
				if (!EntityUtils.isInRenderDistance(pos)) continue;
				if (!isChunkLoaded(slot.rootX, slot.rootZ)) continue;
				checked++;
				applyGeometryValidate(cw, slot);
			}
		} catch (IOException e) {
			StasisChat.warn("Chamber validate save failed: " + e.getClass().getSimpleName());
		}
	}

	/**
	 * Apply validate with fail streak grace; refresh seat coords when column still OK.
	 */
	private void applyGeometryValidate(net.minecraft.client.world.ClientWorld cw, StasisChamberSlot slot)
		throws IOException {
		Boolean ok = ChamberScanner.validateSlot(cw, slot);
		if (ok == null) return; // chunk unloaded
		if (ok) {
			geometryFailStreak.remove(slot.id);
			ChamberScanner.Found refreshed = ChamberScanner.refreshRoot(cw, slot);
			if (refreshed != null) {
				boolean seatMoved = slot.x != refreshed.seatX() || slot.y != refreshed.seatY()
					|| slot.z != refreshed.seatZ();
				if (seatMoved) {
					slot.x = refreshed.seatX();
					slot.y = refreshed.seatY();
					slot.z = refreshed.seatZ();
				}
				if (!Boolean.TRUE.equals(slot.geometryOk) || seatMoved) {
					slot.geometryOk = true;
					store.upsertSlot(slot);
					return;
				}
			}
			store.setSlotGeometryOk(slot, true);
			return;
		}
		int streak = geometryFailStreak.merge(slot.id, 1, Integer::sum);
		if (streak >= GEOMETRY_FAIL_STREAK) {
			store.setSlotGeometryOk(slot, false);
		}
	}

	/** When a live pearl sits in a seat, heal false Broken and refresh seat Y. */
	private void healSlotGeometry(StasisChamberSlot slot, int bx, int by, int bz) throws IOException {
		if (slot == null || store == null) return;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return;
		ChamberScanner.Found refreshed = ChamberScanner.refreshRoot(cw, slot);
		if (refreshed == null) {
			BlockPos seat = new BlockPos(bx, by, bz);
			boolean seatOk = StasisChamberSlot.KIND_HONEY.equals(slot.kind)
				? ChamberScanner.isValidHoneySeat(cw, seat)
				: ChamberScanner.isValidWaterSeat(cw, seat);
			if (!seatOk) return;
			slot.geometryOk = true;
			slot.x = bx;
			slot.y = by;
			slot.z = bz;
			geometryFailStreak.remove(slot.id);
			store.upsertSlot(slot);
			return;
		}
		geometryFailStreak.remove(slot.id);
		slot.geometryOk = true;
		slot.x = refreshed.seatX();
		slot.y = refreshed.seatY();
		slot.z = refreshed.seatZ();
		store.upsertSlot(slot);
	}

	@EventHandler
	private void onPacket(PacketEvent.Receive event) {
		if (!pullRemind.get() || !(event.packet instanceof PlayerPositionLookS2CPacket pkt)) return;
		if (mc.player == null || mc.world == null) return;
		if (!hadPlayerPos) {
			hadPlayerPos = true;
			return;
		}
		if (System.currentTimeMillis() < localThrowUntilMs) return;

		PlayerPosition applied = PlayerPosition.apply(
			PlayerPosition.fromEntity(mc.player), pkt.change(), pkt.relatives());
		Vec3d dest = applied.position();
		double dx = dest.x - mc.player.getX();
		double dz = dest.z - mc.player.getZ();
		double dy = dest.y - mc.player.getY();
		double xz = Math.sqrt(dx * dx + dz * dz);
		long now = System.currentTimeMillis();
		if (xz < 4.0 && Math.abs(dy) >= 4.0) return; // vertical-only
		if (xz <= PULL_MIN_BLOCKS) {
			recentSmallHopMs.addLast(now);
			while (!recentSmallHopMs.isEmpty() && now - recentSmallHopMs.peekFirst() > 500L) {
				recentSmallHopMs.removeFirst();
			}
			return;
		}
		// Burst of small hops just before a large jump → treat as phase noise
		int smallRecent = 0;
		for (Long t : recentSmallHopMs) {
			if (now - t <= 500L) smallRecent++;
		}
		if (smallRecent >= 3) {
			recentSmallHopMs.clear();
			return;
		}
		recentSmallHopMs.clear();

		if (now - lastPullRemindMs < pullRemindInterval.get().seconds * 1000L) return;
		firePullRemind((int) Math.floor(dest.x), (int) Math.floor(dest.y), (int) Math.floor(dest.z), (int) xz);
	}

	@EventHandler
	private void onRender3D(Render3DEvent event) {
		long t0 = StasisPerf.start();
		try {
			renderEsp(event);
		} finally {
			StasisPerf.stop(StasisPerf.Bucket.RENDER3D, t0);
		}
	}

	private void renderEsp(Render3DEvent event) {
		if (!esp.get() || mc.world == null || store == null) return;
		String dim = mc.world.getRegistryKey().getValue().toString();
		String me = localUuidString();

		// Live pearl presence for ESP (do not wait for persisted loaded); built in the tick.
		Map<String, OwnerInfo> liveSlotOwners = liveSlotOwnersTick;

		for (StasisChamberSlot slot : store.slots()) {
			if (!slot.sameDimension(dim)) continue;
			if (Boolean.FALSE.equals(slot.geometryOk)) continue;
			BlockPos pos = new BlockPos(slot.x, slot.y, slot.z);
			if (!EntityUtils.isInRenderDistance(pos)) continue;
			if (!isChunkLoaded(slot.x, slot.z)) continue;

			OwnerInfo live = liveSlotOwners.get(slot.id);
			SettingColor color;
			boolean emptySeat = false;
			if (live != null) {
				// Trust live entity owner only — never fall back to poisoned store UUID.
				boolean own = isOwnOwner(live);
				if (!own && !espOthers.get()) {
					continue;
				}
				color = own ? loadedColor.get() : otherColor.get();
			} else {
				StasisPearl bound = slot.pearlId != null
					? store.findPearlById(slot.pearlId).orElse(null) : null;
				// Out of pearl observe range: keep last-known Loaded (no false Empty).
				if (bound != null && bound.loaded && !canObservePearl(slot.x, slot.y, slot.z)) {
					boolean own = me != null && bound.ownedBy(me);
					if (!own && !espOthers.get()) continue;
					color = own ? loadedColor.get() : otherColor.get();
				} else {
					emptySeat = true;
					color = emptyColor.get();
				}
			}
			if (emptySeat && hideEmptyEsp.get()) continue;
			drawChamberEsp(event, slot, pos, color);
		}

		// Pearls without a slot still get a column (column match, not seat-near merge).
		for (StasisPearl p : store.pearls()) {
			if (!p.sameDimension(dim)) continue;
			boolean slotted = false;
			for (StasisChamberSlot s : store.slots()) {
				if (!s.sameDimension(dim) || Boolean.FALSE.equals(s.geometryOk)) continue;
				if (s.matchesPearl(p.x, p.y, p.z)) {
					slotted = true;
					break;
				}
			}
			if (slotted) continue;
			BlockPos pos = new BlockPos(p.x, p.y, p.z);
			if (!EntityUtils.isInRenderDistance(pos)) continue;
			if (!p.loaded && !isChunkLoaded(p.x, p.z)) continue;

			boolean own = me != null && p.ownedBy(me);
			if (!own && !espOthers.get()) continue;
			if (!p.loaded && hideEmptyEsp.get()) continue;

			SettingColor color;
			if (own) color = p.loaded ? loadedColor.get() : emptyColor.get();
			else color = otherColor.get();
			drawChamberEsp(event, null, pos, color);
		}
	}

	private void onPearlInstalled(PearlSettleTracker.Settled settled, boolean isOwn, StasisChamberSlot matchedSlot) {
		try {
			if (!ensureStore()) return;
			if (!isOwn && !trackOthers.get()) return;

			Optional<StasisPearl> existing = store.findPearlNear(
				settled.dimension, settled.blockX(), settled.blockY(), settled.blockZ(), 3);
			StasisPearl pearl;
			boolean created;
			boolean wasLoaded;
			long now = System.currentTimeMillis();
			if (existing.isPresent()) {
				pearl = existing.get();
				created = false;
				wasLoaded = pearl.loaded;
				if (!wasLoaded) {
					pearl.x = settled.blockX();
					pearl.y = settled.blockY();
					pearl.z = settled.blockZ();
					pearl.loaded = true;
					pearl.lastSeenMs = now;
					if (pearl.firstSeenMs <= 0) pearl.firstSeenMs = now;
				}
				if (settled.ownerUuid != null || settled.ownerIgn != null) {
					if (settled.ownerUuid != null) pearl.ownerUuid = settled.ownerUuid;
					if (settled.ownerIgn != null) pearl.ownerIgn = settled.ownerIgn;
					pearl.ownerConfirmedMs = now;
				}
				pearl.chamberKind = StasisGeometry.chamberKind(settled.matchKind);
				store.updatePearl(pearl);
			} else {
				pearl = new StasisPearl(settled.dimension, settled.blockX(), settled.blockY(), settled.blockZ());
				pearl.ownerUuid = settled.ownerUuid;
				pearl.ownerIgn = settled.ownerIgn;
				if (settled.ownerUuid != null || settled.ownerIgn != null) {
					pearl.ownerConfirmedMs = now;
				}
				pearl.chamberKind = StasisGeometry.chamberKind(settled.matchKind);
				pearl.loaded = true;
				store.addPearl(pearl);
				created = true;
				wasLoaded = false;
			}
			liveEntityToPearl.put(settled.entityId, pearl.id);
			pearlLastLiveMs.put(pearl.id, now);

			Optional<StasisHome> home = store.findCoveringHome(settled.dimension, settled.x, settled.z);
			if (isOwn) {
				if (home.isPresent()) {
					if (!home.get().id.equals(pearl.homeId)) {
						pearl.homeId = home.get().id;
						store.updatePearl(pearl);
					}
				} else if (created && !promptedPearlIds.contains(pearl.id)) {
					promptedPearlIds.add(pearl.id);
					promptAddHome(pearl);
				}
			}

			StasisChamberSlot slot = matchedSlot;
			if (slot == null) {
				slot = matchCachedSlot(settled.dimension, settled.blockX(), settled.blockY(), settled.blockZ())
					.orElse(null);
			}
			if (slot == null && home.isPresent() && StasisGeometry.isInstalled(settled.matchKind)) {
				slot = createSlotFromSettled(home.get(), settled);
			}
			if (slot != null) {
				bindPearlToSlot(pearl, slot);
				slotLastLiveMs.put(slot.id, now);
			} else {
				store.bindPearlToNearestSlot(pearl, 2);
			}

			if (!wasLoaded) {
				syncHubForPearl(pearl);
				notifyPearlState(pearl, true);
				if (isOwn) playAlert(1.2f);
			}
		} catch (IOException e) {
			StasisChat.warn("Save failed: " + e.getClass().getSimpleName());
		}
	}

	/** Recover a missed chamber from a settled pearl (no full chunk walk). */
	private StasisChamberSlot createSlotFromSettled(StasisHome home, PearlSettleTracker.Settled settled)
		throws IOException {
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return null;
		int bx = settled.blockX();
		int by = settled.blockY();
		int bz = settled.blockZ();
		String kind;
		int rx, ry, rz, sx, sy, sz;
		if (settled.matchKind == StasisGeometry.Match.WATER) {
			ChamberScanner.Found water = ChamberScanner.retargetWaterFromSeat(cw, bx, by, bz);
			if (water == null) return null;
			kind = StasisChamberSlot.KIND_WATER;
			rx = water.rootX();
			ry = water.rootY();
			rz = water.rootZ();
			sx = water.seatX();
			sy = water.seatY();
			sz = water.seatZ();
		} else if (settled.matchKind == StasisGeometry.Match.HONEY) {
			kind = StasisChamberSlot.KIND_HONEY;
			sx = bx;
			sy = by;
			sz = bz;
			// Prefer honey neighbor as root column xz; piston below
			rx = bx;
			ry = by - 1;
			rz = bz;
			for (Direction dir : Direction.Type.HORIZONTAL) {
				BlockPos honey = new BlockPos(bx, by, bz).offset(dir);
				if (cw.getBlockState(honey).isOf(Blocks.HONEY_BLOCK)) {
					rx = honey.getX();
					rz = honey.getZ();
					ry = honey.getY();
					for (int i = 0; i < 8; i++) {
						BlockPos down = new BlockPos(rx, ry - i, rz);
						BlockState st = cw.getBlockState(down);
						if (st.isOf(Blocks.PISTON) || st.isOf(Blocks.STICKY_PISTON)) {
							ry = down.getY();
							break;
						}
					}
					break;
				}
			}
		} else {
			return null;
		}

		Optional<StasisChamberSlot> existing = store.findSlotByRoot(settled.dimension, rx, ry, rz);
		if (existing.isPresent()) return existing.get();
		StasisChamberSlot created = new StasisChamberSlot(
			home.id, settled.dimension, sx, sy, sz, rx, ry, rz, kind);
		store.upsertSlot(created);
		return store.findSlotByRoot(settled.dimension, rx, ry, rz).orElse(created);
	}

	/** Instant in-render-distance chat on loaded state flips (deduped). */
	private void notifyPearlState(StasisPearl pearl, boolean loaded) {
		if (pearl == null || pearl.id == null) return;
		Boolean prev = lastNotifiedLoaded.put(pearl.id, loaded);
		if (prev != null && prev == loaded) return;

		BlockPos pos = new BlockPos(pearl.x, pearl.y, pearl.z);
		if (!EntityUtils.isInRenderDistance(pos)) return;

		String me = localUuidString();
		boolean own = me != null && pearl.ownedBy(me);
		boolean other = !own;
		if (own && !chatOwnUpdates.get()) return;
		if (other) {
			if (!trackOthers.get() || !chatOthersUpdates.get()) return;
		}

		String who = pearl.ownerDisplay(sessionStartMs());
		String kind = ChamberKind.label(pearl.chamberKind);
		String at = pearl.x + " " + pearl.y + " " + pearl.z;
		if (loaded) {
			StasisChat.installed("Pearl " + (prev == null ? "installed" : "reloaded")
				+ " (" + kind + ") @ " + at + " [" + who + "]" + (own ? "" : " (other)"));
		} else {
			StasisChat.empty("Pearl empty (" + kind + ") @ " + at + " [" + who + "]" + (own ? "" : " (other)"));
		}
		PanelReload.reloadModuleScreen();
	}

	private void promptAddHome(StasisPearl pearl) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		String suggested = suggestHomeName(pearl);
		var screen = new StasisHomePromptScreen(this, pearl, suggested, defaultPearls.get(), homeRadius.get());
		screen.parent = client.currentScreen;
		client.setScreen(screen);
	}

	private String suggestHomeName(StasisPearl pearl) {
		BaseBackup bb = BaseBackup.get();
		if (bb != null) {
			bb.ensureStore();
			if (bb.homeStore() != null) {
				for (Home h : bb.homeStore().homes()) {
					if (h.dimension != null && h.dimension.equals(pearl.dimension)
						&& h.containsBlock(pearl.x, pearl.z)) {
						return h.name;
					}
				}
			}
		}
		return "stasis-" + (pearl.x >> 4) + "-" + (pearl.z >> 4);
	}

	private void checkMissingHomes(String dim) {
		long now = System.currentTimeMillis();
		long interval = reminderInterval.get().seconds * 1000L;
		boolean onlyCurrent = summaryScope.get() == SummaryScope.Current;
		StasisHome standing = null;
		if (onlyCurrent && mc.player != null) {
			standing = store.findCoveringHome(dim, mc.player.getX(), mc.player.getZ()).orElse(null);
			if (standing == null) return;
		}
		boolean first = true;
		for (StasisHome home : store.homes()) {
			if (!home.sameDimension(dim)) continue;
			if (onlyCurrent && (standing == null || !home.id.equals(standing.id))) continue;
			if (!anyHomeChunkLoaded(home)) continue;
			StasisStore.HomeCapacity cap = capacityFor(home);
			if (!cap.anyAlertShort()) continue;
			Long last = lastReminderMs.get(home.id);
			if (last != null && now - last < interval) continue;
			if (!onlyCurrent && !first) {
				StasisChat.info(" ");
			}
			first = false;
			fireMissingAlert(home, cap, false);
		}
	}

	/** Edge-trigger once when leaving a short-quota home past leave-alert-blocks. */
	private void checkLeaveMissingHomes(String dim) {
		if (!leaveAlert.get() || mc.player == null || store == null) return;
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		int threshold = Math.max(16, leaveAlertBlocks.get());
		for (StasisHome home : store.homes()) {
			if (!home.sameDimension(dim)) continue;
			StasisStore.HomeCapacity cap = capacityFor(home);
			boolean shortQuota = cap.anyAlertShort();
			boolean inCore = home.containsBlock(px, pz);
			int beyond = home.blocksBeyond(px, pz);

			if (!shortQuota) {
				leaveAlertArmed.remove(home.id);
				continue;
			}
			if (inCore) {
				leaveAlertArmed.add(home.id);
				continue;
			}
			if (leaveAlertArmed.contains(home.id) && beyond >= threshold) {
				leaveAlertArmed.remove(home.id);
				fireMissingAlert(home, cap, true);
			}
		}
	}

	private boolean alertBusy() {
		return mc.player != null && (mc.player.isGliding() || mc.player.hurtTime > 0);
	}

	private void fireMissingAlert(StasisHome home, StasisStore.HomeCapacity cap, boolean leaving) {
		int missing = cap.alertMissing();
		String prefix = leaving ? "Leaving: " : "";
		String ctx = prefix + home.name + ": " + cap.quotasLine()
			+ " (" + missing + " alert-missing) ["
			+ ChamberKind.label(firstChamberKind(home)) + "]";
		String capLine = cap.capacityAlertLine();
		boolean delivered = false;
		boolean passive = missingWarning.get() == WarningMode.Passive || alertBusy();
		if (chatMissing.get() || leaving || passive) {
			StasisChat.missing(ctx);
			StasisChat.missing(capLine);
			delivered = true;
		}
		if (alertSound.get()) {
			playAlert(0.7f);
			delivered = true;
		}
		if (passive) {
			pushPassiveToast(leaving ? "Leaving - Pearl Missing" : "Pearl Missing",
				home.name + ": " + cap.quotasLine() + " (" + missing + " alert-missing)  "
					+ capLine);
			if (delivered) lastReminderMs.put(home.id, System.currentTimeMillis());
			return;
		}
		final boolean deliveredChatOrSound = delivered;
		if (!dialogOpen && mc.currentScreen == null) {
			StasisMissingDialog.show(this, home, cap,
				() -> {
					dialogOpen = true;
					lastReminderMs.put(home.id, System.currentTimeMillis());
				},
				() -> {
					if (deliveredChatOrSound) {
						lastReminderMs.put(home.id, System.currentTimeMillis());
					}
				});
			return;
		}
		if (deliveredChatOrSound) {
			lastReminderMs.put(home.id, System.currentTimeMillis());
		}
	}

	private void firePullRemind(int x, int y, int z, int xzBlocks) {
		lastPullRemindMs = System.currentTimeMillis();
		String msg = "Stasis pull ~" + xzBlocks + "b -> @ " + x + " " + y + " " + z
			+ ". Set a pearl so you can return.";
		StasisChat.pulled(msg);
		playAlert(0.85f);
		if (missingWarning.get() == WarningMode.Passive || alertBusy()) {
			pushPassiveToast("Set a pearl", "Pulled ~" + xzBlocks + "b - place a pearl to return.");
			return;
		}
		if (!dialogOpen) {
			dialogOpen = true;
			StasisRemindDialog.show(this, x, y, z, xzBlocks, () -> dialogOpen = false);
		}
	}

	private void pushPassiveToast(String title, String detail) {
		long until = System.currentTimeMillis() + PASSIVE_TOAST_MS;
		passiveToasts.addLast(new PassiveToast(title == null ? "" : title, detail == null ? "" : detail, until));
		while (passiveToasts.size() > 3) passiveToasts.removeFirst();
	}

	@EventHandler
	private void onRender2D(Render2DEvent event) {
		long now = System.currentTimeMillis();
		while (!passiveToasts.isEmpty() && passiveToasts.peekFirst().untilMs() <= now) {
			passiveToasts.removeFirst();
		}
		if (!passiveToasts.isEmpty()) {
			TextRenderer tr = TextRenderer.get();
			tr.begin(1.15, false, true);
			double scale = mc.getWindow().getScaledWidth();
			double y = 36;
			for (PassiveToast toast : passiveToasts) {
				String line1 = toast.title();
				String line2 = toast.detail();
				double w1 = tr.getWidth(line1);
				double w2 = line2.isBlank() ? 0 : tr.getWidth(line2);
				double textW = Math.max(w1, w2);
				double padX = 10;
				double padY = 6;
				double boxW = textW + padX * 2;
				double boxH = tr.getHeight() + padY * 2
					+ (line2.isBlank() ? 0 : tr.getHeight() + 4);
				double x = (scale - boxW) / 2.0;
				Renderer2D.COLOR.begin();
				Renderer2D.COLOR.quad(x, y, boxW, boxH, new Color(28, 30, 36, 210));
				Renderer2D.COLOR.quad(x, y, boxW, 1, new Color(70, 120, 180, 220));
				Renderer2D.COLOR.render(null);
				double ty = y + padY;
				tr.render(line1, x + padX, ty, Color.WHITE, true);
				ty += tr.getHeight() + 4;
				if (!line2.isBlank()) {
					tr.render(line2, x + padX, ty, new Color(180, 220, 255), true);
				}
				y += boxH + 8;
			}
			tr.end();
		}
		long t0 = StasisPerf.start();
		renderPearlLabels(event);
		StasisPerf.stop(StasisPerf.Bucket.RENDER2D_LABELS, t0);
	}

	/** Rusher-like thrown-pearl nametags: Pearl: IGN, optional * when assumed. */
	private void renderPearlLabels(Render2DEvent event) {
		if (!pearlLabels.get() || mc.world == null || mc.player == null) return;
		if (mc.options.hudHidden) return;

		long now = System.currentTimeMillis();
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof EnderPearlEntity pearl)) continue;
			int bx = (int) Math.floor(pearl.getX());
			int by = (int) Math.floor(pearl.getY());
			int bz = (int) Math.floor(pearl.getZ());
			OwnerInfo owner = ownerFor(pearl, bx, by, bz, now, true);
			if (owner == null || owner.ign == null || owner.ign.isBlank()) continue;

			Utils.set(pearlTagPos, entity, event.tickDelta);
			pearlTagPos.add(0, entity.getHeight() + 0.35, 0);
			if (!NametagUtils.to2D(pearlTagPos, pearlLabelScale.get())) continue;

			String prefix = "Pearl: ";
			String ign = owner.ign;
			String suffix = owner.confirmed ? "" : "*";
			SettingColor occ = isOwnOwner(owner) ? loadedColor.get() : otherColor.get();
			Color ignColor = new Color(occ.r, occ.g, occ.b, 255);

			TextRenderer tr = TextRenderer.get();
			NametagUtils.begin(pearlTagPos);
			tr.beginBig();
			double wPrefix = tr.getWidth(prefix);
			double wIgn = tr.getWidth(ign);
			double wSuffix = suffix.isEmpty() ? 0 : tr.getWidth(suffix);
			double totalW = wPrefix + wIgn + wSuffix;
			double h = tr.getHeight();
			double x0 = -totalW / 2.0;
			double y0 = -h;

			Renderer2D.COLOR.begin();
			Renderer2D.COLOR.quad(x0 - 1, y0 - 1, totalW + 2, h + 2, PEARL_LABEL_BG);
			Renderer2D.COLOR.render(null);

			double x = x0;
			tr.render(prefix, x, y0, PEARL_LABEL_WHITE, true);
			x += wPrefix;
			tr.render(ign, x, y0, ignColor, true);
			x += wIgn;
			if (!suffix.isEmpty()) {
				tr.render(suffix, x, y0, PEARL_LABEL_WHITE, true);
			}
			tr.end();
			NametagUtils.end();
		}
	}

	/**
	 * Pearl entities despawn from the client beyond vanilla tracking (~4 chunks).
	 * Cap observe range by min(view, simulation, 4) chunks with a small edge inset.
	 */
	private boolean canObservePearl(int x, int y, int z) {
		if (mc.player == null || mc.world == null) return false;
		double dx = mc.player.getX() - (x + 0.5);
		double dz = mc.player.getZ() - (z + 0.5);
		double lim = pearlObserveBlocks();
		return dx * dx + dz * dz <= lim * lim;
	}

	/** Horizontal observe radius in blocks (auto from view/sim, capped at pearl tracking). */
	public int pearlObserveBlocks() {
		int view = 4;
		int sim = 4;
		try {
			if (mc != null && mc.options != null) {
				view = Math.max(2, mc.options.getViewDistance().getValue());
				sim = Math.max(2, mc.options.getSimulationDistance().getValue());
			}
		} catch (Throwable ignored) {}
		int chunks = Math.min(PEARL_TRACK_CHUNKS, Math.min(view, sim));
		return Math.max(16, chunks * 16 - OBSERVE_INSET_BLOCKS);
	}

	private record PassiveToast(String title, String detail, long untilMs) {}

	public void acknowledgeMissing(StasisHome home) {
		if (home != null) lastReminderMs.put(home.id, System.currentTimeMillis());
		dialogOpen = false;
	}

	public void acknowledgePull() {
		dialogOpen = false;
		lastPullRemindMs = System.currentTimeMillis();
	}

	private String firstChamberKind(StasisHome home) {
		for (StasisPearl p : store.pearls()) {
			if (home.id.equals(p.homeId) || (home.sameDimension(p.dimension) && home.containsBlock(p.x, p.z))) {
				return p.chamberKind;
			}
		}
		return ChamberKind.UNKNOWN;
	}

	private void syncHubForPearl(StasisPearl pearl) {
		if (pearl == null || store == null || !hubWaypoint.get()) return;
		StasisHome home = null;
		if (pearl.homeId != null) {
			home = store.findHomeById(pearl.homeId).orElse(null);
		}
		if (home == null) {
			home = store.findCoveringHome(pearl.dimension, pearl.x, pearl.z).orElse(null);
		}
		if (home != null) syncHubWaypoint(home);
	}

	/** Sync or clear hub pins when the toggle flips; refresh hubs periodically. */
	private void maintainHubWaypoints() {
		if (store == null) return;
		boolean on = hubWaypoint.get();
		if (lastHubWaypoint == null || lastHubWaypoint != on) {
			if (on) {
				for (StasisHome home : store.homes()) {
					XaeroWaypoints.removeLegacyStasisChamberPins(
						home.dimension, home.centerX, home.centerZ, home.radiusChunks);
					syncHubWaypoint(home);
				}
			} else {
				for (StasisHome home : store.homes()) {
					clearHubWaypoint(home);
				}
			}
			lastHubWaypoint = on;
			return;
		}
		if (!on) return;
		if (tickCounter % 100 != 0) return;
		for (StasisHome home : store.homes()) {
			syncHubWaypoint(home);
		}
	}

	private void syncHubWaypoint(StasisHome home) {
		if (home == null || store == null || !hubWaypoint.get()) return;
		List<StasisChamberSlot> seats = new ArrayList<>();
		for (StasisChamberSlot s : store.slotsForHome(home.id)) {
			if (Boolean.FALSE.equals(s.geometryOk)) continue;
			seats.add(s);
		}
		int hx;
		int hy;
		int hz;
		if (seats.isEmpty()) {
			hx = home.centerX;
			hy = home.centerY + 1;
			hz = home.centerZ;
		} else {
			long sx = 0;
			long sz = 0;
			List<Integer> ys = new ArrayList<>();
			for (StasisChamberSlot s : seats) {
				sx += s.x;
				sz += s.z;
				ys.add(s.y);
			}
			ys.sort(Integer::compareTo);
			hx = (int) Math.round(sx / (double) seats.size());
			hz = (int) Math.round(sz / (double) seats.size());
			hy = ys.get(ys.size() / 2) + 1;
		}
		BlockPos prev = null;
		if (home.hubWaypointX != null && home.hubWaypointY != null && home.hubWaypointZ != null) {
			prev = new BlockPos(home.hubWaypointX, home.hubWaypointY, home.hubWaypointZ);
		}
		boolean anyLoaded = false;
		String me = localUuidString();
		for (StasisPearl p : store.pearls()) {
			if (!home.id.equals(p.homeId) && !(home.sameDimension(p.dimension) && home.containsBlock(p.x, p.z))) {
				continue;
			}
			if (!p.loaded) continue;
			if (me != null && p.ownedBy(me)) {
				anyLoaded = true;
				break;
			}
			if (me == null && p.loaded) {
				anyLoaded = true;
				break;
			}
		}
		BlockPos written = XaeroWaypoints.upsertStasisHub(
			prev, new BlockPos(hx, hy, hz), home.name, anyLoaded);
		if (written != null && !written.equals(prev)) {
			home.hubWaypointX = written.getX();
			home.hubWaypointY = written.getY();
			home.hubWaypointZ = written.getZ();
			try { store.updateHome(home); } catch (IOException ignored) {}
		}
	}

	private void clearHubWaypoint(StasisHome home) {
		if (home == null) return;
		if (home.hubWaypointX != null && home.hubWaypointY != null && home.hubWaypointZ != null) {
			XaeroWaypoints.removeWaypointAt(
				new BlockPos(home.hubWaypointX, home.hubWaypointY, home.hubWaypointZ));
		}
		home.hubWaypointX = null;
		home.hubWaypointY = null;
		home.hubWaypointZ = null;
		try {
			if (store != null) store.updateHome(home);
		} catch (IOException ignored) {}
	}

	private StasisGeometry.Match probeGeometry(double x, double y, double z) {
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return StasisGeometry.Match.NONE;
		return ChamberScanner.matchAt(cw, x, y, z);
	}

	private boolean isWaterish(BlockPos pos) {
		if (mc.world == null) return false;
		BlockState state = mc.world.getBlockState(pos);
		Block block = state.getBlock();
		if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN) return true;
		FluidState fluid = state.getFluidState();
		return !fluid.isEmpty() && fluid.isOf(net.minecraft.fluid.Fluids.WATER);
	}

	private boolean isOwnOwner(OwnerInfo owner) {
		if (owner == null) return false;
		if (localPlayerUuid != null && owner.uuid != null && localPlayerUuid.equals(owner.uuid)) return true;
		return localPlayerIgn != null && owner.ign != null
			&& localPlayerIgn.equalsIgnoreCase(owner.ign);
	}

	private OwnerInfo resolveOwner(EnderPearlEntity pearl, int seatX, int seatY, int seatZ) {
		long t0 = StasisPerf.start();
		try {
			return resolveOwnerUncached(pearl, seatX, seatY, seatZ);
		} finally {
			StasisPerf.stop(StasisPerf.Bucket.OWNER_RESOLVE, t0);
		}
	}

	private OwnerInfo resolveOwnerUncached(EnderPearlEntity pearl, int seatX, int seatY, int seatZ) {
		String uuidStr = null;
		String ign = null;
		UUID ownerUuid = null;
		try {
			if (pearl instanceof ProjectileEntity proj) {
				Entity owner = proj.getOwner();
				if (owner != null) {
					ownerUuid = owner.getUuid();
					uuidStr = ownerUuid.toString();
					ign = owner.getName().getString();
				}
			}
		} catch (Throwable ignored) {}

		if (ownerUuid != null) {
			return new OwnerInfo(ownerUuid, uuidStr, ign, true, signRgbMatching(seatX, seatY, seatZ, ign));
		}

		// Local-throw heuristic: only brand-new pearl entities after our use, at most one claim.
		UUID eid = pearl.getUuid();
		long now = System.currentTimeMillis();
		if (localPlayerUuid != null && now < localThrowUntilMs) {
			if (eid.equals(localThrowClaimedEntity)) {
				return new OwnerInfo(localPlayerUuid, localPlayerUuid.toString(), localPlayerIgn, false,
					signRgbMatching(seatX, seatY, seatZ, localPlayerIgn));
			}
			if (localThrowClaimedEntity == null && !pearlsAtLocalThrow.contains(eid)) {
				localThrowClaimedEntity = eid;
				return new OwnerInfo(localPlayerUuid, localPlayerUuid.toString(), localPlayerIgn, false,
					signRgbMatching(seatX, seatY, seatZ, localPlayerIgn));
			}
		}

		// Owner-marker IGN fallback (Assumed until getOwner confirms).
		if (signOwnerFallback.get() && mc.world instanceof net.minecraft.client.world.ClientWorld cw) {
			Optional<SignOwnerProbe.Hit> signHit = Optional.empty();
			try {
				signHit = SignOwnerProbe.hitAtSeat(cw, seatX, seatY, seatZ, ownerMarkerMode());
			} catch (Throwable t) {
				if (!signOwnerProbeFailWarned) {
					signOwnerProbeFailWarned = true;
					StasisChat.warn("Owner marker probe unavailable: " + t.toString());
				}
			}
			if (signHit.isPresent()) {
				SignOwnerProbe.Hit hit = signHit.get();
				String sIgn = hit.ign();
				UUID uuid = null;
				String uStr = null;
				if (localPlayerIgn != null && localPlayerIgn.equalsIgnoreCase(sIgn) && localPlayerUuid != null) {
					uuid = localPlayerUuid;
					uStr = localPlayerUuid.toString();
				} else if (mc.getNetworkHandler() != null) {
					for (var e : mc.getNetworkHandler().getPlayerList()) {
						if (e != null && e.getProfile() != null
							&& sIgn.equalsIgnoreCase(e.getProfile().getName())) {
							uuid = e.getProfile().getId();
							uStr = uuid != null ? uuid.toString() : null;
							break;
						}
					}
				}
				return new OwnerInfo(uuid, uStr, sIgn, false, hit.rgb());
			}
		}
		return new OwnerInfo(null, null, null, false, null);
	}

	private SignOwnerProbe.MarkerMode ownerMarkerMode() {
		return switch (ownerMarker.get()) {
			case Banner -> SignOwnerProbe.MarkerMode.BANNER;
			case Skull -> SignOwnerProbe.MarkerMode.SKULL;
			case Signs -> SignOwnerProbe.MarkerMode.SIGNS;
		};
	}

	/** Marker RGB when a nearby owner marker matches this IGN (cached probe). */
	private Integer signRgbMatching(int seatX, int seatY, int seatZ, String ign) {
		if (ign == null || ign.isBlank()) return null;
		if (!(mc.world instanceof net.minecraft.client.world.ClientWorld cw)) return null;
		try {
			Optional<SignOwnerProbe.Hit> hit = SignOwnerProbe.hitAtSeat(cw, seatX, seatY, seatZ, ownerMarkerMode());
			if (hit.isPresent() && ign.equalsIgnoreCase(hit.get().ign())) {
				return hit.get().rgb();
			}
		} catch (Throwable t) {
			if (!signOwnerProbeFailWarned) {
				signOwnerProbeFailWarned = true;
				StasisChat.warn("Owner marker probe unavailable: " + t.toString());
			}
		}
		return null;
	}

	private boolean anyHomeChunkLoaded(StasisHome home) {
		if (mc.world == null) return false;
		int r = Math.max(0, home.radiusChunks);
		int cx = home.centerChunkX();
		int cz = home.centerChunkZ();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (isChunkLoadedCoords(cx + dx, cz + dz)) return true;
			}
		}
		return false;
	}

	private boolean isChunkLoaded(int blockX, int blockZ) {
		return isChunkLoadedCoords(blockX >> 4, blockZ >> 4);
	}

	private boolean isChunkLoadedCoords(int chunkX, int chunkZ) {
		if (mc.world == null) return false;
		WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ);
		return chunk != null;
	}

	private void playAlert(float pitch) {
		if (!alertSound.get() || mc.world == null || mc.player == null) return;
		mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
			SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.55f, pitch);
	}

	/**
	 * Highlight chamber geometry from the stored seat (or pearl fallback): honey beside the seat,
	 * water/bubble column from soul sand through the top seat block.
	 */
	private void drawChamberEsp(Render3DEvent event, StasisChamberSlot slot, BlockPos pearlPos,
								SettingColor color) {
		lineColor.set(color);
		sideColor.set(color);
		sideColor.a = fillOpacity.get();
		ShapeMode mode = shapeMode.get();
		boolean water = slot == null
			? StasisChamberSlot.KIND_WATER.equals(guessKindAt(pearlPos))
			: StasisChamberSlot.KIND_WATER.equals(
				slot.kind == null || slot.kind.isBlank() ? StasisChamberSlot.KIND_WATER : slot.kind);

		if (espSeatOnly.get()) {
			drawBlockBox(event, pearlPos, mode);
			return;
		}
		boolean drew = false;

		if (!water) {
			// Honey: horizontal neighbors at seat Y and Y±1
			for (int dy = -1; dy <= 1; dy++) {
				BlockPos row = pearlPos.add(0, dy, 0);
				for (Direction dir : Direction.Type.HORIZONTAL) {
					BlockPos hp = row.offset(dir);
					if (mc.world.getBlockState(hp).isOf(Blocks.HONEY_BLOCK)) {
						drawBlockBox(event, hp, mode);
						drew = true;
					}
				}
			}
		}

		// Water column: prefer stored root XZ so every chamber draws the same height band.
		int colX = slot != null && water ? slot.rootX : pearlPos.getX();
		int colZ = slot != null && water ? slot.rootZ : pearlPos.getZ();
		int startY = slot != null && water ? Math.max(slot.rootY + 1, pearlPos.getY()) : pearlPos.getY();
		int minY = mc.world.getBottomY();
		int maxY = mc.world.getTopYInclusive();
		int top = startY;
		int bottom = startY;
		boolean anyWater = isWaterish(new BlockPos(colX, startY, colZ));
		if (!anyWater) {
			anyWater = isWaterish(new BlockPos(colX, startY + 1, colZ))
				|| isWaterish(new BlockPos(colX, startY - 1, colZ));
		}
		if (anyWater || (slot != null && water)) {
			while (top < maxY && isWaterish(new BlockPos(colX, top + 1, colZ))) top++;
			while (bottom > minY && isWaterish(new BlockPos(colX, bottom - 1, colZ))) bottom--;
			if (slot != null && water) {
				bottom = Math.max(bottom, slot.rootY + 1);
				top = Math.max(top, slot.y);
			}
			for (int y = bottom; y <= top; y++) {
				BlockPos wp = new BlockPos(colX, y, colZ);
				if (isWaterish(wp)) {
					drawBlockBox(event, wp, mode);
					drew = true;
				}
			}
			BlockPos under = new BlockPos(colX, bottom - 1, colZ);
			if (slot != null && water) {
				under = new BlockPos(slot.rootX, slot.rootY, slot.rootZ);
			}
			if (under.getY() >= minY && mc.world.getBlockState(under).isOf(Blocks.SOUL_SAND)) {
				drawBlockBox(event, under, mode);
				drew = true;
			}
		}

		if (!drew) {
			drawBlockBox(event, pearlPos, mode);
			drawBlockBox(event, pearlPos.up(), mode);
			drawBlockBox(event, pearlPos.down(), mode);
		}
	}

	private String guessKindAt(BlockPos pos) {
		if (mc.world == null || pos == null) return StasisChamberSlot.KIND_WATER;
		for (Direction dir : Direction.Type.HORIZONTAL) {
			if (mc.world.getBlockState(pos.offset(dir)).isOf(Blocks.HONEY_BLOCK)) {
				return StasisChamberSlot.KIND_HONEY;
			}
		}
		return StasisChamberSlot.KIND_WATER;
	}

	private void drawBlockBox(Render3DEvent event, BlockPos pos, ShapeMode mode) {
		event.renderer.box(
			pos.getX(), pos.getY(), pos.getZ(),
			pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
			sideColor, lineColor, mode, 0);
	}

	private void rebuildTrackerIfNeeded() {
		if (settleTracker == null
			|| lastSettleTicks == null || lastSettleTicks != settleTicks.get()
			|| lastSettleBox == null || Math.abs(lastSettleBox - settleBox.get()) > 1e-6) {
			rebuildTracker();
		}
	}

	private void rebuildTracker() {
		lastSettleTicks = settleTicks.get();
		lastSettleBox = settleBox.get();
		settleTracker = new PearlSettleTracker(settleBox.get(), settleTicks.get());
	}

	/** @param signRgb optional 0xRRGGBB from a matching coloured owner sign */
	private record OwnerInfo(UUID uuid, String uuidStr, String ign, boolean confirmed, Integer signRgb) {}

	private record CachedOwner(OwnerInfo info, long seatKey, long untilMs) {}

	/** One chunk x home scan, resumed one section per step; {@code box} null = full fence. */
	private static final class ScanJob {
		final String dim;
		final String homeId;
		final int cx;
		final int cz;
		final ChamberScanner.Box box;
		final List<ChamberScanner.Found> found = new ArrayList<>();
		int nextSection;

		ScanJob(String dim, String homeId, int cx, int cz, ChamberScanner.Box box) {
			this.dim = dim;
			this.homeId = homeId;
			this.cx = cx;
			this.cz = cz;
			this.box = box;
		}

		String key() {
			return dim + "|" + homeId + "|" + cx + "|" + cz + (box == null ? "|full" : "|box");
		}
	}
}
