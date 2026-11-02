package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.util.BreezeMatcher;
import dev.greencorps.util.VaultMatcher;
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
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class BreezeFinder extends Module {
	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgRender = settings.createGroup("Render");

	private final Setting<Integer> waypointThreshold = sgGeneral.add(new IntSetting.Builder()
		.name("waypoint-threshold")
		.description("Minimum distinct breeze spawners in a chamber before a Xaero waypoint is saved.")
		.defaultValue(4)
		.min(1)
		.sliderRange(1, 16)
		.build()
	);

	private final Setting<Integer> mergeDistance = sgGeneral.add(new IntSetting.Builder()
		.name("merge-distance")
		.description("Horizontal distance (blocks) for grouping breezes and vaults into one trial chamber.")
		.defaultValue(96)
		.min(16)
		.sliderRange(32, 192)
		.build()
	);

	private final Setting<Boolean> chatAlerts = sgGeneral.add(new BoolSetting.Builder()
		.name("chat-alerts")
		.description("Client chat once per newly found breeze spawner or ominous trial vault.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> alertSound = sgGeneral.add(new BoolSetting.Builder()
		.name("alert-sound")
		.description("Play a client sound with each new find chat alert.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> esp = sgRender.add(new BoolSetting.Builder()
		.name("esp")
		.description("Draw StorageESP-style boxes on live breeze spawners and ominous vaults.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> spawnVolume = sgRender.add(new BoolSetting.Builder()
		.name("spawn-volume")
		.description("Draw a 7x7x5 lines-only box around each live breeze spawner (possible spawn space).")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> espRegularVaults = sgRender.add(new BoolSetting.Builder()
		.name("esp-regular-vaults")
		.description("Also ESP regular (copper) trial vaults. Off by default — they are common.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Integer> spawnVolumeExtra = sgRender.add(new IntSetting.Builder()
		.name("spawn-volume-extra")
		.description("Extra horizontal blocks beyond Meteor render distance for spawn-volume wireframes.")
		.defaultValue(32)
		.min(0)
		.max(64)
		.sliderRange(0, 64)
		.build()
	);

	private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
		.name("shape-mode")
		.description("How the ESP shapes are rendered.")
		.defaultValue(ShapeMode.Both)
		.build()
	);

	private final Setting<Integer> fillOpacity = sgRender.add(new IntSetting.Builder()
		.name("fill-opacity")
		.description("The opacity of the shape fill.")
		.defaultValue(50)
		.range(0, 255)
		.sliderMax(255)
		.build()
	);

	private final Setting<SettingColor> breezeColor = sgRender.add(new ColorSetting.Builder()
		.name("breeze-color")
		.description("ESP color for breeze trial spawners.")
		.defaultValue(new SettingColor(0, 240, 255, 255))
		.build()
	);

	private final Setting<SettingColor> vaultColor = sgRender.add(new ColorSetting.Builder()
		.name("vault-color")
		.description("ESP color for ominous (expensive) trial vaults.")
		.defaultValue(new SettingColor(0, 44, 245, 255))
		.build()
	);

	private final Setting<SettingColor> regularVaultColor = sgRender.add(new ColorSetting.Builder()
		.name("regular-vault-color")
		.description("ESP color for regular copper trial vaults.")
		.defaultValue(new SettingColor(255, 140, 60, 255))
		.build()
	);

	private final Color lineColor = new Color();
	private final Color sideColor = new Color();

	private final Set<Long> knownSpawners = new HashSet<>();
	private final Set<Long> knownVaults = new HashSet<>();
	private final Set<Long> liveSpawners = new HashSet<>();
	private final Set<Long> liveVaults = new HashSet<>();
	private final Set<Long> liveRegularVaults = new HashSet<>();
	private final List<Chamber> chambers = new ArrayList<>();

	private int pruneTicker;
	private Integer lastThreshold;

	public BreezeFinder() {
		super(GreenCorps.CATEGORY, "breeze-finder", "Finds breeze trial spawners and ominous vaults in loaded chunks; one Xaero waypoint per chamber at the breeze threshold.");
	}

	public static BreezeFinder get() {
		return meteordevelopment.meteorclient.systems.modules.Modules.get().get(BreezeFinder.class);
	}

	public int liveBreezeCount() {
		return isActive() ? liveSpawners.size() : 0;
	}

	public int liveVaultCount() {
		return isActive() ? liveVaults.size() : 0;
	}

	/** @deprecated use {@link #liveBreezeCount()} */
	@Deprecated
	public int liveCount() {
		return liveBreezeCount();
	}

	@Override
	public void onActivate() {
		lastThreshold = waypointThreshold.get();
		sanitizePersistedVaults();
		int retinted = XaeroWaypoints.retintTrialChamberWaypoints();
		if (retinted > 0) {
			info("Retinted " + retinted + " trial chamber waypoint(s) to gold / TC{breezes}");
		}
		scanAllLoadedChunks();
		syncWaypointsToThreshold();
	}

	@Override
	public void onDeactivate() {
		liveSpawners.clear();
		liveVaults.clear();
		liveRegularVaults.clear();
	}

	/** Drop persisted regular-vault keys left over from the inverted matcher. */
	private void sanitizePersistedVaults() {
		if (mc.world == null) {
			// Clear vault memberships; rescan will repopulate ominous only when chunks load.
			for (Chamber c : chambers) {
				c.vaults.clear();
			}
			knownVaults.clear();
			return;
		}
		Iterator<Long> kit = knownVaults.iterator();
		while (kit.hasNext()) {
			BlockPos pos = BlockPos.fromLong(kit.next());
			ChunkPos cp = new ChunkPos(pos);
			if (!mc.world.isChunkLoaded(cp.x, cp.z)) continue;
			if (!VaultMatcher.isOminousVault(mc.world, pos)) kit.remove();
		}
		for (Chamber c : chambers) {
			Iterator<Long> it = c.vaults.iterator();
			while (it.hasNext()) {
				BlockPos pos = BlockPos.fromLong(it.next());
				ChunkPos cp = new ChunkPos(pos);
				if (!mc.world.isChunkLoaded(cp.x, cp.z)) {
					it.remove();
					continue;
				}
				if (!VaultMatcher.isOminousVault(mc.world, pos)) it.remove();
			}
		}
	}

	@EventHandler
	private void onChunkData(ChunkDataEvent event) {
		if (mc.world == null) return;
		scanChunk(event.chunk());
	}

	@EventHandler
	private void onRender3D(Render3DEvent event) {
		if (!esp.get() || mc.player == null) return;

		for (long key : liveSpawners) {
			BlockPos pos = BlockPos.fromLong(key);
			boolean inBoxRange = EntityUtils.isInRenderDistance(pos);
			boolean inVolumeRange = inBoxRange || inSpawnVolumeExtra(pos);
			if (inBoxRange) {
				drawBox(event, pos, breezeColor.get());
			}
			if (spawnVolume.get() && inVolumeRange) {
				drawSpawnVolume(event, pos, breezeColor.get());
			}
		}
		for (long key : liveVaults) {
			BlockPos pos = BlockPos.fromLong(key);
			if (!EntityUtils.isInRenderDistance(pos)) continue;
			drawBox(event, pos, vaultColor.get());
		}
		if (espRegularVaults.get()) {
			for (long key : liveRegularVaults) {
				BlockPos pos = BlockPos.fromLong(key);
				if (!EntityUtils.isInRenderDistance(pos)) continue;
				drawBox(event, pos, regularVaultColor.get());
			}
		}
	}

	/** Spawn volumes may extend slightly past Meteor render distance. */
	private boolean inSpawnVolumeExtra(BlockPos pos) {
		if (mc.player == null) return false;
		int extra = spawnVolumeExtra.get();
		if (extra <= 0) return false;
		int vd = mc.options.getViewDistance().getValue();
		double limit = (vd + 1) * 16.0 + extra;
		double dx = Math.abs(mc.player.getX() - (pos.getX() + 0.5));
		double dz = Math.abs(mc.player.getZ() - (pos.getZ() + 0.5));
		return dx <= limit && dz <= limit;
	}

	private void drawBox(Render3DEvent event, BlockPos pos, SettingColor color) {
		lineColor.set(color);
		sideColor.set(color);
		sideColor.a = fillOpacity.get();
		event.renderer.box(pos, sideColor, lineColor, shapeMode.get(), 0);
	}

	/** 7x7x5 wireframe with spawner centered on the 2nd layer (1-based). */
	private void drawSpawnVolume(Render3DEvent event, BlockPos spawner, SettingColor color) {
		lineColor.set(color);
		sideColor.set(color);
		sideColor.a = 0;
		double minX = spawner.getX() - 3;
		double minY = spawner.getY() - 1;
		double minZ = spawner.getZ() - 3;
		double maxX = spawner.getX() + 4;
		double maxY = spawner.getY() + 4;
		double maxZ = spawner.getZ() + 4;
		event.renderer.box(minX, minY, minZ, maxX, maxY, maxZ, sideColor, lineColor, ShapeMode.Lines, 0);
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		if (mc.world == null) return;

		int threshold = waypointThreshold.get();
		if (lastThreshold == null || lastThreshold != threshold) {
			lastThreshold = threshold;
			syncWaypointsToThreshold();
		}

		if (++pruneTicker < 40) return;
		pruneTicker = 0;
		pruneLiveSet(liveSpawners);
		pruneLiveSet(liveVaults);
		pruneLiveSet(liveRegularVaults);
	}

	private void scanAllLoadedChunks() {
		if (mc.world == null) return;
		liveRegularVaults.clear();
		for (WorldChunk chunk : getLoadedChunks()) {
			scanChunk(chunk);
		}
		pruneLiveSet(liveSpawners);
		pruneLiveSet(liveVaults);
		pruneLiveSet(liveRegularVaults);
	}

	private void scanChunk(WorldChunk chunk) {
		if (mc.world == null || chunk == null) return;
		World world = mc.world;

		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (BreezeMatcher.isBreezeTrialSpawner(world, be)) {
				onBreezeFound(be.getPos().toImmutable());
			} else if (VaultMatcher.isOminousVault(world, be)) {
				onVaultFound(be.getPos().toImmutable());
			} else if (VaultMatcher.isRegularVault(world, be)) {
				liveRegularVaults.add(be.getPos().asLong());
			}
		}
	}

	private void onBreezeFound(BlockPos pos) {
		long key = pos.asLong();
		liveSpawners.add(key);

		boolean isNew = knownSpawners.add(key);
		if (!isNew) {
			Chamber chamber = findChamberContaining(pos);
			if (chamber != null) {
				chamber.ensureBreeze(pos);
				updateWaypointIfNeeded(chamber, false);
			}
			return;
		}

		Chamber chamber = findOrCreateChamber(pos);
		chamber.ensureBreeze(pos);
		alertAndSync(chamber, pos, true);
	}

	private void onVaultFound(BlockPos pos) {
		long key = pos.asLong();
		liveVaults.add(key);

		boolean isNew = knownVaults.add(key);
		if (!isNew) {
			Chamber chamber = findChamberContaining(pos);
			if (chamber != null) {
				chamber.ensureVault(pos);
				updateWaypointIfNeeded(chamber, false);
			}
			return;
		}

		Chamber chamber = findOrCreateChamber(pos);
		chamber.ensureVault(pos);
		alertAndSync(chamber, pos, false);
	}

	private void alertAndSync(Chamber chamber, BlockPos pos, boolean breeze) {
		int b = chamber.breezes.size();
		int tc = chamber.vaults.size();
		boolean hadWp = chamber.waypointSaved;
		updateWaypointIfNeeded(chamber, true);
		boolean crossed = !hadWp && chamber.waypointSaved;

		if (chatAlerts.get()) {
			String totals = "(" + b + "xB/" + tc + "xTC)";
			StringBuilder msg = new StringBuilder();
			if (breeze) {
				msg.append("Breeze spawner ");
			} else {
				msg.append("Ominous vault ");
			}
			msg.append(pos.getX()).append(' ')
				.append(pos.getY()).append(' ')
				.append(pos.getZ())
				.append(' ').append(totals);
			if (chamber.waypointSaved || crossed) {
				msg.append(" — ").append(XaeroWaypoints.chamberName(b, tc));
			}
			info(msg.toString());
		}

		if (alertSound.get()) {
			playAlert(breeze ? b : tc);
		}
	}

	private void updateWaypointIfNeeded(Chamber chamber, boolean fromNewFind) {
		chamber.raisePeaks();
		int b = chamber.peakBreezes;
		int tc = chamber.peakVaults;

		// Merge counts already written on an adopted/nearby pin so we never rename downward.
		if (chamber.waypointPos != null || fromNewFind) {
			XaeroWaypoints.ChamberCounts pin = XaeroWaypoints.peekNearbyChamberCounts(
				chamber.waypointPos != null ? chamber.waypointPos : chamber.centroid(),
				mergeDistance.get()
			);
			if (pin != null) {
				b = Math.max(b, pin.breezes);
				tc = Math.max(tc, pin.vaults);
				chamber.peakBreezes = Math.max(chamber.peakBreezes, pin.breezes);
				chamber.peakVaults = Math.max(chamber.peakVaults, pin.vaults);
			}
		}

		int threshold = waypointThreshold.get();

		if (b < threshold) {
			if (chamber.waypointSaved && chamber.waypointPos != null && !chamber.waypointAdopted) {
				XaeroWaypoints.removeWaypointAt(chamber.waypointPos);
			}
			if (chamber.waypointSaved) {
				chamber.waypointSaved = false;
				chamber.waypointPos = null;
				chamber.waypointAdopted = false;
			}
			return;
		}

		BlockPos centroid = chamber.centroid();
		XaeroWaypoints.ChamberUpsert written = XaeroWaypoints.upsertChamberWaypoint(
			chamber.waypointPos,
			centroid,
			b,
			tc,
			mergeDistance.get()
		);
		if (written != null) {
			chamber.waypointPos = written.pos;
			chamber.waypointSaved = true;
			if (written.adopted) {
				chamber.waypointAdopted = true;
			}
		} else if (fromNewFind && XaeroWaypoints.isAvailable()) {
			chamber.waypointSaved = false;
		}
	}

	private void syncWaypointsToThreshold() {
		for (Chamber chamber : chambers) {
			updateWaypointIfNeeded(chamber, false);
		}
	}

	private void pruneLiveSet(Set<Long> set) {
		if (mc.world == null) {
			set.clear();
			return;
		}
		Iterator<Long> it = set.iterator();
		while (it.hasNext()) {
			BlockPos pos = BlockPos.fromLong(it.next());
			ChunkPos cp = new ChunkPos(pos);
			if (!mc.world.isChunkLoaded(cp.x, cp.z)) {
				it.remove();
			}
		}
	}

	private Chamber findOrCreateChamber(BlockPos pos) {
		Chamber chamber = findNearestChamber(pos);
		if (chamber == null) {
			chamber = new Chamber(dimensionId());
			chambers.add(chamber);
		}
		return chamber;
	}

	private Chamber findChamberContaining(BlockPos pos) {
		long key = pos.asLong();
		for (Chamber c : chambers) {
			if (!c.dimension.equals(dimensionId())) continue;
			if (c.breezes.contains(key) || c.vaults.contains(key)) return c;
		}
		return null;
	}

	private Chamber findNearestChamber(BlockPos pos) {
		int merge = mergeDistance.get();
		double mergeSq = (double) merge * merge;
		Chamber best = null;
		double bestDist = Double.MAX_VALUE;

		for (Chamber c : chambers) {
			if (!c.dimension.equals(dimensionId())) continue;
			double distSq = c.minHorizDistSq(pos);
			if (distSq <= mergeSq && distSq < bestDist) {
				bestDist = distSq;
				best = c;
			}
		}
		return best;
	}

	private void playAlert(int countHint) {
		if (mc.player == null || mc.world == null) return;
		float pitch = 0.75f + 0.08f * Math.min(countHint, 12);
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

	private List<WorldChunk> getLoadedChunks() {
		List<WorldChunk> out = new ArrayList<>();
		if (mc.world == null || mc.player == null) return out;
		int rd = mc.options.getViewDistance().getValue();
		ChunkPos center = mc.player.getChunkPos();
		for (int x = center.x - rd; x <= center.x + rd; x++) {
			for (int z = center.z - rd; z <= center.z + rd; z++) {
				if (!mc.world.isChunkLoaded(x, z)) continue;
				WorldChunk chunk = mc.world.getChunk(x, z);
				if (chunk != null) out.add(chunk);
			}
		}
		return out;
	}

	@Override
	public String getInfoString() {
		return liveSpawners.size() + "/" + liveVaults.size();
	}

	@Override
	public NbtCompound toTag() {
		NbtCompound tag = super.toTag();
		if (tag == null) tag = new NbtCompound();

		tag.put("knownSpawners", longsToList(knownSpawners));
		tag.put("knownVaults", longsToList(knownVaults));

		NbtList chamberTags = new NbtList();
		for (Chamber c : chambers) {
			chamberTags.add(c.toNbt());
		}
		tag.put("chambers", chamberTags);
		return tag;
	}

	@Override
	public Module fromTag(NbtCompound tag) {
		super.fromTag(tag);
		knownSpawners.clear();
		knownVaults.clear();
		chambers.clear();

		knownSpawners.addAll(listToLongs(tag, "knownSpawners"));
		knownVaults.addAll(listToLongs(tag, "knownVaults"));

		if (tag.contains("chambers", NbtElement.LIST_TYPE)) {
			NbtList list = tag.getList("chambers", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < list.size(); i++) {
				chambers.add(Chamber.fromNbt(list.getCompound(i)));
			}
		}
		return this;
	}

	private static NbtList longsToList(Set<Long> set) {
		NbtList list = new NbtList();
		for (long k : set) {
			NbtCompound e = new NbtCompound();
			e.putLong("p", k);
			list.add(e);
		}
		return list;
	}

	private static Set<Long> listToLongs(NbtCompound tag, String key) {
		Set<Long> out = new HashSet<>();
		if (!tag.contains(key, NbtElement.LIST_TYPE)) return out;
		NbtList list = tag.getList(key, NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			out.add(list.getCompound(i).getLong("p"));
		}
		return out;
	}

	private static final class Chamber {
		final String dimension;
		final Set<Long> breezes = new HashSet<>();
		final Set<Long> vaults = new HashSet<>();
		int peakBreezes;
		int peakVaults;
		BlockPos waypointPos;
		boolean waypointSaved;
		boolean waypointAdopted;

		Chamber(String dimension) {
			this.dimension = dimension;
		}

		void ensureBreeze(BlockPos pos) {
			breezes.add(pos.asLong());
			raisePeaks();
		}

		void ensureVault(BlockPos pos) {
			vaults.add(pos.asLong());
			raisePeaks();
		}

		void raisePeaks() {
			peakBreezes = Math.max(peakBreezes, breezes.size());
			peakVaults = Math.max(peakVaults, vaults.size());
		}

		double minHorizDistSq(BlockPos pos) {
			double best = Double.MAX_VALUE;
			best = Math.min(best, minHorizDistSq(pos, breezes));
			best = Math.min(best, minHorizDistSq(pos, vaults));
			return best;
		}

		private static double minHorizDistSq(BlockPos pos, Set<Long> keys) {
			double best = Double.MAX_VALUE;
			for (long k : keys) {
				BlockPos member = BlockPos.fromLong(k);
				double dx = member.getX() - pos.getX();
				double dz = member.getZ() - pos.getZ();
				double distSq = dx * dx + dz * dz;
				if (distSq < best) best = distSq;
			}
			return best;
		}

		BlockPos centroid() {
			long sx = 0, sy = 0, sz = 0;
			int n = 0;
			for (long k : breezes) {
				BlockPos p = BlockPos.fromLong(k);
				sx += p.getX();
				sy += p.getY();
				sz += p.getZ();
				n++;
			}
			for (long k : vaults) {
				BlockPos p = BlockPos.fromLong(k);
				sx += p.getX();
				sy += p.getY();
				sz += p.getZ();
				n++;
			}
			if (n == 0) return BlockPos.ORIGIN;
			return new BlockPos((int) Math.round(sx / (double) n), (int) Math.round(sy / (double) n), (int) Math.round(sz / (double) n));
		}

		NbtCompound toNbt() {
			NbtCompound tag = new NbtCompound();
			tag.putString("dim", dimension);
			tag.putBoolean("wp", waypointSaved);
			tag.putBoolean("adopted", waypointAdopted);
			tag.putInt("peakB", peakBreezes);
			tag.putInt("peakV", peakVaults);
			if (waypointPos != null) {
				tag.putLong("wpos", waypointPos.asLong());
			}
			tag.put("members", longsToList(breezes));
			tag.put("vaults", longsToList(vaults));
			return tag;
		}

		static Chamber fromNbt(NbtCompound tag) {
			Chamber c = new Chamber(tag.getString("dim"));
			c.waypointSaved = tag.getBoolean("wp");
			c.waypointAdopted = tag.getBoolean("adopted");
			if (tag.contains("wpos")) {
				c.waypointPos = BlockPos.fromLong(tag.getLong("wpos"));
			}
			c.breezes.addAll(listToLongs(tag, "members"));
			c.vaults.addAll(listToLongs(tag, "vaults"));
			c.peakBreezes = tag.contains("peakB") ? tag.getInt("peakB") : c.breezes.size();
			c.peakVaults = tag.contains("peakV") ? tag.getInt("peakV") : c.vaults.size();
			c.raisePeaks();
			return c;
		}

		private static NbtList longsToList(Set<Long> set) {
			NbtList list = new NbtList();
			for (long k : set) {
				NbtCompound e = new NbtCompound();
				e.putLong("p", k);
				list.add(e);
			}
			return list;
		}

		private static Set<Long> listToLongs(NbtCompound tag, String key) {
			Set<Long> out = new HashSet<>();
			if (!tag.contains(key, NbtElement.LIST_TYPE)) return out;
			NbtList list = tag.getList(key, NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < list.size(); i++) {
				out.add(list.getCompound(i).getLong("p"));
			}
			return out;
		}
	}
}
