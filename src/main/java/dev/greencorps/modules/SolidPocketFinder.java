package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.util.DensityBand;
import dev.greencorps.util.DensityStats;
import dev.greencorps.util.SolidPocketScore;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Finds dense underground lower-hemisphere volumes (minimal spawn-proof effort).
 * Near-density transparency, score history, chat/sound, Xaero waypoint, optional ESP.
 */
public class SolidPocketFinder extends Module {
	private static final int ANCHOR_XZ_STEP = 16;
	private static final int ANCHOR_Y_STEP = 16;
	private static final int TICK_SCORE_BUDGET = 3;
	private static final int NEAR_PROBE_INTERVAL = 20;
	private static final int MAX_CAVE_MARKERS = 200;

	public enum Strictness {
		Loose(78),
		Balanced(88),
		Strict(94);

		public final int threshold;

		Strictness(int threshold) {
			this.threshold = threshold;
		}
	}

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgRender = settings.createGroup("Render");

	private final Setting<Integer> waypointThreshold = sgGeneral.add(new IntSetting.Builder()
		.name("waypoint-threshold")
		.description("Min solid percent to save a pin. 78 explore / 88 quick proof / 94 nearly solid. Higher = fewer waypoints.")
		.defaultValue(88)
		.min(0)
		.max(100)
		.sliderRange(40, 100)
		.build()
	);

	private final Setting<Strictness> strictness = sgGeneral.add(new EnumSetting.Builder<Strictness>()
		.name("strictness")
		.description("Preset density bar. Loose=78 explore, Balanced=88 quick proof, Strict=94 nearly solid. Writes waypoint-threshold.")
		.defaultValue(Strictness.Balanced)
		.onChanged(v -> {
			if (v != null) waypointThreshold.set(v.threshold);
		})
		.build()
	);

