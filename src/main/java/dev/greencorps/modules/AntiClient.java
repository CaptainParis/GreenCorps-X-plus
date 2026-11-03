package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.anticlient.Archetype;
import dev.greencorps.anticlient.CrystalView;
import dev.greencorps.anticlient.Damage;
import dev.greencorps.anticlient.DiveRecord;
import dev.greencorps.anticlient.FileSink;
import dev.greencorps.anticlient.HudSnapshot;
import dev.greencorps.anticlient.Inventory;
import dev.greencorps.anticlient.JsonLine;
import dev.greencorps.anticlient.Observation;
import dev.greencorps.anticlient.PlayerTrack;
import dev.greencorps.anticlient.Scorecard;
import dev.greencorps.anticlient.SelfView;
import dev.greencorps.anticlient.Sensor;
import dev.greencorps.anticlient.SessionStats;
import dev.greencorps.anticlient.ShadowPlan;
import dev.greencorps.anticlient.ShadowPlanner;
import dev.greencorps.anticlient.SurvivalConfig;
import dev.greencorps.anticlient.SurvivalInput;
import dev.greencorps.anticlient.SurvivalModel;
import dev.greencorps.anticlient.SurvivalVerdict;
import dev.greencorps.anticlient.Telemetry;
import dev.greencorps.anticlient.ThreatAssessment;
import dev.greencorps.anticlient.ThreatConfig;
import dev.greencorps.anticlient.ThreatLevel;
import dev.greencorps.anticlient.ThreatModel;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoTotem;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Anti-Client: reads mace dives and crystal bursts early, scores how hopeless a fight is,
 * and (optionally) logs out before an unrecoverable burst. This build is shadow-first:
 * counters (wind charge, string, snowball, pearl) are only planned and logged, never fired.
 * Everything it sees is measured and written to a local JSONL session file so the model
 * can be tuned from real fights. Positions in the file are relative to you.
 */
public class AntiClient extends Module {
	public enum SafetyMode { Off, Notify, DryLog, Log }

	private static final byte TOTEM_STATUS = 35;
	private static final int FLUSH_EVERY = 20;
	private static final int SAFETY_COOLDOWN = 100;
	private static final int REFILL_TIMEOUT = 100;
	private static final int PENDING_HIT_MAX_AGE = 6;

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgThreat = settings.createGroup("Threat");
	private final SettingGroup sgSafety = settings.createGroup("Safety");
	private final SettingGroup sgTelemetry = settings.createGroup("Telemetry");
	private final SettingGroup sgRender = settings.createGroup("Render");

