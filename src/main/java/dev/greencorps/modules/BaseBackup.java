package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.backup.BackupEngine;
import dev.greencorps.backup.ChunkCapture;
import dev.greencorps.backup.CommitRiskScanner;
import dev.greencorps.backup.ContainerCapture;
import dev.greencorps.backup.Fsck;
import dev.greencorps.backup.Home;
import dev.greencorps.backup.HomeStore;
import dev.greencorps.backup.Retention;
import dev.greencorps.backup.Scrubber;
import dev.greencorps.backup.SnapshotIndex;
import dev.greencorps.backup.SnapshotManifest;
import dev.greencorps.backup.StoreGc;
import dev.greencorps.backup.StoreHealth;
import dev.greencorps.backup.StoreStats;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.compat.XaeroBridge;
import dev.greencorps.export.AnvilExporter;
import dev.greencorps.export.ExportRehearsal;
import dev.greencorps.export.PlayableSavePublisher;
import dev.greencorps.gui.BaseBackupPanel;
import dev.greencorps.gui.BaseBackupScreen;
import dev.greencorps.gui.CommitRiskConfirm;
import dev.greencorps.mixin.ModuleScreenAccessor;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Auto-captures user homes into a content-addressed versioned store with
 * Time Machine export. Xaero map overlay is optional.
 */
public class BaseBackup extends Module {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/basebackup");

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgCommit = settings.createGroup("Commit");
	private final SettingGroup sgRender = settings.createGroup("Render");
	private final SettingGroup sgEmbed = settings.createGroup("Embed");
	private final SettingGroup sgTrust = settings.createGroup("Trust");

	private final Setting<Keybind> manualCommitBind = sgGeneral.add(new KeybindSetting.Builder()
		.name("manual-commit-bind")
		.description("Commit the home you are inside (manual; ignores min-commit-gap). Unbound by default.")
		.defaultValue(Keybind.none())
		.action(this::manualCommitFromKeybind)
		.build()
	);

	private final Setting<HomeChat.Verbosity> chatVerbosity = sgGeneral.add(new EnumSetting.Builder<HomeChat.Verbosity>()
		.name("chat-verbosity")
		.description("Enter/leave/commit chat detail. Off / Minimal / Full. (.home info)")
		.defaultValue(HomeChat.Verbosity.MINIMAL)
		.build()
	);

	private final Setting<Integer> maxRadiusChunks = sgGeneral.add(new IntSetting.Builder()
		.name("max-radius-chunks")
		.description("Hard cap on home radius in chunks (prevents multi-GB accidents).")
		.defaultValue(32)
		.min(4)
		.max(128)
		.build()
	);

	private final Setting<Integer> mapHomeRadius = sgGeneral.add(new IntSetting.Builder()
		.name("map-home-radius")
		.description("Radius (chunks) used when setting a home from the Xaero map / waypoint chips.")
		.defaultValue(32)
		.min(1)
		.sliderRange(1, 128)
		.max(128)
		.build()
	);

	private final Setting<Integer> snapshotRadius = sgGeneral.add(new IntSetting.Builder()
		.name("snapshot-radius")
		.description("Default radius (chunks) for a new snapshot. Same range as map-home-radius; create screen can still edit per snapshot.")
		.defaultValue(32)
		.min(1)
		.sliderRange(1, 128)
		.max(128)
		.build()
	);

	private final Setting<Integer> storageAlertGb = sgGeneral.add(new IntSetting.Builder()
		.name("storage-alert-gb")
		.description("Soft alert when total Time Machine store size exceeds this many GB (does not block commits).")
		.defaultValue(50)
		.min(5)
		.max(500)
		.build()
	);

	/** Internal free-space floor — not a user setting. */
	private static final long INTERNAL_DISK_RESERVE_BYTES = 1024L * 1024L * 1024L;

	public enum CommitInterval {
		MIN_60(60, "60 min"),
		H2(120, "2 h"),
		H3(180, "3 h"),
		H4(240, "4 h"),
		H6(360, "6 h"),
		H8(480, "8 h"),
		H12(720, "12 h");

		public final int minutes;
		private final String label;

