package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.util.OutpostMatcher;
import dev.greencorps.util.OutpostTerrainScore;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OutpostFinder extends Module {
	private final SettingGroup sgGeneral = settings.getDefaultGroup();

	private final Setting<Integer> waypointThreshold = sgGeneral.add(new IntSetting.Builder()
		.name("waypoint-threshold")
		.description("Minimum flatness score (0-100) required before a Xaero waypoint is saved.")
		.defaultValue(0)
		.min(0)
		.max(100)
		.sliderRange(0, 100)
		.build()
	);

	private final Setting<Integer> mergeDistance = sgGeneral.add(new IntSetting.Builder()
		.name("merge-distance")
		.description("Horizontal distance for grouping ominous banners into one outpost.")
		.defaultValue(48)
		.min(16)
		.sliderRange(24, 96)
		.build()
	);

	private final Setting<Integer> sampleStep = sgGeneral.add(new IntSetting.Builder()
		.name("sample-step")
		.description("Block step when sampling terrain flatness around an outpost.")
		.defaultValue(4)
		.min(2)
		.max(8)
		.sliderRange(2, 8)
		.build()
	);

	private final Setting<Boolean> chatAlerts = sgGeneral.add(new BoolSetting.Builder()
		.name("chat-alerts")
		.description("Client chat once when an outpost is first confirmed.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> alertSound = sgGeneral.add(new BoolSetting.Builder()
		.name("alert-sound")
		.description("Play a client sound when an outpost is first confirmed.")
		.defaultValue(true)
		.build()
	);

	private final Set<Long> knownBanners = new HashSet<>();
	private final Set<Long> liveOutposts = new HashSet<>();
	private final List<Outpost> outposts = new ArrayList<>();

	private int pruneTicker;
	private Integer lastThreshold;

	public OutpostFinder() {
		super(GreenCorps.CATEGORY, "outpost-finder", "Finds pillager outposts, scores surrounding flatness, and saves one Xaero waypoint per outpost.");
	}

	public static OutpostFinder get() {
		return meteordevelopment.meteorclient.systems.modules.Modules.get().get(OutpostFinder.class);
	}

	public int liveCount() {
		return isActive() ? liveOutposts.size() : 0;
	}

	@Override
	public void onActivate() {
		lastThreshold = waypointThreshold.get();
		scanAllLoadedChunks();
		for (Outpost o : outposts) {
			if (o.confirmed) {
				rescore(o);
				updateWaypoint(o);
			}
		}
	}

	@Override
	public void onDeactivate() {
		liveOutposts.clear();
	}

	@EventHandler
	private void onChunkData(ChunkDataEvent event) {
		if (mc.world == null) return;
		scanChunk(event.chunk());
		rescoreNearby(event.chunk().getPos());
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		if (mc.world == null) return;

		int threshold = waypointThreshold.get();
		if (lastThreshold == null || lastThreshold != threshold) {
			lastThreshold = threshold;
			for (Outpost o : outposts) {
				if (o.confirmed) updateWaypoint(o);
			}
		}

		if (++pruneTicker < 40) return;
		pruneTicker = 0;
		pruneLive();
	}

	private void scanAllLoadedChunks() {
		if (mc.world == null || mc.player == null) return;
		int rd = mc.options.getViewDistance().getValue();
		ChunkPos center = mc.player.getChunkPos();
		for (int x = center.x - rd; x <= center.x + rd; x++) {
			for (int z = center.z - rd; z <= center.z + rd; z++) {
				if (!mc.world.isChunkLoaded(x, z)) continue;
				WorldChunk chunk = mc.world.getChunk(x, z);
				if (chunk != null) scanChunk(chunk);
			}
		}
		pruneLive();
	}

	private void scanChunk(WorldChunk chunk) {
		if (mc.world == null || chunk == null) return;
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (!OutpostMatcher.isOminousBanner(mc.world, be)) continue;
			onBannerFound(be.getPos().toImmutable());
		}
	}

	private void onBannerFound(BlockPos pos) {
		long key = pos.asLong();
		knownBanners.add(key);

		Outpost outpost = findContaining(pos);
		if (outpost == null) {
			outpost = findNearest(pos);
		}
		if (outpost == null) {
			outpost = new Outpost(dimensionId());
			outposts.add(outpost);
		}
		outpost.banners.add(key);

		boolean wasConfirmed = outpost.confirmed;
		if (outpost.banners.size() >= OutpostMatcher.MIN_BANNERS_TO_CONFIRM) {
			outpost.confirmed = true;
			outpost.refreshAnchor();
			liveOutposts.add(outpost.anchorKey());
			rescore(outpost);
			updateWaypoint(outpost);

			if (!wasConfirmed) {
				alertNew(outpost);
			}
		}
	}

	private void alertNew(Outpost outpost) {
		BlockPos anchor = outpost.anchor;
		if (chatAlerts.get() && anchor != null) {
			StringBuilder msg = new StringBuilder();
			msg.append("Outpost ")
				.append(anchor.getX()).append(' ')
				.append(anchor.getY()).append(' ')
				.append(anchor.getZ())
				.append(" (").append(outpost.flatness).append(" FLT)");
			if (outpost.waypointSaved) {
				msg.append(" — ").append(XaeroWaypoints.outpostName(outpost.flatness));
			}
			info(msg.toString());
		}
		if (alertSound.get()) {
			playAlert(outpost.flatness);
		}
	}

	private void rescoreNearby(ChunkPos chunkPos) {
		int cx = (chunkPos.x << 4) + 8;
		int cz = (chunkPos.z << 4) + 8;
		for (Outpost o : outposts) {
			if (!o.confirmed || o.anchor == null) continue;
			if (!o.dimension.equals(dimensionId())) continue;
			double dx = o.anchor.getX() - cx;
			double dz = o.anchor.getZ() - cz;
			if (dx * dx + dz * dz > 80 * 80) continue;
			int before = o.flatness;
			rescore(o);
			if (o.flatness != before) {
				updateWaypoint(o);
			}
		}
	}

	private void rescore(Outpost outpost) {
		if (outpost.anchor == null || mc.world == null) return;
		outpost.flatness = OutpostTerrainScore.score(
			mc.world,
			outpost.anchor,
			outpost.topFloorY,
			sampleStep.get()
		);
	}

	private void updateWaypoint(Outpost outpost) {
		if (!outpost.confirmed || outpost.anchor == null) return;
		int threshold = waypointThreshold.get();

		if (outpost.flatness < threshold) {
			if (outpost.waypointSaved && outpost.waypointPos != null) {
				XaeroWaypoints.removeWaypointAt(outpost.waypointPos);
				outpost.waypointSaved = false;
				outpost.waypointPos = null;
			}
			return;
		}

		BlockPos written = XaeroWaypoints.upsertOutpostWaypoint(
			outpost.waypointPos,
			outpost.anchor,
			outpost.flatness
		);
		if (written != null) {
			outpost.waypointPos = written;
			outpost.waypointSaved = true;
		}
	}

	private void pruneLive() {
		if (mc.world == null) {
			liveOutposts.clear();
			return;
		}
		liveOutposts.clear();
		for (Outpost o : outposts) {
			if (!o.confirmed || o.anchor == null) continue;
			if (!o.dimension.equals(dimensionId())) continue;
			ChunkPos cp = new ChunkPos(o.anchor);
			if (mc.world.isChunkLoaded(cp.x, cp.z)) {
				liveOutposts.add(o.anchorKey());
			}
		}
	}

	private Outpost findContaining(BlockPos pos) {
		for (Outpost o : outposts) {
			if (!o.dimension.equals(dimensionId())) continue;
			for (long k : o.banners) {
				if (OutpostMatcher.sameCluster(pos, BlockPos.fromLong(k))) return o;
			}
		}
		return null;
	}

	private Outpost findNearest(BlockPos pos) {
		int merge = mergeDistance.get();
		double mergeSq = (double) merge * merge;
		Outpost best = null;
		double bestDist = Double.MAX_VALUE;

		for (Outpost o : outposts) {
			if (!o.dimension.equals(dimensionId())) continue;
			for (long k : o.banners) {
				BlockPos banner = BlockPos.fromLong(k);
				double dx = banner.getX() - pos.getX();
				double dz = banner.getZ() - pos.getZ();
				double distSq = dx * dx + dz * dz;
				if (distSq <= mergeSq && OutpostMatcher.sameCluster(pos, banner) && distSq < bestDist) {
					bestDist = distSq;
					best = o;
				}
			}
		}
		return best;
	}

	private void playAlert(int flatness) {
		if (mc.player == null || mc.world == null) return;
		float pitch = 0.7f + 0.004f * Math.min(flatness, 100);
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
		return String.valueOf(liveOutposts.size());
	}

	@Override
	public NbtCompound toTag() {
		NbtCompound tag = super.toTag();
		if (tag == null) tag = new NbtCompound();

		NbtList known = new NbtList();
		for (long k : knownBanners) {
			NbtCompound e = new NbtCompound();
			e.putLong("p", k);
			known.add(e);
		}
		tag.put("knownBanners", known);

		NbtList list = new NbtList();
		for (Outpost o : outposts) {
			list.add(o.toNbt());
		}
		tag.put("outposts", list);
		return tag;
	}

	@Override
	public Module fromTag(NbtCompound tag) {
		super.fromTag(tag);
		knownBanners.clear();
		outposts.clear();

		if (tag.contains("knownBanners", NbtElement.LIST_TYPE)) {
			NbtList known = tag.getList("knownBanners", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < known.size(); i++) {
				knownBanners.add(known.getCompound(i).getLong("p"));
			}
		}
		if (tag.contains("outposts", NbtElement.LIST_TYPE)) {
			NbtList list = tag.getList("outposts", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < list.size(); i++) {
				outposts.add(Outpost.fromNbt(list.getCompound(i)));
			}
		}
		return this;
	}

	private static final class Outpost {
		final String dimension;
		final Set<Long> banners = new HashSet<>();
		BlockPos anchor;
		int topFloorY;
		int flatness;
		BlockPos waypointPos;
		boolean waypointSaved;
		boolean confirmed;

		Outpost(String dimension) {
			this.dimension = dimension;
		}

		long anchorKey() {
			return anchor == null ? 0L : anchor.asLong();
		}

		void refreshAnchor() {
			if (banners.isEmpty()) {
				anchor = null;
				return;
			}
			long sx = 0, sz = 0;
			int maxY = Integer.MIN_VALUE;
			int n = 0;
			for (long k : banners) {
				BlockPos p = BlockPos.fromLong(k);
				sx += p.getX();
				sz += p.getZ();
				if (p.getY() > maxY) maxY = p.getY();
				n++;
			}
			topFloorY = maxY;
			anchor = new BlockPos((int) Math.round(sx / (double) n), maxY, (int) Math.round(sz / (double) n));
		}

		NbtCompound toNbt() {
			NbtCompound tag = new NbtCompound();
			tag.putString("dim", dimension);
			tag.putBoolean("confirmed", confirmed);
			tag.putBoolean("wp", waypointSaved);
			tag.putInt("flat", flatness);
			tag.putInt("topY", topFloorY);
			if (anchor != null) tag.putLong("anchor", anchor.asLong());
			if (waypointPos != null) tag.putLong("wpos", waypointPos.asLong());
			NbtList bans = new NbtList();
			for (long k : banners) {
				NbtCompound e = new NbtCompound();
				e.putLong("p", k);
				bans.add(e);
			}
			tag.put("banners", bans);
			return tag;
		}

		static Outpost fromNbt(NbtCompound tag) {
			Outpost o = new Outpost(tag.getString("dim"));
			o.confirmed = tag.getBoolean("confirmed");
			o.waypointSaved = tag.getBoolean("wp");
			o.flatness = tag.getInt("flat");
			o.topFloorY = tag.getInt("topY");
			if (tag.contains("anchor")) o.anchor = BlockPos.fromLong(tag.getLong("anchor"));
			if (tag.contains("wpos")) o.waypointPos = BlockPos.fromLong(tag.getLong("wpos"));
			if (tag.contains("banners", NbtElement.LIST_TYPE)) {
				NbtList bans = tag.getList("banners", NbtElement.COMPOUND_TYPE);
				for (int i = 0; i < bans.size(); i++) {
					o.banners.add(bans.getCompound(i).getLong("p"));
				}
			}
			return o;
		}
	}
}