	private final Setting<Double> trackRange = sgGeneral.add(new DoubleSetting.Builder()
		.name("track-range").description("Only players within this many blocks are tracked.")
		.defaultValue(48).min(8).sliderRange(16, 96).build());
	private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
		.name("ignore-friends").description("Never treat Meteor friends as threats.")
		.defaultValue(true).build());
	private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
		.name("chat-feedback").description("Client-side chat lines for commits, hits and safety events.")
		.defaultValue(true).build());
	private final Setting<Integer> pingLeadExtra = sgGeneral.add(new IntSetting.Builder()
		.name("ping-lead-extra").description("Extra ticks added to the p95 ping lead (jitter margin).")
		.defaultValue(0).min(0).sliderRange(0, 5).build());

	private final Setting<Double> minFall = sgThreat.add(new DoubleSetting.Builder()
		.name("min-fall").description("Blocks of fall before a mace dive counts as a smash threat.")
		.defaultValue(3.0).min(1.5).sliderRange(1.5, 10).build());
	private final Setting<Integer> commitWindow = sgThreat.add(new IntSetting.Builder()
		.name("commit-window").description("Commit a mace dive when the strike is this many ticks away or less (plus ping lead).")
		.defaultValue(14).min(4).sliderRange(4, 30).build());
	private final Setting<Integer> watchScore = sgThreat.add(new IntSetting.Builder()
		.name("watch-score").description("Score where a player becomes WATCH.")
		.defaultValue(30).min(1).sliderRange(10, 60).build());
	private final Setting<Integer> armedScore = sgThreat.add(new IntSetting.Builder()
		.name("armed-score").description("Score where a player becomes ARMED (and crystal commits start).")
		.defaultValue(55).min(1).sliderRange(30, 90).build());
	private final Setting<Integer> commitScore = sgThreat.add(new IntSetting.Builder()
		.name("commit-score").description("Score a mace dive needs to COMMIT.")
		.defaultValue(75).min(1).sliderRange(50, 100).build());
	private final Setting<Double> assumedArmor = sgThreat.add(new DoubleSetting.Builder()
		.name("assumed-armor").description("Your armor points for damage estimates.")
		.defaultValue(20).min(0).sliderRange(0, 20).build());
	private final Setting<Double> assumedToughness = sgThreat.add(new DoubleSetting.Builder()
		.name("assumed-toughness").description("Your armor toughness for damage estimates.")
		.defaultValue(12).min(0).sliderRange(0, 12).build());
	private final Setting<Double> assumedEpf = sgThreat.add(new DoubleSetting.Builder()
		.name("assumed-protection").description("Enchantment protection factor, 16 is full Protection IV.")
		.defaultValue(16).min(0).max(20).sliderRange(0, 20).build());
	private final Setting<Double> difficultyMul = sgThreat.add(new DoubleSetting.Builder()
		.name("difficulty-multiplier").description("Explosion scaling: 1.5 hard, 1.0 normal.")
		.defaultValue(1.5).min(0.5).sliderRange(0.5, 1.5).build());

	private final Setting<SafetyMode> safetyMode = sgSafety.add(new EnumSetting.Builder<SafetyMode>()
		.name("safety-mode").description("Off, Notify (warn only), DryLog (record a would-log), Log (disconnect).")
		.defaultValue(SafetyMode.Notify).build());
	private final Setting<Integer> neutralizeTicks = sgSafety.add(new IntSetting.Builder()
		.name("neutralize-ticks").description("How long you need to survive to break the fight (ticks, 20 = 1s).")
		.defaultValue(60).min(10).sliderRange(20, 200).build());
	private final Setting<Integer> okMargin = sgSafety.add(new IntSetting.Builder()
		.name("ok-margin").description("Spare ticks above the need before the verdict is OK instead of DEGRADED.")
		.defaultValue(40).min(0).sliderRange(0, 120).build());
	private final Setting<Integer> confirmTicks = sgSafety.add(new IntSetting.Builder()
		.name("confirm-ticks").description("Consecutive DOOMED ticks before acting (filters one-tick noise).")
		.defaultValue(3).min(1).sliderRange(1, 10).build());
	private final Setting<Integer> warmupTicks = sgSafety.add(new IntSetting.Builder()
		.name("warmup-ticks").description("No safety action for this long after enabling (ping and pop stats need data).")
		.defaultValue(100).min(0).sliderRange(0, 400).build());
	private final Setting<Integer> refillDefault = sgSafety.add(new IntSetting.Builder()
		.name("refill-default-ticks").description("Assumed pop-to-offhand refill time until 3 pops have been measured.")
		.defaultValue(8).min(1).sliderRange(1, 40).build());
	private final Setting<Integer> maceInterval = sgSafety.add(new IntSetting.Builder()
		.name("mace-interval").description("Assumed ticks between mace smashes.")
		.defaultValue(30).min(5).sliderRange(10, 80).build());
	private final Setting<Integer> crystalInterval = sgSafety.add(new IntSetting.Builder()
		.name("crystal-interval").description("Assumed ticks between crystal bursts.")
		.defaultValue(4).min(1).sliderRange(1, 20).build());
	private final Setting<Boolean> blockReconnect = sgSafety.add(new BoolSetting.Builder()
		.name("block-auto-reconnect").description("Turn Auto Reconnect off when Log mode disconnects you. Re-enable it yourself.")
		.defaultValue(true).build());

	private final Setting<Boolean> telemetryOn = sgTelemetry.add(new BoolSetting.Builder()
		.name("telemetry").description("Write the local JSONL session file (config/greencorps/anti-client).")
		.defaultValue(true).build());
	private final Setting<Integer> flightSeconds = sgTelemetry.add(new IntSetting.Builder()
		.name("flight-seconds").description("Seconds of per-tick rows kept in memory and dumped on commits, hits, pops and marks.")
		.defaultValue(6).min(2).sliderRange(2, 20).build());
	private final Setting<String> scenario = sgTelemetry.add(new StringSetting.Builder()
		.name("scenario").description("Test id stamped on every row, for example T3. Also: .ac scenario T3")
		.defaultValue("none").onChanged(v -> applyScenario(v)).build());
	private final Setting<Keybind> markKey = sgTelemetry.add(new KeybindSetting.Builder()
		.name("mark-key").description("Press to drop a marker in the log and save the last seconds. Use after anything that felt wrong.")
		.defaultValue(Keybind.none()).action(() -> mark("key")).build());

	private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
		.name("render-path").description("Draw the predicted path and landing of the top threat.")
		.defaultValue(true).build());

	private final ThreatConfig tc = new ThreatConfig();
	private final SurvivalConfig sc = new SurvivalConfig();
	private final SessionStats stats = new SessionStats();
	private final Map<Integer, PlayerTrack> tracks = new HashMap<>();
	private final Map<Integer, ThreatLevel> lastLevel = new HashMap<>();
	private final Map<Integer, DiveRecord> dives = new HashMap<>();
	private final Set<Integer> committed = new HashSet<>();
	private final Map<Integer, ThreatAssessment> assessments = new HashMap<>();
	private final List<PendingHit> pendingHits = new ArrayList<>();
	private final int[] lastHitTick = new int[Archetype.values().length];

	private Telemetry tel;
	private FileSink file;
	private Path summaryPath;
	private World lastWorld;
	private volatile int tick;
	private int startTick;
	private double poolBefore;
	private int popTick = -1;
	private int lastPopTick = -1000;
	private int lastExplosionTick = -1000;
	private double lastExplosionRaw;
	private int lastSafetyTick = -1000;
	private int doomedStreak;
	private SurvivalVerdict verdict;
	private SurvivalVerdict.Kind lastKind = SurvivalVerdict.Kind.OK;
	private ThreatAssessment top;
	private PlayerTrack topTrack;
	private ShadowPlan plan = ShadowPlan.NONE;
	private int lastDumpTick = -1000;
	private boolean deathLogged;

	private volatile HudSnapshot snapshot = HudSnapshot.OFF;

	private record PendingHit(int tick, String type, int causeId, double poolBefore) {}

	public AntiClient() {
		super(GreenCorps.CATEGORY, "anti-client",
			"Reads mace dives and crystal bursts early, scores how hopeless a fight is, and can log out first. Shadow-first: counters are planned and logged, not fired.");
	}

	public static AntiClient get() {
		return Modules.get().get(AntiClient.class);
	}

	public HudSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public String getInfoString() {
		HudSnapshot s = snapshot;
		if (!s.active()) return null;
		return s.level().name() + (s.survival() == SurvivalVerdict.Kind.OK ? "" : " " + s.survival().name());
	}

	// ----------------------------------------------------------- lifecycle --

	@Override
	public void onActivate() {
		stats.reset();
		resetWorldState();
		tick = 0;
		startTick = 0;
		deathLogged = false;
		openTelemetry();
		syncConfig();
		if (tel != null) {
			tel.emit(tel.event("session_start", 0)
				.str("version", "anti-client-1").str("mc", "1.21.4")
				.str("safety", safetyMode.get().name())
				.num("neutralize", neutralizeTicks.get()).num("min_fall", minFall.get())
				.num("watch", watchScore.get()).num("armed", armedScore.get()).num("commit", commitScore.get())
				.num("epf", assumedEpf.get()).num("armor", assumedArmor.get()));
			tel.flush();
		}
		if (chatFeedback.get()) {
			ChatUtils.info("Anti-Client on | safety=" + safetyMode.get() + " | scenario=" + scenario.get()
				+ (file != null ? " | log=" + file.path().getFileName() : " | log off"));
		}
	}

	@Override
	public void onDeactivate() {
		if (tel != null) {
			List<String> lines = stats.lines(tel.scenario());
			tel.emit(tel.event("session_end", tick).num("tp", stats.scorecard.tp()).num("fp", stats.scorecard.fp())
				.num("miss", stats.scorecard.miss()).num("ticks", stats.ticks).strs("summary", lines));
			writeSummaryFile(lines);
			tel.close();
			tel = null;
			file = null;
		}
		if (chatFeedback.get() && mc.player != null) {
			for (String l : stats.lines(scenario.get())) ChatUtils.info(l);
		}
		snapshot = HudSnapshot.OFF;
		tracks.clear();
	}

	private void openTelemetry() {
		tel = null;
		file = null;
		if (!telemetryOn.get()) return;
		try {
			Path dir = FabricLoader.getInstance().getGameDir().resolve("greencorps").resolve("anti-client");
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			file = new FileSink(dir.resolve("session-" + stamp + ".jsonl"));
			summaryPath = dir.resolve("session-" + stamp + "-summary.txt");
			tel = new Telemetry(file, flightSeconds.get() * 20);
			tel.scenario(scenario.get());
		} catch (IOException | RuntimeException e) {
			GreenCorps.LOG.warn("Anti-Client: telemetry disabled: {}", e.toString());
			tel = null;
			file = null;
		}
	}

	private void writeSummaryFile(List<String> lines) {
		if (summaryPath == null) return;
		try {
			Files.write(summaryPath, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			GreenCorps.LOG.warn("Anti-Client: summary not written: {}", e.toString());
		}
	}

	private void resetWorldState() {
		tracks.clear();
		lastLevel.clear();
		dives.clear();
		committed.clear();
		assessments.clear();
		pendingHits.clear();
		java.util.Arrays.fill(lastHitTick, -1000);
		popTick = -1;
		lastPopTick = -1000;
		doomedStreak = 0;
		verdict = null;
		top = null;
		topTrack = null;
		plan = ShadowPlan.NONE;
		lastKind = SurvivalVerdict.Kind.OK;
		poolBefore = 0;
	}

	private void applyScenario(String v) {
		if (tel != null) {
			tel.scenario(v);
			tel.emit(tel.event("scenario", tick).str("id", tel.scenario()));
		}
	}

	private void syncConfig() {
		tc.minFall = minFall.get();
		tc.commitWindowTicks = commitWindow.get();
		tc.watchScore = watchScore.get();
		tc.armedScore = armedScore.get();
		tc.commitScore = commitScore.get();
		tc.assumedArmor = assumedArmor.get();
		tc.assumedToughness = assumedToughness.get();
		tc.assumedEpf = assumedEpf.get();
		tc.difficultyMul = difficultyMul.get();
		sc.neutralizeTicks = neutralizeTicks.get();
		sc.okMarginTicks = okMargin.get();
		sc.defaultRefillTicks = refillDefault.get();
		sc.maceIntervalTicks = maceInterval.get();
		sc.crystalIntervalTicks = crystalInterval.get();
	}

	// ------------------------------------------------------- public actions --

	/** Drops a labelled marker and saves the last seconds of per-tick rows. */
	public void mark(String label) {
		if (tel == null) {
			if (chatFeedback.get()) ChatUtils.warning("Anti-Client: telemetry is off, marker not saved.");
			return;
		}
		tel.emit(tel.event("mark", tick).str("label", label));
		int rows = tel.dump("mark:" + label, tick);
		tel.flush();
		if (chatFeedback.get()) ChatUtils.info("Anti-Client: marker '" + label + "' saved (" + rows + " rows).");
	}

	public void setScenario(String id) {
		scenario.set(id);
	}

	public String scenarioId() {
		return tel != null ? tel.scenario() : scenario.get();
	}

	public void dumpNow() {
		if (tel == null) return;
		tel.dump("manual", tick);
		tel.flush();
	}

	public void resetStats() {
		stats.reset();
		committed.clear();
		if (tel != null) tel.emit(tel.event("stats_reset", tick));
	}

	public List<String> summaryLines() {
		return stats.lines(scenarioId());
	}

	public String logPath() {
		return file == null ? "(telemetry off)" : file.path().toString();
	}

	// ------------------------------------------------------------- ticking --

	@EventHandler
	private void onTick(TickEvent.Post event) {
		ClientPlayerEntity me = mc.player;
		if (me == null || mc.world == null) return;
		if (mc.world != lastWorld) {
			resetWorldState();
			lastWorld = mc.world;
			if (tel != null) tel.emit(tel.event("world_change", tick));
		}
		tick++;
		stats.ticks++;
		syncConfig();

		if (tick % 20 == 0) samplePing(me);

		int rtt = stats.ping.leadTicks(pingLeadExtra.get());
		double pool = me.getHealth() + me.getAbsorptionAmount();
		boolean offhandTotem = me.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING);
		int invTotems = Sensor.mainInventoryTotems(me);
		boolean autoTotem = Modules.get().isActive(AutoTotem.class);
		Inventory inv = Sensor.inventory(me);

		trackRefill(offhandTotem);
		updateTracks(me);
		evaluateThreats(me, rtt, inv);
		resolveHits(me, pool);
		for (Scorecard.Resolved r : stats.scorecard.tick(tick)) onFalsePositive(r);

		int refill = stats.totemRefillTicks.count() >= 3
			? (int) Math.round(stats.totemRefillTicks.percentile(50)) : sc.defaultRefillTicks;
		ThreatAssessment ta = top == null ? ThreatAssessment.idle(-1) : top;
		SurvivalInput in = new SurvivalInput(me.getHealth(), me.getAbsorptionAmount(), offhandTotem, invTotems,
			autoTotem, refill, rtt, ta.level(), ta.archetype(), ta.etaTicks(), ta.burstDamage());
		verdict = SurvivalModel.evaluate(in, sc);
		runSafety(me, ta, in);

		if (me.getHealth() <= 0 || me.isDead()) {
			if (!deathLogged) onDeath(pool);
		} else {
			deathLogged = false;
		}

		recordFlight(me, pool, rtt, offhandTotem, invTotems, autoTotem);
		poolBefore = pool;
		publish(rtt);
		if (tel != null && tick % FLUSH_EVERY == 0) tel.flush();
	}

	private void samplePing(ClientPlayerEntity me) {
		int own = Sensor.latency(mc, me);
		if (own >= 0) stats.ping.add(own);
		if (tel != null && tick % 100 == 0) {
			tel.emit(tel.event("ping", tick).num("ms", own).num("p50", nz(stats.ping.p50()))
				.num("p95", nz(stats.ping.p95())).num("lead", stats.ping.leadTicks(pingLeadExtra.get()))
				.num("tps", TickRate.INSTANCE.getTickRate()));
		}
	}

	private void trackRefill(boolean offhandTotem) {
		if (popTick < 0) return;
		if (offhandTotem) {
			int ticks = tick - popTick;
			stats.totemRefillTicks.add(ticks);
			if (tel != null) tel.emit(tel.event("refill", tick).num("ticks", ticks));
			popTick = -1;
		} else if (tick - popTick > REFILL_TIMEOUT) {
			if (tel != null) tel.emit(tel.event("refill_timeout", tick).num("ticks", tick - popTick));
			popTick = -1;
		}
	}

	private void updateTracks(ClientPlayerEntity me) {
		double range2 = trackRange.get() * trackRange.get();
		for (PlayerEntity p : mc.world.getPlayers()) {
			if (p == me || p.isSpectator() || p.isDead()) continue;
			if (ignoreFriends.get() && Friends.get().isFriend(p)) continue;
			if (p.squaredDistanceTo(me) > range2) continue;
			PlayerTrack t = tracks.computeIfAbsent(p.getId(), PlayerTrack::new);
			t.name = p.getGameProfile().getName();
			Observation o = Sensor.observe(mc, p, tick);
			t.update(o);
			if (o.pingMs() >= 0 && tick % 40 == 0) stats.enemyPingMs.add(o.pingMs());
		}
		tracks.values().removeIf(t -> {
			boolean stale = tick - t.lastSeenTick() > 40;
			if (stale) {
				lastLevel.remove(t.id);
				committed.remove(t.id);
				dives.remove(t.id);
			}
			return stale;
		});
	}

	private void evaluateThreats(ClientPlayerEntity me, int rtt, Inventory inv) {
		List<CrystalView> crystals = Sensor.crystals(mc, me, tc.crystalNear + 4);
		SelfView sv = new SelfView(me.getX(), me.getY(), me.getZ(), rtt);
		assessments.clear();
		top = null;
		topTrack = null;
		for (PlayerTrack t : tracks.values()) {
			if (t.lastSeenTick() != tick) continue;
			ThreatAssessment a = ThreatModel.evaluate(t, sv, crystals, tc, tick);
			assessments.put(t.id, a);
			trackDive(t, a, me);
			ThreatLevel prev = lastLevel.getOrDefault(t.id, ThreatLevel.IDLE);
			if (a.level() != prev) {
				lastLevel.put(t.id, a.level());
				if (a.level().atLeast(ThreatLevel.WATCH) || prev.atLeast(ThreatLevel.WATCH)) emitThreat(t, a, prev, me);
			}
			if (!a.level().atLeast(ThreatLevel.COMMIT)) committed.remove(t.id);
			else if (!committed.contains(t.id)) onCommit(t, a, rtt, inv, me);
			if (top == null || a.level().ordinal() > top.level().ordinal()
				|| (a.level() == top.level() && a.score() > top.score())) {
				top = a;
				topTrack = t;
			}
		}
		plan = top == null ? ShadowPlan.NONE : ShadowPlanner.plan(top, inv, rtt, verdict);
	}

	private void onCommit(PlayerTrack t, ThreatAssessment a, int rtt, Inventory inv, ClientPlayerEntity me) {
		committed.add(t.id);
		if (!stats.scorecard.openCommit(t.id, a.archetype(), tick, a.etaTicks(), a.score())) return;
		stats.shadowCommits++;
		ShadowPlan p = ShadowPlanner.plan(a, inv, rtt, verdict);
		if (tel != null) {
			tel.emit(tel.event("commit", tick).num("pid", t.id).str("name", t.name)
				.str("arch", a.archetype().name()).num("score", a.score()).num("eta", a.etaTicks())
				.num("fall", a.fallAtImpact()).num("dist", a.minDistance())
				.num("est_mace", a.maceDamage()).num("est_crystal", a.crystalDamage())
				.num("crystals", a.crystalsNear()).num("rtt", rtt)
				.str("plan", p.action().name()).num("plan_in", p.fireInTicks()).ints("parts", a.parts()));
			dumpRateLimited("commit");
		}
		if (chatFeedback.get()) {
			ChatUtils.warning("COMMIT " + t.name + " " + a.archetype() + " eta=" + a.etaTicks() + "t plan="
				+ p.action() + (p.fireInTicks() > 0 ? " in " + p.fireInTicks() + "t" : ""));
		}
	}

	private void onFalsePositive(Scorecard.Resolved r) {
		if (tel != null) {
			tel.emit(tel.event("commit_fp", tick).num("pid", r.commit().playerId())
				.str("arch", r.commit().archetype().name()).num("age", r.leadTicks())
				.num("score", r.commit().score()));
		}
		if (chatFeedback.get()) ChatUtils.info("commit expired (false alarm): " + r.commit().archetype());
	}

	private void emitThreat(PlayerTrack t, ThreatAssessment a, ThreatLevel prev, ClientPlayerEntity me) {
		if (tel == null) return;
		Observation o = t.latest();
		tel.emit(tel.event("threat", tick).num("pid", t.id).str("name", t.name)
			.str("from", prev.name()).str("to", a.level().name()).str("arch", a.archetype().name())
			.num("score", a.score()).num("eta", a.etaTicks()).num("dist", me.distanceTo(mcEntity(t.id)))
			.num("dy", o == null ? 0 : o.y() - me.getY()).num("fall", a.fallAtImpact())
			.str("main", o == null ? "none" : Sensor.heldCode(o.mainHand()))
			.str("off", o == null ? "none" : Sensor.heldCode(o.offHand()))
			.num("ping_them", o == null ? -1 : o.pingMs()).ints("parts", a.parts()));
	}

	private Entity mcEntity(int id) {
		Entity e = mc.world.getEntityById(id);
		return e == null ? mc.player : e;
	}

	/** Measures how well the airborne arc is predicted: landing position and tick. */
	private void trackDive(PlayerTrack t, ThreatAssessment a, ClientPlayerEntity me) {
		DiveRecord d = dives.get(t.id);
		Observation o = t.latest();
		if (o == null) return;
		if (d == null) {
			if (t.descending() && t.fallDistance() >= tc.minFall && !o.gliding()) {
				int ttg = t.ticksToGround(tc.horizonTicks);
				if (ttg < 0) return;
				double[][] path = t.project(ttg);
				dives.put(t.id, new DiveRecord(tick, tick + ttg, path[ttg][0], path[ttg][2],
					t.fallDistance(), t.maceRecent(tick, tc.equipmentMemoryTicks)));
				stats.divesSeen++;
			}
			return;
		}
		boolean landed = !t.airborne() || o.onGround();
		if (landed || tick - d.startTick > 120) {
			dives.remove(t.id);
			if (!landed) return;
			double err = Math.hypot(o.x() - d.predictedX, o.z() - d.predictedZ);
			int errTicks = tick - d.predictedLandTick;
			stats.landingErrorBlocks.add(err);
			stats.landingErrorTicks.add(errTicks);
			if (tel != null) {
				tel.emit(tel.event("dive_land", tick).num("pid", t.id).str("name", t.name)
					.bool("mace", d.mace).num("fall_start", d.fallAtStart)
					.num("pred_ticks", d.predictedLandTick - d.startTick).num("act_ticks", tick - d.startTick)
					.num("err_blocks", err).num("err_ticks", errTicks)
					.num("land_dx", o.x() - me.getX()).num("land_dz", o.z() - me.getZ()));
			}
		}
	}

	// ----------------------------------------------------- hits and packets --

	@EventHandler
	private void onPacket(PacketEvent.Receive event) {
		if (mc.player == null) return;
		Packet<?> pk = event.packet;
		if (pk instanceof EntityDamageS2CPacket p) {
			if (p.entityId() != mc.player.getId()) return;
			String type = p.sourceType().getKey().map(k -> k.getValue().getPath()).orElse("unknown");
			int cause = p.sourceCauseId();
			mc.execute(() -> pendingHits.add(new PendingHit(tick, type, cause, poolBefore)));
		} else if (pk instanceof EntityStatusS2CPacket p && p.getStatus() == TOTEM_STATUS) {
			mc.execute(() -> onTotemPop(p));
		} else if (pk instanceof ExplosionS2CPacket p) {
			Vec3d center = p.center();
			Vec3d kb = p.playerKnockback().orElse(null);
			mc.execute(() -> onExplosion(center, kb));
		} else if (pk instanceof EntityVelocityUpdateS2CPacket p) {
			if (p.getEntityId() != mc.player.getId()) return;
			double speed = Math.sqrt(p.getVelocityX() * p.getVelocityX() + p.getVelocityY() * p.getVelocityY()
				+ p.getVelocityZ() * p.getVelocityZ());
			mc.execute(() -> onSelfVelocity(speed));
		}
	}

	private void onTotemPop(EntityStatusS2CPacket p) {
		if (mc.world == null || mc.player == null) return;
		Entity e = p.getEntity(mc.world);
		if (e == mc.player) {
			popTick = tick;
			lastPopTick = tick;
			stats.scorecard.onTotemPop();
			if (tel != null) {
				tel.emit(tel.event("totem_pop", tick).str("who", "self").num("left", Sensor.mainInventoryTotems(mc.player))
					.num("top_score", top == null ? 0 : top.score()).str("top_level", top == null ? "IDLE" : top.level().name()));
				dumpRateLimited("pop");
			}
			if (chatFeedback.get()) ChatUtils.warning("Totem popped | spare=" + Sensor.mainInventoryTotems(mc.player));
		} else if (e instanceof PlayerEntity other && tel != null) {
			tel.emit(tel.event("totem_pop", tick).str("who", "enemy").str("name", other.getGameProfile().getName())
				.num("dist", other.distanceTo(mc.player)));
		}
	}

	private void onExplosion(Vec3d center, Vec3d kb) {
		if (mc.player == null) return;
		double dx = center.x - mc.player.getX();
		double dy = center.y - mc.player.getY();
		double dz = center.z - mc.player.getZ();
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist > 24) return;
		lastExplosionTick = tick;
		lastExplosionRaw = Damage.crystalRaw(dist, 1.0, tc.difficultyMul);
		if (tel != null) {
			JsonLine l = tel.event("explosion", tick).num("dx", dx).num("dy", dy).num("dz", dz).num("dist", dist)
				.num("raw_est", lastExplosionRaw);
			if (kb != null) l.num("kb_x", kb.x).num("kb_y", kb.y).num("kb_z", kb.z).num("kb_mag", kb.length());
			tel.emit(l);
		}
	}

	private void onSelfVelocity(double speed) {
		if (speed < 0.4) return;
		stats.selfKnockback.add(speed);
		if (tel != null) {
			tel.emit(tel.event("self_velocity", tick).num("speed", speed)
				.num("since_explosion", tick - lastExplosionTick));
		}
	}

	private static Archetype classify(String type) {
		return switch (type) {
			case "mace_smash" -> Archetype.MACE;
			case "explosion", "player_explosion" -> Archetype.CRYSTAL;
			default -> Archetype.NONE;
		};
	}

	private void resolveHits(ClientPlayerEntity me, double poolNow) {
		if (pendingHits.isEmpty()) return;
		List<PendingHit> ready = new ArrayList<>();
		for (PendingHit h : pendingHits) {
			if (tick > h.tick() || tick - h.tick() > PENDING_HIT_MAX_AGE) ready.add(h);
		}
		if (ready.isEmpty()) return;
		pendingHits.removeAll(ready);
		double dmg = Math.max(0, ready.get(0).poolBefore() - poolNow);
		for (int i = 0; i < ready.size(); i++) {
			processHit(ready.get(i), i == 0 ? dmg : 0, poolNow);
		}
	}

	private void processHit(PendingHit h, double dmg, double poolNow) {
		Archetype a = classify(h.type());
		int attacker = tracks.containsKey(h.causeId()) ? h.causeId() : -1;
		Scorecard.Resolved r = stats.scorecard.onHit(a, h.tick(), attacker);
		if (a != Archetype.NONE) {
			int idx = a.ordinal();
			if (tick - lastHitTick[idx] < 100 && lastHitTick[idx] >= 0) stats.burstGapTicks.add(h.tick() - lastHitTick[idx]);
			lastHitTick[idx] = h.tick();
			if (dmg > 0) {
				if (a == Archetype.MACE) stats.maceHitDamage.add(dmg);
				else stats.crystalHitDamage.add(dmg);
			}
		}
		double crystalPred = 0;
		if (a == Archetype.CRYSTAL && h.tick() - lastExplosionTick <= 3 && dmg > 0
			&& Math.abs(h.tick() - lastPopTick) > 3 && poolNow >= 2) {
			crystalPred = Damage.afterProtection(Damage.afterArmor(lastExplosionRaw, tc.assumedArmor, tc.assumedToughness), tc.assumedEpf);
			stats.crystalModelError.add(dmg - crystalPred);
		}
		String name = attacker >= 0 ? tracks.get(attacker).name : "";
		if (tel != null) {
			JsonLine l = tel.event("hit", h.tick()).str("dmg_type", h.type()).str("arch", a.name())
				.num("dmg", dmg).num("pool_after", poolNow).str("attacker", name)
				.bool("had_commit", r != null).num("top_score", top == null ? 0 : top.score())
				.str("top_level", top == null ? "IDLE" : top.level().name());
			if (r != null) l.num("lead", r.leadTicks()).num("eta_err", r.etaErrorTicks());
			if (a != Archetype.NONE && r == null) l.bool("miss", true);
			if (crystalPred > 0) l.num("pred_after_armor", crystalPred);
			tel.emit(l);
			dumpRateLimited(r == null && a != Archetype.NONE ? "miss" : "hit");
		}
		if (chatFeedback.get() && a != Archetype.NONE) {
			ChatUtils.info((r != null ? "HIT (predicted, lead " + r.leadTicks() + "t) " : "HIT (MISSED) ") + a
				+ " " + String.format(Locale.ROOT, "%.1f", dmg) + " hp");
		}
	}

	private void onDeath(double pool) {
		deathLogged = true;
		stats.deaths++;
		if (tel != null) {
			tel.emit(tel.event("death", tick).num("pool_before", poolBefore)
				.str("verdict", verdict == null ? "?" : verdict.kind().name())
				.str("top_level", top == null ? "IDLE" : top.level().name()));
			tel.dump("death", tick);
			tel.flush();
		}
	}

	private void dumpRateLimited(String reason) {
		if (tel == null) return;
		if (tick - lastDumpTick < 10) return;
		lastDumpTick = tick;
		tel.dump(reason, tick);
	}

	// -------------------------------------------------------------- crystals --

	@EventHandler
	private void onEntityAdded(EntityAddedEvent event) {
		if (tel == null || mc.player == null || !(event.entity instanceof EndCrystalEntity c)) return;
		double dist = c.distanceTo(mc.player);
		if (dist > 24) return;
		PlayerTrack nearest = null;
		double best = 9.0;
		for (PlayerTrack t : tracks.values()) {
			Observation o = t.latest();
			if (o == null) continue;
			double d = Math.sqrt(Math.pow(o.x() - c.getX(), 2) + Math.pow(o.y() - c.getY(), 2) + Math.pow(o.z() - c.getZ(), 2));
			if (d < best) {
				best = d;
				nearest = t;
			}
		}
		tel.emit(tel.event("crystal_add", tick).num("dx", c.getX() - mc.player.getX()).num("dy", c.getY() - mc.player.getY())
			.num("dz", c.getZ() - mc.player.getZ()).num("dist", dist)
			.num("raw_est", Damage.crystalRaw(dist, 1.0, tc.difficultyMul))
			.str("near", nearest == null ? "" : nearest.name).num("near_dist", nearest == null ? -1 : best)
			.str("near_main", nearest == null || nearest.latest() == null ? "none" : Sensor.heldCode(nearest.latest().mainHand())));
	}

	@EventHandler
	private void onEntityRemoved(EntityRemovedEvent event) {
		if (mc.player == null || !(event.entity instanceof EndCrystalEntity c)) return;
		double dist = c.distanceTo(mc.player);
		if (dist > 24) return;
		stats.crystalLifeTicks.add(c.age);
		if (tel != null) tel.emit(tel.event("crystal_gone", tick).num("life", c.age).num("dist", dist));
	}

	// ---------------------------------------------------------------- safety --

	private void runSafety(ClientPlayerEntity me, ThreatAssessment ta, SurvivalInput in) {
		SurvivalVerdict v = verdict;
		if (v.kind() != lastKind || (tick % 200 == 0 && tel != null)) {
			if (tel != null) {
				tel.emit(tel.event("survival", tick).str("kind", v.kind().name()).num("lives", v.lives())
					.num("bursts", v.burstsToDeath()).num("ttd", v.timeToDeathTicks()).num("margin", v.marginTicks())
					.num("hp", in.hp()).num("abs", in.absorption()).num("spares", in.inventoryTotems())
					.bool("offhand", in.offhandTotem()).bool("autototem", in.autoTotemOn()).num("refill", in.refillTicks())
					.str("arch", ta.archetype().name()).str("level", ta.level().name()).num("burst", ta.burstDamage())
					.strs("flags", v.flags()));
			}
			lastKind = v.kind();
		}
		if (v.doomed()) {
			doomedStreak++;
			stats.safetyDoomedTicks++;
		} else {
			doomedStreak = 0;
		}
		if (safetyMode.get() == SafetyMode.Off) return;
		if (doomedStreak < confirmTicks.get() || tick - startTick < warmupTicks.get()) return;
		if (tick - lastSafetyTick < SAFETY_COOLDOWN) return;
		lastSafetyTick = tick;
		stats.safetyTriggers++;
		SafetyMode mode = safetyMode.get();
		String why = String.join(",", v.flags());
		if (tel != null) {
			tel.emit(tel.event("safety", tick).str("mode", mode.name()).num("margin", v.marginTicks())
				.num("lives", v.lives()).str("flags", why).str("arch", ta.archetype().name())
				.num("eta", ta.etaTicks()).num("burst", ta.burstDamage()));
			tel.dump("safety", tick);
			tel.flush();
		}
		String msg = "DOOMED margin=" + v.marginTicks() + "t lives=" + v.lives() + " " + ta.archetype()
			+ (why.isEmpty() ? "" : " [" + why + "]");
		switch (mode) {
			case Notify -> ChatUtils.warning("SAFETY: " + msg);
			case DryLog -> ChatUtils.warning("SAFETY (dry run, would log out): " + msg);
			case Log -> disconnect(msg);
			default -> { }
		}
	}

	private void disconnect(String msg) {
		if (blockReconnect.get()) {
			AutoReconnect ar = Modules.get().get(AutoReconnect.class);
			if (ar != null && ar.isActive()) ar.toggle();
		}
		if (mc.getNetworkHandler() != null) {
			mc.getNetworkHandler().getConnection().disconnect(Text.literal("[Anti-Client] safety log: " + msg));
		}
	}

	// ----------------------------------------------------- flight recorder --

	private void recordFlight(ClientPlayerEntity me, double pool, int rtt, boolean offhand, int spares, boolean auto) {
		if (tel == null) return;
		JsonLine row = tel.event("tk", tick).num("hp", me.getHealth()).num("abs", me.getAbsorptionAmount())
			.num("spares", spares).bool("off", offhand).bool("auto", auto).num("rtt", rtt)
			.str("sv", verdict == null ? "?" : verdict.kind().name());
		int n = 0;
		List<PlayerTrack> order = new ArrayList<>(tracks.values());
		order.sort((x, y) -> Integer.compare(scoreOf(y), scoreOf(x)));
		for (PlayerTrack t : order) {
			if (n >= 3) break;
			Observation o = t.latest();
			ThreatAssessment a = assessments.get(t.id);
			if (o == null || a == null) continue;
			double[] v = t.velocity();
			String p = "p" + n + "_";
			row.str(p + "n", t.name).num(p + "dx", o.x() - me.getX()).num(p + "dy", o.y() - me.getY())
				.num(p + "dz", o.z() - me.getZ()).num(p + "vy", v[1]).num(p + "vh", Math.hypot(v[0], v[2]))
				.num(p + "fall", t.fallDistance()).bool(p + "air", t.airborne())
				.str(p + "m", Sensor.heldCode(o.mainHand())).num(p + "s", a.score())
				.str(p + "l", a.level().name()).num(p + "eta", a.etaTicks());
			n++;
		}
		tel.record(row);
	}

	private int scoreOf(PlayerTrack t) {
		ThreatAssessment a = assessments.get(t.id);
		return a == null ? -1 : a.level().ordinal() * 1000 + a.score();
	}

	private void publish(int rtt) {
		ThreatAssessment a = top == null ? ThreatAssessment.idle(-1) : top;
		SurvivalVerdict v = verdict;
		snapshot = new HudSnapshot(true, tel != null ? tel.scenario() : scenario.get(), a.level(), a.archetype(),
			topTrack == null ? "" : topTrack.name, a.score(), a.etaTicks(), plan,
			v == null ? SurvivalVerdict.Kind.OK : v.kind(), v == null ? 0 : v.lives(),
			v == null ? 0 : v.marginTicks(), v == null ? "" : String.join(",", v.flags()),
			stats.ping.lastMs(), rtt, stats.scorecard.tp(), stats.scorecard.fp(), stats.scorecard.miss());
	}

	// ---------------------------------------------------------------- render --

	@EventHandler
	private void onRender3D(Render3DEvent event) {
		if (!renderPath.get() || topTrack == null || top == null) return;
		if (!top.level().atLeast(ThreatLevel.WATCH) || !topTrack.airborne()) return;
		int ttg = topTrack.ticksToGround(tc.horizonTicks);
		int n = ttg < 0 ? tc.horizonTicks : ttg;
		double[][] path = topTrack.project(n);
		Color c = switch (top.level()) {
			case COMMIT -> new Color(255, 40, 40, 255);
			case ARMED -> new Color(255, 160, 0, 255);
			default -> new Color(255, 230, 0, 200);
		};
		for (int k = 0; k < n; k++) {
			event.renderer.line(path[k][0], path[k][1] + 0.9, path[k][2], path[k + 1][0], path[k + 1][1] + 0.9, path[k + 1][2], c);
		}
		double[] end = path[n];
		event.renderer.boxLines(end[0] - 0.4, end[1], end[2] - 0.4, end[0] + 0.4, end[1] + 1.8, end[2] + 0.4, c, 0);
	}

	private static double nz(double v) {
		return Double.isNaN(v) ? -1 : v;
	}
}