		CommitInterval(int minutes, String label) {
			this.minutes = minutes;
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private final Setting<CommitInterval> commitInterval = sgCommit.add(new EnumSetting.Builder<CommitInterval>()
		.name("commit-interval")
		.description("Auto-commit interval while inside an armed home (longer = less disk).")
		.defaultValue(CommitInterval.H2)
		.build()
	);

	private final Setting<Integer> minCommitGapMin = sgCommit.add(new IntSetting.Builder()
		.name("min-commit-gap-min")
		.description("Skip automatic commits (interval / leave / disconnect) if a commit happened within this many minutes. Manual Commit always runs.")
		.defaultValue(60)
		.min(0)
		.max(720)
		.build()
	);

	private final Setting<Integer> minDirtyChunks = sgCommit.add(new IntSetting.Builder()
		.name("min-dirty-chunks")
		.description("Skip interval commit unless at least this many chunks changed.")
		.defaultValue(1)
		.min(0)
		.max(64)
		.build()
	);

	private final Setting<Boolean> commitOnLeave = sgCommit.add(new BoolSetting.Builder()
		.name("commit-on-leave")
		.description("Commit when leaving a home geofence.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> commitOnDisconnect = sgCommit.add(new BoolSetting.Builder()
		.name("commit-on-disconnect")
		.description("Flush dirty homes in the background on disconnect (never freezes the client).")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> settleDelayTicks = sgCommit.add(new IntSetting.Builder()
		.name("settle-delay-ticks")
		.description("Wait this many ticks after chunk load before capture (block entities arrive late).")
		.defaultValue(40)
		.min(0)
		.max(200)
		.build()
	);

	/** Master auto-playable (migrated from keep-playable-saves). One stable SP world per opted-in home. */
	private final Setting<Boolean> autoPlayableLatest = sgCommit.add(new BoolSetting.Builder()
		.name("auto-playable-latest")
		.description("When on, homes with Playable checked keep one Singleplayer world in saves/ (latest overwrite). Refreshed on disconnect + successful commit.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> drawBoundary = sgRender.add(new BoolSetting.Builder()
		.name("draw-boundary")
		.description("Show visual home forcefield walls near the edge (no collision). Center is the linked Xaero waypoint.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> borderApproachChunks = sgRender.add(new IntSetting.Builder()
		.name("border-approach-chunks")
		.description("Show forcefield walls when within this many chunks of the home edge (either side).")
		.defaultValue(2)
		.min(1)
		.max(64)
		.build()
	);

	public enum UnnaturalScope {
		SNAPSHOTS_ONLY,
		HOMES_AND_SNAPSHOTS,
		HOMES_ONLY,
		OFF
	}

	private final Setting<UnnaturalScope> unnaturalOverlay = sgRender.add(new EnumSetting.Builder<UnnaturalScope>()
		.name("unnatural-overlay")
		.description("Map hover only — never in-world. Which homes get unnatural chunk tints when hovered.")
		.defaultValue(UnnaturalScope.SNAPSHOTS_ONLY)
		.build()
	);

	private final Setting<SettingColor> unnaturalColor = sgRender.add(new ColorSetting.Builder()
		.name("unnatural-color")
		.description("Unnatural chunk highlight color (godseye default orange).")
		.defaultValue(new SettingColor(255, 69, 0, 136))
		.build()
	);

	private final Setting<Double> boundaryOpacity = sgRender.add(new DoubleSetting.Builder()
		.name("boundary-opacity")
		.description("Home forcefield wall opacity (0-1). Color is fixed stationary world-border blue.")
		.defaultValue(0.55)
		.min(0.05)
		.max(1.0)
		.build()
	);

	private final Setting<Double> mapCircleOpacity = sgRender.add(new DoubleSetting.Builder()
		.name("map-circle-opacity")
		.description("Xaero map home outline opacity (0-1).")
		.defaultValue(0.45)
		.min(0.05)
		.max(1.0)
		.build()
	);

	private final Setting<Boolean> shiftCoordsTo0 = sgEmbed.add(new BoolSetting.Builder()
		.name("shift-coords-to-0")
		.description("After yaw scramble, move home center chunk to (0,0) / spawn ~8,y,8. Applies to default and embedded exports.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Integer> blendRadiusChunks = sgEmbed.add(new IntSetting.Builder()
		.name("blend-radius-chunks")
		.description("Embedded export only: height-ring blend width outside the geofence (chunks).")
		.defaultValue(8)
		.min(4)
		.max(16)
		.build()
	);

	private final Setting<Integer> seedSearchBudgetMs = sgEmbed.add(new IntSetting.Builder()
		.name("seed-search-budget-ms")
		.description("Embedded export only: max milliseconds to search for a matching seed biome.")
		.defaultValue(3000)
		.min(500)
		.max(15000)
		.build()
	);

	private final Setting<Boolean> scrubEnabled = sgTrust.add(new BoolSetting.Builder()
		.name("scrub-enabled")
		.description("Background blob verification (finds corruption within hours).")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> rehearsalEveryN = sgTrust.add(new IntSetting.Builder()
		.name("rehearsal-every-n-commits")
		.description("Run export rehearsal every N commits (core trust feature).")
		.defaultValue(20)
		.min(1)
		.max(200)
		.build()
	);

	private final Setting<Boolean> retainMonthlyArchive = sgTrust.add(new BoolSetting.Builder()
		.name("retain-monthly-archive")
		.description("Keep monthly rehearsal exports as independent archives.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> sealedKeyframes = sgTrust.add(new BoolSetting.Builder()
		.name("sealed-keyframes")
		.description("Monthly self-contained snapshots (no blob sharing).")
		.defaultValue(true)
		.build()
	);

	private HomeStore homeStore;
	private BackupEngine engine;
	private Scrubber scrubber;
	private ExportRehearsal rehearsal;
	private final ContainerCapture containers = new ContainerCapture();
	private final Set<String> insideHomes = new HashSet<>();
	private final Set<String> snapshotFinalizing = new HashSet<>();
	private final Map<String, Long> lastSkipNoticeAt = new HashMap<>();
	private final Map<String, Long> lastSnapshotWaitChatAt = new HashMap<>();
	private long lastIntervalCommitAt;
	private boolean xaeroNoteShown;
	private String activeHomeId;
	private boolean flushingDisconnect;
	private long lastCaptureProgressChatAt;
	private boolean storageAlertShownThisSession;
	private final AtomicBoolean verifyInFlight = new AtomicBoolean(false);

	private static final ExecutorService VERIFY_WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "greencorps-verify");
		t.setDaemon(true);
		return t;
	});

	public BaseBackup() {
		super(GreenCorps.CATEGORY, "time-machine",
			"Geofenced home backups with auto-commit interval, timeline export, "
				+ "playable Singleplayer worlds, and optional Xaero map homes. "
				+ "Left click or the module keybind opens Manage Homes & Backups. Right click opens settings.");
	}

	/**
	 * Left click / keybind: turn on if off, otherwise open Manage Homes & Backups.
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
		openManageScreen();
	}

	private boolean isSettingsToggle() {
		if (mc == null || !(mc.currentScreen instanceof ModuleScreen screen)) {
			return false;
		}
		return ((ModuleScreenAccessor) (Object) screen).greencorps$module() == this;
	}

	public static BaseBackup get() {
		return Modules.get().get(BaseBackup.class);
	}

	/** Open HomeStore + engine without requiring the module toggle (UI / Add / Manage). */
	public synchronized boolean ensureStore() {
		if (homeStore != null && engine != null) {
			engine.setDiskReserveBytes(INTERNAL_DISK_RESERVE_BYTES);
			engine.setSettleDelayTicks(settleDelayTicks.get());
			maybeStorageAlert();
			return true;
		}
		try {
			Path base = HomeStore.defaultBaseDir();
			if (homeStore == null) homeStore = new HomeStore(base);
			if (engine == null) {
				engine = new BackupEngine(base);
				engine.setDiskReserveBytes(INTERNAL_DISK_RESERVE_BYTES);
				engine.setSettleDelayTicks(settleDelayTicks.get());
			}
			maybeStorageAlert();
			return true;
		} catch (IOException e) {
			LOG.warn("ensureStore failed: {}", e.toString());
			HomeChat.warn("Failed to open store: " + e.getClass().getSimpleName());
			return false;
		}
	}

	@Override
	public void onActivate() {
		if (!ensureStore()) {
			toggle();
			return;
		}
		try {
			scrubber = new Scrubber(engine);
		} catch (IOException e) {
			LOG.warn("scrubber init: {}", e.toString());
			scrubber = null;
		}
		rehearsal = new ExportRehearsal(engine, rehearsalEveryN.get(),
			7L * 24 * 60 * 60 * 1000, retainMonthlyArchive.get());
		if (homeStore != null && homeStore.homes().isEmpty()) {
			engine.clearRehearsalStatus();
			rehearsal.clearFailReason();
		}
		if (!engine.ensureCommitLock() && engine.consumeLockWarn()) {
			HomeChat.warn("Store locked by another client on this instance — close the other client to commit. Browse/export still work.");
		}
		if (!xaeroNoteShown) {
			HomeChat.info(XaeroBridge.statusLine());
			xaeroNoteShown = true;
		}
	}

	@Override
	public void onDeactivate() {
		scrubber = null;
		rehearsal = null;
		insideHomes.clear();
		if (engine == null || homeStore == null) return;

		Runnable afterFlush = () -> {
			flushingDisconnect = false;
			publishPlayableOnDisconnect();
		};

		if (commitOnDisconnect.get() && !flushingDisconnect) {
			List<Home> toFlush = new ArrayList<>();
			for (Home h : homeStore.homes()) {
				if (h.isSnapshot()) continue; // one-shot snapshots finalize on full load only
				if (!h.armed || !h.autoCommit || engine.dirtyCount(h.id) <= 0) continue;
				if (shouldSkipAutoCommit(h)) {
					notifyAutoCommitSkip(h);
					continue;
				}
				toFlush.add(h);
			}
			if (!toFlush.isEmpty()) {
				flushingDisconnect = true;
				HomeChat.info("Flushing home backup in background…");
				engine.flushDirtyAsync(toFlush, this::onCaptureProgress, this::onCommitResult, afterFlush);
				return;
			}
		}
		afterFlush.run();
		// Do not sync-commit or close on the render thread — store stays open for UI.
	}

	private void publishPlayableOnDisconnect() {
		if (!autoPlayableLatest.get() || engine == null || homeStore == null || mc == null) return;
		Path saves = PlayableSavePublisher.savesRoot(mc);
		PlayableSavePublisher.publishEligibleAsync(engine, homeStore.homes(), saves,
			this::shouldPublishPlayable, shiftCoordsTo0.get());
	}

	@Override
	public WWidget getWidget(GuiTheme theme) {
		ensureStore();
		return BaseBackupPanel.build(theme, this);
	}

	@EventHandler
	private void onChunkData(ChunkDataEvent event) {
		if (!isActive() || engine == null || homeStore == null || mc.world == null) return;
		ChunkPos pos = event.chunk().getPos();
		int tick = mc.player != null ? mc.player.age : 0;
		for (Home h : homeStore.homes()) {
			if (!h.armed) continue;
			String dim = mc.world.getRegistryKey().getValue().toString();
			if (!dim.equals(h.dimension)) continue;
			engine.markDirty(h, pos.x, pos.z, tick);
		}
	}

	@EventHandler
	private void onPacket(PacketEvent.Receive event) {
		if (!isActive() || engine == null || homeStore == null || mc.world == null) return;
		int tick = mc.player != null ? mc.player.age : 0;
		if (event.packet instanceof BlockUpdateS2CPacket p) {
			BlockPos bp = p.getPos();
			markAll(bp.getX() >> 4, bp.getZ() >> 4, tick);
		} else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) {
			p.visitUpdates((pos, state) -> markAll(pos.getX() >> 4, pos.getZ() >> 4, tick));
		} else if (event.packet instanceof EntitySpawnS2CPacket p) {
			// Item frames, paintings, armour stands are saved with the chunk; a new one changes it.
			if (ChunkCapture.isDecoration(p.getEntityType())) markAll((int) Math.floor(p.getX()) >> 4, (int) Math.floor(p.getZ()) >> 4, tick);
		} else if (event.packet instanceof EntityTrackerUpdateS2CPacket p) {
			markDecoration(mc.world.getEntityById(p.id()), tick); // a map put into / taken out of a frame, rotation
		} else if (event.packet instanceof EntitiesDestroyS2CPacket p) {
			for (int id : p.getEntityIds()) markDecoration(mc.world.getEntityById(id), tick);
		}
	}

	private void markDecoration(Entity e, int tick) {
		if (e != null && ChunkCapture.isDecoration(e)) markAll(e.getBlockX() >> 4, e.getBlockZ() >> 4, tick);
	}

	private void markAll(int cx, int cz, int tick) {
		for (Home h : homeStore.homes()) {
			if (!h.armed) continue;
			engine.markDirty(h, cx, cz, tick);
		}
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		// Drain spread capture even while flushing after deactivate (Meteor may still deliver
		// a tick or two); GreenCorps also drains via ClientTickEvents when inactive.
		if (engine != null) {
			engine.tickCapture(BackupEngine.CAPTURE_BUDGET_MS);
		}

		if (!isActive() || engine == null || homeStore == null || mc.player == null || mc.world == null) return;

		String dim = mc.world.getRegistryKey().getValue().toString();
		double x = mc.player.getX();
		double z = mc.player.getZ();
		Set<String> nowInside = new HashSet<>();

		boolean membershipChanged = false;
		for (Home h : homeStore.homes()) {
			if (!dim.equals(h.dimension)) continue;
			boolean inside = h.containsBlock(x, z);
			if (inside) nowInside.add(h.id);
			boolean was = insideHomes.contains(h.id);
			if (inside && !was) {
				membershipChanged = true;
				activeHomeId = h.id;
				double cov = coverage(h);
				int snaps = snapshotCount(h);
				HomeChat.enter(h, chatVerbosity.get(), cov, snaps, engine.lastCommitAt(h.id), engine.health());
			} else if (!inside && was) {
				membershipChanged = true;
				HomeChat.leave(h, chatVerbosity.get());
				if (commitOnLeave.get() && h.autoCommit && !h.isSnapshot()) doCommit(h, false);
			}
		}
		if (!nowInside.equals(insideHomes)) membershipChanged = true;
		insideHomes.clear();
		insideHomes.addAll(nowInside);
		if (membershipChanged) {
			panelReloadSafe();
		}

		// Snapshots: wait until every chunk in the radius is client-loaded, then commit+export once.
		for (Home h : homeStore.homes()) {
			if (!h.isSnapshot() || !h.armed) continue;
			if (!dim.equals(h.dimension)) continue;
			tryFinalizeSnapshot(h);
		}

		long intervalMs = commitInterval.get().minutes * 60_000L;
		if (System.currentTimeMillis() - lastIntervalCommitAt >= intervalMs) {
			lastIntervalCommitAt = System.currentTimeMillis();
			for (Home h : homeStore.homes()) {
				if (h.isSnapshot()) continue;
				if (!h.armed || !h.autoCommit) continue;
				if (!insideHomes.contains(h.id)) continue;
				if (engine.dirtyCount(h.id) < minDirtyChunks.get()) continue;
				doCommit(h, false);
			}
		}

		if (scrubEnabled.get() && scrubber != null) {
			int found = scrubber.tick();
			if (found > 0) {
				HomeChat.health(engine.health(), engine.degradedSections(),
					engine.lastRehearsalPass(), engine.lastRehearsalAt());
			}
		}

		if (mc.currentScreen instanceof HandledScreen<?> && containers.lastInteracted() != null) {
			containers.onScreenOpen();
		}
	}

	/** Called from GreenCorps client tick so capture continues after module deactivate. */
	public void drainCaptureTick() {
		if (engine != null && engine.hasActiveCapture()) {
			engine.tickCapture(BackupEngine.CAPTURE_BUDGET_MS);
		}
	}


	/** Keybind: commit the home the player is inside. */
	private void manualCommitFromKeybind() {
		if (!isActive()) {
			HomeChat.warn("Activate Time Machine to capture/commit.");
			return;
		}
		Home target = null;
		if (activeHomeId != null && insideHomes.contains(activeHomeId) && homeStore != null) {
			target = homeStore.findById(activeHomeId).orElse(null);
		}
		if (target == null && homeStore != null) {
			for (String id : insideHomes) {
				target = homeStore.findById(id).orElse(null);
				if (target != null) break;
			}
		}
		if (target == null) {
			HomeChat.warn("Manual commit: not inside a home.");
			return;
		}
		doCommit(target, true);
	}

	/** Manual commit (panel / chat / snapshot) — always runs. */
	public void doCommit(Home home) {
		doCommit(home, true);
	}

	/**
	 * @param manual true = user-forced (ignores min-commit-gap); false = auto (interval/leave/disconnect)
	 */
	public void doCommit(Home home, boolean manual) {
		if (engine == null) {
			HomeChat.warn("Store not open — activate Time Machine or open the Homes panel.");
			return;
		}
		if (!isActive()) {
			HomeChat.warn("Activate Time Machine to capture/commit.");
			return;
		}
		if (!manual && shouldSkipAutoCommit(home)) {
			notifyAutoCommitSkip(home);
			return;
		}
		CommitRiskScanner.Report risk = CommitRiskScanner.scan(engine, home);
		if (risk.risky()) {
			if (!manual && CommitRiskConfirm.busyForDialog()) {
				HomeChat.warn("Severe change at " + home.name
					+ " — auto-commit deferred (open Time Machine / stand still to confirm).");
				return;
			}
			if (CommitRiskConfirm.busyForDialog() && manual) {
				HomeChat.warn("Severe change detected — close other screens to confirm commit.");
				return;
			}
			CommitRiskConfirm.prompt(this, home, risk, manual);
			return;
		}
		engine.commitAsync(home, this::onCaptureProgress, this::onCommitResult);
	}

	/** Called after Accept on a severe-change confirm. */
	public void sealCommitAfterRiskConfirm(Home home, boolean manual) {
		if (engine == null || home == null) return;
		if (!isActive()) {
			HomeChat.warn("Activate Time Machine to capture/commit.");
			return;
		}
		if (!manual && shouldSkipAutoCommit(home)) {
			notifyAutoCommitSkip(home);
			return;
		}
		engine.commitAsync(home, this::onCaptureProgress, this::onCommitResult);
	}

	/** Reject severe-change confirm: pause auto-backups for this home. */
	public void pauseAutoCommitAfterRiskReject(Home home) throws IOException {
		if (home == null) return;
		home.autoCommit = false;
		if (homeStore != null) homeStore.update(home);
		HomeChat.warn("Auto-backups paused for " + home.name
			+ " — re-enable Auto-commit in Manage Homes when ready.");
		panelReloadSafe();
	}

	private boolean shouldSkipAutoCommit(Home home) {
		if (home == null || engine == null) return false;
		int gapMin = minCommitGapMin.get();
		if (gapMin <= 0) return false;
		long last = engine.lastCommitAt(home.id);
		if (last <= 0) return false;
		return System.currentTimeMillis() - last < gapMin * 60_000L;
	}

	private void notifyAutoCommitSkip(Home home) {
		if (home == null || engine == null) return;
		if (chatVerbosity.get() == HomeChat.Verbosity.OFF) return;
		long now = System.currentTimeMillis();
		Long prev = lastSkipNoticeAt.get(home.id);
		long gapMs = Math.max(1, minCommitGapMin.get()) * 60_000L;
		if (prev != null && now - prev < gapMs) return;
		lastSkipNoticeAt.put(home.id, now);
		long last = engine.lastCommitAt(home.id);
		long agoMin = last <= 0 ? 0 : Math.max(0, (now - last) / 60_000L);
		HomeChat.info("Skipped auto-commit for " + home.name
			+ " (last commit " + agoMin + "m ago, gap " + minCommitGapMin.get()
			+ "m) — commit manually to force.");
	}

	private void onCaptureProgress(BackupEngine.CaptureProgress p) {
		if (p == null || p.home() == null) return;
		long now = System.currentTimeMillis();
		if (!p.starting() && p.done() < p.total() && now - lastCaptureProgressChatAt < 1000L) return;
		lastCaptureProgressChatAt = now;
		HomeChat.updating(p.home().name, p.done(), p.total(), p.etaSeconds(), p.starting());
	}

	private void onCommitResult(BackupEngine.CommitResult r) {
		if (r == null) return;
		if (r.ok()) {
			Home home = homeStore != null
				? homeStore.findById(r.manifest().homeId).orElse(null)
				: null;
			if (home == null) {
				home = new Home();
				home.id = r.manifest().homeId;
				home.name = r.manifest().homeId;
			}
			HomeChat.commit(home, r.manifest(), engine.storeSizeNow());
			if (rehearsal != null) {
				boolean wasPass = engine.lastRehearsalPass();
				long wasAt = engine.lastRehearsalAt();
				rehearsal.onCommit(home);
				// Only chat when a rehearsal actually ran and failed.
				if (!engine.lastRehearsalPass()
					&& (engine.lastRehearsalAt() != wasAt || wasPass)) {
					String reason = rehearsal.lastFailReason();
					HomeChat.warn("Export rehearsal FAIL: "
						+ (reason == null || reason.isBlank() ? "check store health" : reason));
				}
			}
			if (sealedKeyframes.get()) maybeSeal(home, r.manifest());
			if (shouldPublishPlayable(home) && mc != null) {
				PlayableSavePublisher.publishLatestAsync(engine, home,
					PlayableSavePublisher.savesRoot(mc), shiftCoordsTo0.get());
			}
		} else if ("store locked".equals(r.error())) {
			if (engine != null && engine.consumeLockWarn()) {
				HomeChat.warn("Store locked by another client on this instance — close the other client to commit.");
			}
		} else {
			HomeChat.warn("Commit failed: " + r.error());
		}
	}

	private void maybeSeal(Home home, SnapshotManifest m) {
		try {
			SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), home.id);
			boolean recentSeal = idx.entries().stream()
				.anyMatch(e -> e.sealed && System.currentTimeMillis() - e.epochMs < Retention.D * 30);
			if (!recentSeal && m.changedChunks > 0) {
				m.sealed = true;
				Path p = engine.manifestPath(home.id, m.epochMs + ".gcsnap");
				m.writeAtomic(p);
				idx.latest().ifPresent(e -> {
					e.sealed = true;
					try { idx.update(e); } catch (IOException ignored) {}
				});
			}
		} catch (IOException ignored) {}
	}

	public Home addHomeHere(String name, int radiusChunks) throws IOException {
		if (mc.player == null || mc.world == null) throw new IOException("no player");
		return addHomeAt(
			mc.player.getBlockX(),
			mc.player.getBlockZ(),
			mc.world.getRegistryKey().getValue().toString(),
			radiusChunks,
			name
		);
	}

	/** Create an armed home centered on block coords (used by Xaero map right-click). */
	public Home addHomeAt(int blockX, int blockZ, String dimension, int radiusChunks) throws IOException {
		String name = "home-" + (blockX >> 4) + "-" + (blockZ >> 4);
		return addHomeAt(blockX, blockZ, dimension, radiusChunks, name);
	}

	public Home addHomeAt(int blockX, int blockZ, String dimension, int radiusChunks, String name)
		throws IOException {
		if (!ensureStore()) throw new IOException("store not open");
		int r = Math.min(Math.max(1, radiusChunks), maxRadiusChunks.get());
		Home h = new Home(name, dimension, blockX >> 4, blockZ >> 4, r);
		h.armed = true;
		h.autoCommit = true;
		if (mc != null && mc.player != null) {
			h.spawnY = mc.player.getBlockY();
		}
		homeStore.add(h);
		return h;
	}

	/**
	 * Create a home from a Xaero waypoint (map menu or module chip).
	 * {@code spawnY} null → player Y if available, else 64.
	 */
	public Home addHomeFromWaypoint(String name, int blockX, Integer spawnY, int blockZ,
									String dimension, int radiusChunks) throws IOException {
		String n = (name == null || name.isBlank()) ? "wp-" + (blockX >> 4) + "-" + (blockZ >> 4) : name.trim();
		Home h = addHomeAt(blockX, blockZ, dimension, radiusChunks, n);
		if (spawnY != null) {
			h.spawnY = spawnY;
			homeStore.update(h);
		} else if (h.spawnY <= 0) {
			h.spawnY = 64;
			homeStore.update(h);
		}
		return h;
	}

	public Home addSnapshotHere(String name, int radiusChunks) throws IOException {
		Home h = addHomeHere(name, radiusChunks);
		h.kind = Home.Kind.SNAPSHOT;
		h.autoCommit = false; // not a home backup — finalize on full load only
		homeStore.update(h);
		pinSnapshotWaypoint(h);
		int loaded = countLoadedChunks(h);
		HomeChat.info("Snapshot " + h.name + " waiting for chunks (" + loaded + "/"
			+ h.expectedChunks() + ")");
		return h;
	}

	/** One-shot snapshot centered on map coords (not necessarily the player). */
	public Home addSnapshotAt(int blockX, int blockZ, String dimension, String name, int radiusChunks)
		throws IOException {
		Home h = addHomeAt(blockX, blockZ, dimension, radiusChunks, name);
		h.kind = Home.Kind.SNAPSHOT;
		h.autoCommit = false;
		homeStore.update(h);
		pinSnapshotWaypoint(h);
		int loaded = countLoadedChunks(h);
		HomeChat.info("Snapshot " + h.name + " waiting for chunks (" + loaded + "/"
			+ h.expectedChunks() + ")");
		return h;
	}

	/** Client-loaded chunks currently present inside the home square. */
	public int countLoadedChunks(Home h) {
		if (h == null || mc == null) return 0;
		ClientWorld world = mc.world;
		if (world == null) return 0;
		if (h.dimension != null) {
			String dim = world.getRegistryKey().getValue().toString();
			if (!h.dimension.equals(dim)) return 0;
		}
		int n = 0;
		for (int dx = -h.radiusChunks; dx <= h.radiusChunks; dx++) {
			for (int dz = -h.radiusChunks; dz <= h.radiusChunks; dz++) {
				if (world.getChunkManager().getWorldChunk(h.centerChunkX + dx, h.centerChunkZ + dz) != null) {
					n++;
				}
			}
		}
		return n;
	}

	public boolean isSnapshotAreaFullyLoaded(Home h) {
		return h != null && countLoadedChunks(h) >= h.expectedChunks();
	}

	/**
	 * When every chunk in a SNAPSHOT radius is client-loaded, mark dirty, commit once,
	 * auto-export, then disarm. Ignores home commit gates / min-gap.
	 */
	private void tryFinalizeSnapshot(Home h) {
		if (h == null || !h.isSnapshot() || !h.armed || engine == null) return;
		if (snapshotFinalizing.contains(h.id)) return;
		if (!isSnapshotAreaFullyLoaded(h)) {
			maybeChatSnapshotWait(h);
			return;
		}
		int marked = engine.markAllLoadedDirty(h);
		if (marked < h.expectedChunks()) {
			maybeChatSnapshotWait(h);
			return;
		}
		snapshotFinalizing.add(h.id);
		HomeChat.info("Snapshot " + h.name + " area full (" + marked + "/" + h.expectedChunks()
			+ ") — capturing…");
		engine.commitAsync(h, this::onCaptureProgress, r -> onSnapshotFinalizeResult(h, r));
	}

	private void maybeChatSnapshotWait(Home h) {
		if (chatVerbosity.get() == HomeChat.Verbosity.OFF) return;
		long now = System.currentTimeMillis();
		Long prev = lastSnapshotWaitChatAt.get(h.id);
		if (prev != null && now - prev < 8000L) return;
		lastSnapshotWaitChatAt.put(h.id, now);
		HomeChat.info("Snapshot " + h.name + " waiting for chunks (" + countLoadedChunks(h)
			+ "/" + h.expectedChunks() + ")");
	}

	private void onSnapshotFinalizeResult(Home h, BackupEngine.CommitResult r) {
		snapshotFinalizing.remove(h.id);
		if (h == null) return;
		if (r == null || !r.ok()) {
			HomeChat.warn("Snapshot " + h.name + " finalize failed: "
				+ (r == null || r.error() == null ? "unknown" : r.error()));
			return;
		}
		HomeChat.commit(h, r.manifest(), engine != null ? engine.storeSizeNow() : 0);
		try {
			Path path = exportHome(h, "latest", null);
			HomeChat.info("Snapshot " + h.name + " complete — exported "
				+ r.manifest().capturedChunks + "/" + r.manifest().expectedChunks
				+ " → " + path.getFileName());
		} catch (Exception e) {
			HomeChat.warn("Snapshot " + h.name + " export failed: " + e);
		}
		h.armed = false;
		try {
			if (homeStore != null) homeStore.update(h);
		} catch (Exception e) {
			HomeChat.warn("Failed to disarm snapshot: " + e);
		}
		lastSnapshotWaitChatAt.remove(h.id);
		panelReloadSafe();
	}

	private void panelReloadSafe() {
		try {
			dev.greencorps.gui.PanelReload.reloadModuleScreen();
		} catch (Throwable ignored) {}
	}

	/** Soft-dep Xaero pin at snapshot center (each snapshot gets its own pin). */
	private void pinSnapshotWaypoint(Home home) {
		if (home == null) return;
		int y = home.spawnY > 0 ? home.spawnY
			: (mc != null && mc.player != null ? mc.player.getBlockY() : 64);
		BlockPos pos = new BlockPos((int) home.centerBlockX(), y, (int) home.centerBlockZ());
		BlockPos written = XaeroWaypoints.upsertBaseBackupSnapshot(null, pos, home.name);
		if (written != null && chatVerbosity.get() != HomeChat.Verbosity.OFF) {
			HomeChat.info("Xaero waypoint " + XaeroWaypoints.baseBackupSnapshotName(home.name));
		}
	}

	/** Enable per-home playable flag + master auto-playable-latest. */
	public void enablePlayableForHome(Home home) throws IOException {
		if (home == null) return;
		autoPlayableLatest.set(true);
		home.keepPlayableSave = true;
		if (homeStore != null) homeStore.update(home);
	}

	public void openManageScreen() {
		if (mc == null) return;
		if (meteordevelopment.meteorclient.gui.GuiThemes.get() == null) return;
		ensureStore();
		if (mc.currentScreen instanceof BaseBackupScreen) return;
		BaseBackupScreen screen = new BaseBackupScreen(this);
		screen.parent = mc.currentScreen;
		mc.setScreen(screen);
	}

	public void mcSetScreenHome(String homeId) {
		if (mc == null) return;
		if (meteordevelopment.meteorclient.gui.GuiThemes.get() == null) return;
		ensureStore();
		BaseBackupScreen screen = new BaseBackupScreen(this, homeId);
		screen.parent = mc.currentScreen;
		mc.setScreen(screen);
	}

	public int mapHomeRadiusChunks() {
		return Math.min(mapHomeRadius.get(), maxRadiusChunks.get());
	}

	public int snapshotRadiusChunks() {
		return Math.min(Math.max(1, snapshotRadius.get()), maxRadiusChunks.get());
	}

	public Path exportHome(Home home, String when, Path dest) throws IOException {
		if (!ensureStore()) throw new IOException("store not open");
		SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), home.id);
		var entry = dev.greencorps.commands.TimeSelector.resolve(idx, when)
			.orElseThrow(() -> new IOException("no matching snapshot"));
		boolean latest = idx.latest().map(e -> e.epochMs == entry.epochMs && e.filename.equals(entry.filename))
			.orElse(false);
		boolean shift = shiftCoordsTo0.get();
		Path savesRoot = PlayableSavePublisher.savesRoot(mc);
		// Auto-playable: overwrite the one stable folder when exporting latest + opted in.
		if (dest == null && shouldPublishPlayable(home) && latest) {
			String folder = PlayableSavePublisher.autoFolderName(home);
			HomeChat.info("Updating playable save " + folder + " in background…");
			PlayableSavePublisher.publishLatestAsync(engine, home, savesRoot, shift);
			return savesRoot.resolve(folder);
		}
		// Manual Time Machine archive: new named world (does not accumulate auto folders).
		java.time.Instant whenInst = java.time.Instant.ofEpochMilli(entry.epochMs);
		String folder = PlayableSavePublisher.archiveFolderName(home, whenInst);
		String levelName = PlayableSavePublisher.archiveLevelName(home, whenInst);
		if (dest == null) {
			dest = savesRoot.resolve(folder);
		}
		if (dest.startsWith(savesRoot)) {
			final Path destFinal = dest;
			final SnapshotIndex.Entry entryFinal = entry;
			final String levelFinal = levelName;
			HomeChat.info("Export queued → " + folder);
			PlayableSavePublisher.submit(() -> {
				try {
					AnvilExporter exporter = new AnvilExporter(engine);
					AnvilExporter.ExportResult result = exporter.exportEntry(
						home.id, entryFinal, destFinal, levelFinal, home, shift, idx);
					try {
						dev.greencorps.export.SurfaceIconRenderer.writeIcon(destFinal, result.destChunks());
					} catch (Exception ignored) {}
					chatOnClient(() -> {
						if (result.legacySkipped() > 0) {
							HomeChat.info("Exported " + result.written() + "/" + result.total()
								+ " chunks (" + result.legacySkipped()
								+ " legacy skipped — revisit those areas and commit).");
						} else {
							HomeChat.info("Exported " + destFinal.getFileName());
						}
					});
				} catch (Exception e) {
					LOG.warn("export failed: {}", e.toString());
					chatOnClient(() -> HomeChat.warn("Export failed: " + e.getClass().getSimpleName()));
				}
			});
			return dest;
		}
		AnvilExporter exporter = new AnvilExporter(engine);
		AnvilExporter.ExportResult result = exporter.exportEntry(home.id, entry, dest, levelName, home, shift, idx);
		try {
			dev.greencorps.export.SurfaceIconRenderer.writeIcon(dest, result.destChunks());
		} catch (Exception ignored) {}
		if (result.legacySkipped() > 0) {
			HomeChat.info("Exported " + result.written() + "/" + result.total()
				+ " chunks (" + result.legacySkipped()
				+ " legacy skipped — revisit those areas and commit).");
		}
		return result.dest();
	}