	private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
		.name("radius")
		.description("Farm volume radius (blocks). Larger = bigger site to spawn-proof, costlier scan.")
		.defaultValue(32)
		.min(16)
		.max(48)
		.sliderRange(16, 48)
		.build()
	);

	private final Setting<Integer> sampleStep = sgGeneral.add(new IntSetting.Builder()
		.name("sample-step")
		.description("Grid step when sampling rock vs air (higher = cheaper, coarser).")
		.defaultValue(4)
		.min(2)
		.max(8)
		.sliderRange(2, 8)
		.build()
	);

	private final Setting<Integer> mergeDistance = sgGeneral.add(new IntSetting.Builder()
		.name("merge-distance")
		.description("How far apart two dense hits can be and still share one waypoint.")
		.defaultValue(48)
		.min(16)
		.sliderRange(24, 96)
		.build()
	);

	private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
		.name("min-y")
		.description("Lowest Y band for candidate anchors.")
		.defaultValue(-56)
		.min(-64)
		.max(100)
		.sliderRange(-64, 40)
		.build()
	);

	private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
		.name("max-y")
		.description("Highest Y band for candidate anchors (keep underground).")
		.defaultValue(40)
		.min(-64)
		.max(120)
		.sliderRange(-32, 80)
		.build()
	);

	private final Setting<Boolean> scoreOres = sgGeneral.add(new BoolSetting.Builder()
		.name("score-ores")
		.description("Also score ore richness among solid samples and show it on waypoints/chat.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Integer> statsInterval = sgGeneral.add(new IntSetting.Builder()
		.name("stats-interval")
		.description("Seconds between density history chat lines (0 = off).")
		.defaultValue(30)
		.min(0)
		.max(300)
		.sliderRange(0, 120)
		.build()
	);

	private final Setting<Boolean> chatAlerts = sgGeneral.add(new BoolSetting.Builder()
		.name("chat-alerts")
		.description("Client chat once when a solid pocket first crosses the density threshold.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> alertSound = sgGeneral.add(new BoolSetting.Builder()
		.name("alert-sound")
		.description("Play a client sound when a solid pocket is first confirmed.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> esp = sgRender.add(new BoolSetting.Builder()
		.name("esp")
		.description("Draw hemisphere outline and cave markers for confirmed solid pockets.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> espRange = sgRender.add(new IntSetting.Builder()
		.name("esp-range")
		.description("Max distance from player to draw pocket ESP.")
		.defaultValue(64)
		.min(16)
		.max(128)
		.sliderRange(16, 128)
		.build()
	);

	private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
		.name("shape-mode")
		.description("How cave marker boxes are rendered.")
		.defaultValue(ShapeMode.Both)
		.build()
	);

	private final Setting<Integer> fillOpacity = sgRender.add(new IntSetting.Builder()
		.name("fill-opacity")
		.description("Fill opacity for cave markers.")
		.defaultValue(40)
		.range(0, 255)
		.sliderMax(255)
		.build()
	);

	private final Setting<SettingColor> pocketColor = sgRender.add(new ColorSetting.Builder()
		.name("pocket-color")
		.description("Hemisphere outline color.")
		.defaultValue(new SettingColor(180, 220, 120, 255))
		.build()
	);

	private final Setting<SettingColor> caveColor = sgRender.add(new ColorSetting.Builder()
		.name("cave-color")
		.description("Cave / hole marker color.")
		.defaultValue(new SettingColor(255, 80, 80, 255))
		.build()
	);

	private final Color lineColor = new Color();
	private final Color sideColor = new Color();

	private final Queue<Long> pendingAnchors = new ArrayDeque<>();
	private final Set<Long> enqueuedOrScored = new HashSet<>();
	private final List<Pocket> pockets = new ArrayList<>();
	private final Set<Long> livePockets = new HashSet<>();
	private final DensityStats history = new DensityStats();

	private int pruneTicker;
	private int nearProbeTicker;
	private int statsChatTicker;
	private Integer lastThreshold;
	private Boolean lastScoreOres;

	private int nearDensity = -1;
	private boolean nearValid;

	public SolidPocketFinder() {
		super(GreenCorps.CATEGORY, "solid-pocket", "Finds dense underground hemispheres for low spawn-proof farm sites; near density, history, chat, sound, waypoint, ESP.");
	}

	public static SolidPocketFinder get() {
		return meteordevelopment.meteorclient.systems.modules.Modules.get().get(SolidPocketFinder.class);
	}

	public int liveCount() {
		return isActive() ? livePockets.size() : 0;
	}

	public int nearDensity() {
		return nearValid ? nearDensity : -1;
	}

	public DensityStats history() {
		return history;
	}

	@Override
	public void onActivate() {
		lastThreshold = waypointThreshold.get();
		lastScoreOres = scoreOres.get();
		pendingAnchors.clear();
		enqueuedOrScored.clear();
		nearDensity = -1;
		nearValid = false;
		nearProbeTicker = 0;
		statsChatTicker = 0;
		enqueueAllLoadedChunks();
		for (Pocket p : pockets) {
			if (p.confirmed) {
				updateWaypoint(p);
				p.caveMarkersDirty = true;
			}
		}
	}

	@Override
	public void onDeactivate() {
		pendingAnchors.clear();
		livePockets.clear();
		nearValid = false;
	}

	@EventHandler
	private void onChunkData(ChunkDataEvent event) {
		if (mc.world == null) return;
		enqueueChunk(event.chunk().getPos());
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		if (mc.world == null) return;

		int threshold = waypointThreshold.get();
		boolean ores = scoreOres.get();
		if (lastThreshold == null || lastThreshold != threshold || lastScoreOres == null || lastScoreOres != ores) {
			lastThreshold = threshold;
			lastScoreOres = ores;
			for (Pocket p : pockets) {
				if (p.confirmed) updateWaypoint(p);
			}
		}

		int budget = TICK_SCORE_BUDGET;
		while (budget-- > 0 && !pendingAnchors.isEmpty()) {
			Long key = pendingAnchors.poll();
			if (key == null) break;
			scoreAnchor(BlockPos.fromLong(key));
		}

		if (++nearProbeTicker >= NEAR_PROBE_INTERVAL) {
			nearProbeTicker = 0;
			probeNear();
		}

		int intervalSec = statsInterval.get();
		if (intervalSec > 0) {
			if (++statsChatTicker >= intervalSec * 20) {
				statsChatTicker = 0;
				chatStats();
			}
		}

		if (++pruneTicker < 40) return;
		pruneTicker = 0;
		pruneLive();
	}

	@EventHandler
	private void onRender3D(Render3DEvent event) {
		if (!esp.get() || mc.player == null) return;
		double range = espRange.get();
		double rangeSq = range * range;
		double px = mc.player.getX();
		double py = mc.player.getY();
		double pz = mc.player.getZ();
		int r = radius.get();

		for (Pocket pocket : pockets) {
			if (!pocket.confirmed || pocket.anchor == null) continue;
			if (!pocket.dimension.equals(dimensionId())) continue;
			if (!livePockets.contains(pocket.anchorKey())) continue;

			double dx = pocket.anchor.getX() + 0.5 - px;
			double dy = pocket.anchor.getY() + 0.5 - py;
			double dz = pocket.anchor.getZ() + 0.5 - pz;
			if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

			drawHemisphereOutline(event, pocket.anchor, r, pocketColor.get());
			refreshCaveMarkersIfNeeded(pocket);
			for (BlockPos cave : pocket.caveMarkers) {
				drawMarker(event, cave, caveColor.get());
			}
		}
	}

	private void refreshCaveMarkersIfNeeded(Pocket pocket) {
		if (!pocket.caveMarkersDirty && !pocket.caveMarkers.isEmpty()) return;
		if (mc.world == null || pocket.anchor == null) return;
		pocket.caveMarkers.clear();
		pocket.caveMarkers.addAll(SolidPocketScore.collectCaveMarkers(
			mc.world,
			pocket.anchor,
			radius.get(),
			sampleStep.get(),
			MAX_CAVE_MARKERS
		));
		pocket.caveMarkersDirty = false;
	}

	private void drawHemisphereOutline(Render3DEvent event, BlockPos anchor, int r, SettingColor color) {
		lineColor.set(color);
		sideColor.set(color);
		sideColor.a = 0;
		double minX = anchor.getX() - r;
		double minY = anchor.getY() - r;
		double minZ = anchor.getZ() - r;
		double maxX = anchor.getX() + r + 1;
		double maxY = anchor.getY() + 1;
		double maxZ = anchor.getZ() + r + 1;
		event.renderer.box(minX, minY, minZ, maxX, maxY, maxZ, sideColor, lineColor, ShapeMode.Lines, 0);
	}

	private void drawMarker(Render3DEvent event, BlockPos pos, SettingColor color) {
		lineColor.set(color);
		sideColor.set(color);
		sideColor.a = fillOpacity.get();
		event.renderer.box(pos, sideColor, lineColor, shapeMode.get(), 0);
	}

	private void probeNear() {
		if (mc.player == null || mc.world == null) {
			nearValid = false;
			return;
		}
		int y = mc.player.getBlockY();
		int yLo = Math.min(minY.get(), maxY.get());
		int yHi = Math.max(minY.get(), maxY.get());
		y = Math.max(yLo, Math.min(yHi, y));
		BlockPos anchor = new BlockPos(mc.player.getBlockX(), y, mc.player.getBlockZ());
		SolidPocketScore.Result result = SolidPocketScore.score(
			mc.world,
			anchor,
			radius.get(),
			sampleStep.get(),
			false
		);
		if (result.valid) {
			nearDensity = result.density;
			nearValid = true;
			history.record(result.density);
		} else {
			nearValid = false;
		}
	}

	private void chatStats() {
		String near = nearValid ? String.valueOf(nearDensity) : "?";
		if (history.n() <= 0) {
			info("Solid pocket near=" + near + " | n=0 (still sampling)");
			return;
		}
		info("Solid pocket near=" + near
			+ " | n=" + history.n()
			+ " max=" + history.max()
			+ " p50=" + history.percentile(50)
			+ " p90=" + history.percentile(90)
			+ " | " + history.highBinsSummary());
	}

	private void enqueueAllLoadedChunks() {
		if (mc.world == null || mc.player == null) return;
		int rd = mc.options.getViewDistance().getValue();
		ChunkPos center = mc.player.getChunkPos();
		for (int x = center.x - rd; x <= center.x + rd; x++) {
			for (int z = center.z - rd; z <= center.z + rd; z++) {
				if (!mc.world.isChunkLoaded(x, z)) continue;
				enqueueChunk(new ChunkPos(x, z));
			}
		}
	}

	private void enqueueChunk(ChunkPos chunkPos) {
		if (mc.world == null) return;
		int yLo = Math.min(minY.get(), maxY.get());
		int yHi = Math.max(minY.get(), maxY.get());
		int worldBottom = mc.world.getBottomY();
		int worldTop = worldBottom + mc.world.getHeight() - 1;
		yLo = Math.max(yLo, worldBottom + 8);
		yHi = Math.min(yHi, worldTop - 8);

		int baseX = chunkPos.getStartX();
		int baseZ = chunkPos.getStartZ();
		for (int lx = 4; lx < 16; lx += ANCHOR_XZ_STEP) {
			for (int lz = 4; lz < 16; lz += ANCHOR_XZ_STEP) {
				for (int y = yLo; y <= yHi; y += ANCHOR_Y_STEP) {
					long key = BlockPos.asLong(baseX + lx, y, baseZ + lz);
					if (enqueuedOrScored.add(key)) {
						pendingAnchors.offer(key);
					}
				}
			}
		}
	}

	private void scoreAnchor(BlockPos anchor) {
		if (mc.world == null) return;

		SolidPocketScore.Result result = SolidPocketScore.score(
			mc.world,
			anchor,
			radius.get(),
			sampleStep.get(),
			scoreOres.get()
		);
		if (!result.valid) return;
		history.record(result.density);

		if (result.density < waypointThreshold.get()) return;

		Pocket pocket = findNearest(anchor);
		if (pocket == null) {
			pocket = new Pocket(dimensionId());
			pockets.add(pocket);
		}

		boolean wasConfirmed = pocket.confirmed;
		boolean improved = !pocket.confirmed
			|| result.density > pocket.density
			|| (result.density == pocket.density && result.ore > pocket.ore);

		if (improved) {
			pocket.anchor = anchor.toImmutable();
			pocket.density = result.density;
			pocket.ore = result.ore;
			pocket.confirmed = true;
			pocket.caveMarkersDirty = true;
			livePockets.add(pocket.anchorKey());
			updateWaypoint(pocket);
			if (!wasConfirmed) {
				alertNew(pocket);
			}
		} else if (pocket.confirmed) {
			livePockets.add(pocket.anchorKey());
		}
	}

	private void alertNew(Pocket pocket) {
		BlockPos anchor = pocket.anchor;
		if (chatAlerts.get() && anchor != null) {
			StringBuilder msg = new StringBuilder();
			msg.append("Solid pocket ")
				.append(anchor.getX()).append(' ')
				.append(anchor.getY()).append(' ')
				.append(anchor.getZ())
				.append(" (").append(pocket.density).append(" DEN");
			if (scoreOres.get()) {
				msg.append('/').append(pocket.ore).append(" ORE");
			}
			msg.append(')');
			if (pocket.waypointSaved) {
				msg.append(" — ").append(XaeroWaypoints.solidPocketName(pocket.density, pocket.ore, scoreOres.get()));
			}
			info(msg.toString());
		}
		if (alertSound.get()) {
			playAlert(pocket.density);
		}
	}

	private void updateWaypoint(Pocket pocket) {
		if (!pocket.confirmed || pocket.anchor == null) return;
		int threshold = waypointThreshold.get();
		boolean ores = scoreOres.get();

		if (pocket.density < threshold) {
			if (pocket.waypointSaved && pocket.waypointPos != null) {
				XaeroWaypoints.removeWaypointAt(pocket.waypointPos);
				pocket.waypointSaved = false;
				pocket.waypointPos = null;
			}
			return;
		}

		BlockPos written = XaeroWaypoints.upsertSolidPocketWaypoint(
			pocket.waypointPos,
			pocket.anchor,
			pocket.density,
			pocket.ore,
			ores
		);
		if (written != null) {
			pocket.waypointPos = written;
			pocket.waypointSaved = true;
		}
	}

	private void pruneLive() {
		if (mc.world == null) {
			livePockets.clear();
			return;
		}
		livePockets.clear();
		for (Pocket p : pockets) {
			if (!p.confirmed || p.anchor == null) continue;
			if (!p.dimension.equals(dimensionId())) continue;
			ChunkPos cp = new ChunkPos(p.anchor);
			if (mc.world.isChunkLoaded(cp.x, cp.z)) {
				livePockets.add(p.anchorKey());
			}
		}

		if (mc.player != null && enqueuedOrScored.size() > 8000) {
			enqueuedOrScored.clear();
			pendingAnchors.clear();
			enqueueAllLoadedChunks();
		}
	}

	private Pocket findNearest(BlockPos pos) {
		int merge = mergeDistance.get();
		double mergeSq = (double) merge * merge;
		Pocket best = null;
		double bestDist = Double.MAX_VALUE;

		for (Pocket p : pockets) {
			if (!p.dimension.equals(dimensionId()) || p.anchor == null) continue;
			double dx = p.anchor.getX() - pos.getX();
			double dy = p.anchor.getY() - pos.getY();
			double dz = p.anchor.getZ() - pos.getZ();
			double distSq = dx * dx + dy * dy + dz * dz;
			if (distSq <= mergeSq && distSq < bestDist) {
				bestDist = distSq;
				best = p;
			}
		}
		return best;
	}

	private void playAlert(int density) {
		if (mc.player == null || mc.world == null) return;
		float pitch = 0.65f + 0.004f * Math.min(density, 100);
		mc.world.playSound(
			mc.player,
			mc.player.getX(),
			mc.player.getY(),
			mc.player.getZ(),
			SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
			SoundCategory.PLAYERS,
			0.55f,
			pitch
		);
	}

	private String dimensionId() {
		if (mc.world == null) return "unknown";
		return mc.world.getRegistryKey().getValue().toString();
	}

	@Override
	public String getInfoString() {
		if (nearValid) return DensityBand.info(nearDensity);
		return null;
	}

	@Override
	public NbtCompound toTag() {
		NbtCompound tag = super.toTag();
		if (tag == null) tag = new NbtCompound();
		NbtList list = new NbtList();
		for (Pocket p : pockets) {
			list.add(p.toNbt());
		}
		tag.put("pockets", list);
		return tag;
	}

	@Override
	public Module fromTag(NbtCompound tag) {
		super.fromTag(tag);
		pockets.clear();
		if (tag.contains("pockets", NbtElement.LIST_TYPE)) {
			NbtList list = tag.getList("pockets", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < list.size(); i++) {
				pockets.add(Pocket.fromNbt(list.getCompound(i)));
			}
		}
		return this;
	}

	private static final class Pocket {
		final String dimension;
		BlockPos anchor;
		int density;
		int ore;
		BlockPos waypointPos;
		boolean waypointSaved;
		boolean confirmed;
		boolean caveMarkersDirty = true;
		final List<BlockPos> caveMarkers = new ArrayList<>();

		Pocket(String dimension) {
			this.dimension = dimension;
		}

		long anchorKey() {
			return anchor == null ? 0L : anchor.asLong();
		}

		NbtCompound toNbt() {
			NbtCompound tag = new NbtCompound();
			tag.putString("dim", dimension);
			tag.putBoolean("confirmed", confirmed);
			tag.putBoolean("wp", waypointSaved);
			tag.putInt("den", density);
			tag.putInt("ore", ore);
			if (anchor != null) tag.putLong("anchor", anchor.asLong());
			if (waypointPos != null) tag.putLong("wpos", waypointPos.asLong());
			return tag;
		}

		static Pocket fromNbt(NbtCompound tag) {
			Pocket p = new Pocket(tag.getString("dim"));
			p.confirmed = tag.getBoolean("confirmed");
			p.waypointSaved = tag.getBoolean("wp");
			p.density = tag.getInt("den");
			p.ore = tag.getInt("ore");
			if (tag.contains("anchor")) p.anchor = BlockPos.fromLong(tag.getLong("anchor"));
			if (tag.contains("wpos")) p.waypointPos = BlockPos.fromLong(tag.getLong("wpos"));
			p.caveMarkersDirty = true;
			return p;
		}
	}
}