	public boolean keepPlayableSaves() {
		return autoPlayableLatest.get();
	}

	/** Master switch + per-home flag both required. */
	public boolean shouldPublishPlayable(Home home) {
		return autoPlayableLatest.get() && home != null && home.keepPlayableSave;
	}

	public StoreGc.Result runGc(boolean dryRun) {
		return new StoreGc(engine, Retention.D * 7).run(dryRun);
	}

	public Fsck.Result runFsck() {
		return Fsck.run(engine);
	}

	public boolean verifyInFlight() {
		return verifyInFlight.get();
	}

	/**
	 * Fsck + export rehearsal off the UI thread with progress chat.
	 * No-ops if a verify is already running.
	 */
	public void runVerifyAsync(Home home) {
		if (home == null || engine == null) {
			HomeChat.warn("Verify: store not open.");
			return;
		}
		if (!verifyInFlight.compareAndSet(false, true)) {
			HomeChat.info("Verify: already running…");
			return;
		}
		final Home target = home;
		HomeChat.info("Verify: fsck…");
		VERIFY_WORKER.submit(() -> {
			try {
				Fsck.Result fsck = Fsck.run(engine);
				String fsckMsg = "Verify: fsck " + (fsck.ok() ? "OK" : fsck.errors().size() + " errors");
				chatOnClient(() -> HomeChat.info(fsckMsg));
				chatOnClient(() -> HomeChat.info("Verify: rehearsal…"));
				ExportRehearsal reh = new ExportRehearsal(engine, 1, 0, false);
				boolean pass = reh.run(target);
				chatOnClient(() -> {
					if (pass) HomeChat.info("Verify: PASS");
					else HomeChat.warn("Verify: FAIL — "
						+ (reh.lastFailReason() == null ? "unknown" : reh.lastFailReason()));
				});
			} catch (Exception e) {
				LOG.warn("verify failed: {}", e.toString());
				chatOnClient(() -> HomeChat.warn("Verify: error — " + e.getClass().getSimpleName()));
			} finally {
				verifyInFlight.set(false);
			}
		});
	}

	private static void chatOnClient(Runnable r) {
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc != null) mc.execute(r);
		else r.run();
	}

	public int storageAlertGb() {
		return storageAlertGb.get();
	}

	public long storageAlertBytes() {
		return storageAlertGb.get() * 1024L * 1024L * 1024L;
	}

	public boolean storageOverAlert() {
		if (engine == null) return false;
		return engine.estimateStoreSize() >= storageAlertBytes();
	}

	/** Session-throttled soft alert when store size exceeds the threshold. */
	public void maybeStorageAlert() {
		if (engine == null || storageAlertShownThisSession) return;
		if (!storageOverAlert()) return;
		storageAlertShownThisSession = true;
		HomeChat.warn("Storage ALERT: store "
			+ StoreStats.formatBytes(engine.estimateStoreSize())
			+ " exceeds " + storageAlertGb.get() + " GB threshold.");
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

	public double coverage(Home h) {
		if (engine == null) return 0;
		try {
			SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), h.id);
			return idx.latest().map(e -> e.coveragePct).orElse(0.0);
		} catch (IOException e) {
			return 0;
		}
	}

	public int snapshotCount(Home h) {
		if (engine == null) return 0;
		try {
			return new SnapshotIndex(engine.refsDir(), h.id).entries().size();
		} catch (IOException e) {
			return 0;
		}
	}

	public Optional<StoreStats.Summary> stats(Home h) {
		if (engine == null) return Optional.empty();
		try {
			return Optional.of(StoreStats.forHome(engine, h.id));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	// Accessors for GUI / render / map
	public HomeStore homeStore() { return homeStore; }
	public BackupEngine engine() { return engine; }
	public ContainerCapture containers() { return containers; }
	public SettingColor unnaturalColor() { return unnaturalColor.get(); }
	public double boundaryOpacity() { return boundaryOpacity.get(); }
	public boolean drawBoundary() { return drawBoundary.get(); }
	/** Whether unnatural map tints apply for this home under the current overlay scope. */
	public boolean drawUnnaturalFor(Home h) {
		if (h == null) return false;
		return switch (unnaturalOverlay.get()) {
			case OFF -> false;
			case SNAPSHOTS_ONLY -> h.isSnapshot();
			case HOMES_ONLY -> !h.isSnapshot();
			case HOMES_AND_SNAPSHOTS -> true;
		};
	}
	public int borderApproachChunks() { return borderApproachChunks.get(); }
	public double mapCircleOpacity() { return mapCircleOpacity.get(); }
	public boolean shiftCoordsTo0() { return shiftCoordsTo0.get(); }
	public void setShiftCoordsTo0(boolean v) { shiftCoordsTo0.set(v); }
	public int blendRadiusChunks() { return blendRadiusChunks.get(); }
	public void setBlendRadiusChunks(int v) { blendRadiusChunks.set(v); }
	public int seedSearchBudgetMs() { return seedSearchBudgetMs.get(); }
	public void setSeedSearchBudgetMs(int v) { seedSearchBudgetMs.set(v); }
	public int maxRadiusChunks() { return maxRadiusChunks.get(); }
	public String activeHomeId() { return activeHomeId; }
	public Set<String> insideHomes() { return insideHomes; }

	@Override
	public String getInfoString() {
		if (engine == null) return null;
		return engine.health().name() + " d" + engine.totalDirty();
	}
}
