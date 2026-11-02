package dev.greencorps.stasis;

import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Edge-driven chamber geometry. Prefer {@link #scanSection} (budgeted) on chunk load;
 * {@link #discoverNear} on finishing block updates; validate known roots only otherwise.
 */
public final class ChamberScanner {
	/** Probe walk above soul sand (taller than ready max so elevators can be rejected). */
	private static final int WATER_PROBE_MAX = 16;
	/** Ready stasis columns: typically 5–7; reject shorter UC and taller elevators. */
	private static final int WATER_READY_MIN = 3;
	private static final int WATER_READY_MAX = 7;
	private static final int MAX_HONEY_HEIGHT = 8;
	/** Hard cap new roots discovered from a single chunk load. */
	public static final int MAX_ROOTS_PER_CHUNK = 16;
	private static final int SIGN_PROBE_CAP = 4;

	public record Found(
		int seatX, int seatY, int seatZ,
		int rootX, int rootY, int rootZ,
		String kind
	) {}

	private ChamberScanner() {}

	/** Probe whether a pearl at (x,y,z) sits in smart chamber geometry. */
	public static StasisGeometry.Match matchAt(ClientWorld world, double x, double y, double z) {
		if (world == null) return StasisGeometry.Match.NONE;
		BlockPos feet = BlockPos.ofFloored(x, y, z);
		if (isValidWaterSeat(world, feet)) return StasisGeometry.Match.WATER;
		if (isValidHoneySeat(world, feet)) return StasisGeometry.Match.HONEY;
		return StasisGeometry.Match.NONE;
	}

	/**
	 * Seat-local discover around a block change (soul sand / bubble / honey / piston / sign).
	 * Never walks a full chunk.
	 */
	public static List<Found> discoverNear(ClientWorld world, BlockPos hint) {
		List<Found> out = new ArrayList<>();
		if (world == null || hint == null) return out;
		BlockState state = world.getBlockState(hint);
		Block block = state.getBlock();

		if (block instanceof AbstractSignBlock) {
			probeBox(world, hint, 1, 2, SIGN_PROBE_CAP, out);
			return out;
		}

		if (block == Blocks.SOUL_SAND) {
			Found w = tryWaterColumn(world, hint);
			if (w != null) out.add(w);
			return out;
		}
		if (isWaterSourceOrBubble(world, hint)) {
			BlockPos sand = findSoulSandBelow(world, hint);
			if (sand != null) {
				Found w = tryWaterColumn(world, sand);
				if (w != null) out.add(w);
			}
			return out;
		}
		if (block == Blocks.HONEY_BLOCK) {
			BlockPos piston = findUpwardPistonUnderHoney(world, hint);
			if (piston != null) {
				Found h = tryHoneyColumn(world, piston);
				if (h != null) out.add(h);
			}
			return out;
		}
		if (isPistonBlock(state)) {
			if (isUpwardPiston(state)) {
				Found h = tryHoneyColumn(world, hint);
				if (h != null) out.add(h);
			}
			return out;
		}
		return out;
	}

	/** True if either state side is worth a discoverNear probe. */
	public static boolean isInterestingChange(BlockState a, BlockState b) {
		return isInteresting(a) || isInteresting(b);
	}

	public static boolean isInteresting(BlockState state) {
		if (state == null) return false;
		Block block = state.getBlock();
		if (block instanceof AbstractSignBlock) return true;
		if (block == Blocks.SOUL_SAND || block == Blocks.HONEY_BLOCK) return true;
		if (block == Blocks.BUBBLE_COLUMN) return true;
		if (isPistonBlock(state)) return true;
		FluidState fluid = state.getFluidState();
		return !fluid.isEmpty() && fluid.isOf(Fluids.WATER);
	}

	/**
	 * Scan one loaded chunk for chamber roots inside {@code home}.
	 * Skips sections without a root candidate; stops after {@link #MAX_ROOTS_PER_CHUNK} finds.
	 */
	public static List<Found> scanChunk(ClientWorld world, WorldChunk chunk, StasisHome home) {
		List<Found> out = new ArrayList<>();
		if (world == null || chunk == null || home == null) return out;
		int n = chunk.getSectionArray().length;
		for (int si = 0; si < n && out.size() < MAX_ROOTS_PER_CHUNK; si++) {
			scanSection(world, chunk, home, si, null, out);
		}
		return out;
	}

	/** Optional block-space clip for {@link #scanSection} (inclusive bounds). */
	public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public boolean intersectsSection(int x0, int y0, int z0) {
			return maxX >= x0 && minX <= x0 + 15
				&& maxY >= y0 && minY <= y0 + 15
				&& maxZ >= z0 && minZ <= z0 + 15;
		}
	}

	/**
	 * Scan one 16^3 section (index into the chunk's section array) for roots inside {@code home},
	 * optionally clipped to {@code box}. Returns blocks visited (0 when the palette has no
	 * soul sand / upward piston — the common case, checked without walking 4096 states).
	 */
	public static int scanSection(ClientWorld world, WorldChunk chunk, StasisHome home,
								  int sectionIndex, Box box, List<Found> out) {
		if (world == null || chunk == null || home == null) return 0;
		ChunkSection[] sections = chunk.getSectionArray();
		if (sectionIndex < 0 || sectionIndex >= sections.length) return 0;
		ChunkSection section = sections[sectionIndex];
		if (section == null || section.isEmpty()) return 0;

		ChunkPos cp = chunk.getPos();
		int baseX = cp.getStartX();
		int baseZ = cp.getStartZ();
		int sectionBottom = chunk.getBottomY() + sectionIndex * 16;
		if (box != null && !box.intersectsSection(baseX, sectionBottom, baseZ)) return 0;
		if (!section.hasAny(ChamberScanner::isRootCandidate)) return 0;

		int lyMin = 0, lyMax = 15, lxMin = 0, lxMax = 15, lzMin = 0, lzMax = 15;
		if (box != null) {
			lyMin = Math.max(0, box.minY() - sectionBottom);
			lyMax = Math.min(15, box.maxY() - sectionBottom);
			lxMin = Math.max(0, box.minX() - baseX);
			lxMax = Math.min(15, box.maxX() - baseX);
			lzMin = Math.max(0, box.minZ() - baseZ);
			lzMax = Math.min(15, box.maxZ() - baseZ);
		}
		int visited = 0;
		for (int ly = lyMin; ly <= lyMax && out.size() < MAX_ROOTS_PER_CHUNK; ly++) {
			int y = sectionBottom + ly;
			for (int lx = lxMin; lx <= lxMax && out.size() < MAX_ROOTS_PER_CHUNK; lx++) {
				for (int lz = lzMin; lz <= lzMax && out.size() < MAX_ROOTS_PER_CHUNK; lz++) {
					int bx = baseX + lx;
					int bz = baseZ + lz;
					if (!home.containsBlock(bx, bz)) continue;
					visited++;

					BlockState state = section.getBlockState(lx, ly, lz);
					Block block = state.getBlock();
					Found f = null;
					if (block == Blocks.SOUL_SAND) {
						f = tryWaterColumn(world, new BlockPos(bx, y, bz));
					} else if (isUpwardPiston(state)) {
						f = tryHoneyColumn(world, new BlockPos(bx, y, bz));
					}
					if (f != null && !containsRoot(out, f)) out.add(f);
				}
			}
		}
		return visited;
	}

	private static boolean isRootCandidate(BlockState state) {
		return state.isOf(Blocks.SOUL_SAND) || isUpwardPiston(state);
	}

	/** Root-only re-validate; null if chunk unloaded. */
	public static Boolean validateSlot(ClientWorld world, StasisChamberSlot slot) {
		if (world == null || slot == null) return null;
		if (!rootChunkLoaded(world, slot) && !seatChunkLoaded(world, slot)) return null;
		return refreshRoot(world, slot) != null;
	}

	/**
	 * Re-probe stored root; returns updated Found (seat may shift) or null if invalid / unloaded.
	 * Water seats whose XZ drifted off the root are retargeted from the seat column.
	 */
	public static Found refreshRoot(ClientWorld world, StasisChamberSlot slot) {
		if (world == null || slot == null) return null;
		if (StasisChamberSlot.KIND_WATER.equals(slot.kind)) {
			boolean seatOffRoot = slot.x != slot.rootX || slot.z != slot.rootZ;
			if (seatOffRoot) {
				if (!seatChunkLoaded(world, slot)) return null;
				Found retarget = retargetWaterFromSeat(world, slot.x, slot.y, slot.z);
				if (retarget != null) return retarget;
				// Orphan seat on a foreign root (e.g. elevator) — invalid for this seat.
				return null;
			}
			if (!rootChunkLoaded(world, slot)) return null;
			return tryWaterColumn(world, new BlockPos(slot.rootX, slot.rootY, slot.rootZ));
		}
		if (StasisChamberSlot.KIND_HONEY.equals(slot.kind)) {
			if (!rootChunkLoaded(world, slot)) return null;
			return tryHoneyColumn(world, new BlockPos(slot.rootX, slot.rootY, slot.rootZ));
		}
		return null;
	}

	public static boolean isValidWaterSeat(ClientWorld world, BlockPos seat) {
		if (isKelp(world.getBlockState(seat))) return false;
		if (!isWaterSourceOrBubble(world, seat)) return false;
		BlockPos sand = findSoulSandBelow(world, seat);
		if (sand == null) return false;
		Found f = tryWaterColumn(world, sand);
		if (f == null) return false;
		return seat.getX() == f.rootX() && seat.getZ() == f.rootZ()
			&& seat.getY() >= sand.getY() + 1 && seat.getY() <= sand.getY() + WATER_READY_MAX;
	}

	public static boolean isValidHoneySeat(ClientWorld world, BlockPos seat) {
		if (!isPearlSeatSpace(world, seat)) return false;
		for (Direction dir : Direction.Type.HORIZONTAL) {
			BlockPos honey = seat.offset(dir);
			if (!world.getBlockState(honey).isOf(Blocks.HONEY_BLOCK)) continue;
			if (findUpwardPistonUnderHoney(world, honey) != null) return true;
		}
		return false;
	}

	private static boolean rootChunkLoaded(ClientWorld world, StasisChamberSlot slot) {
		int cx = slot.rootX >> 4;
		int cz = slot.rootZ >> 4;
		return world.getChunkManager().getWorldChunk(cx, cz) != null;
	}

	private static boolean seatChunkLoaded(ClientWorld world, StasisChamberSlot slot) {
		int cx = slot.x >> 4;
		int cz = slot.z >> 4;
		return world.getChunkManager().getWorldChunk(cx, cz) != null;
	}

	private static void probeBox(ClientWorld world, BlockPos center, int xz, int yUp,
								 int cap, List<Found> out) {
		int minY = center.getY() - 1;
		int maxY = center.getY() + yUp;
		for (int dy = minY; dy <= maxY && out.size() < cap; dy++) {
			for (int dx = -xz; dx <= xz && out.size() < cap; dx++) {
				for (int dz = -xz; dz <= xz && out.size() < cap; dz++) {
					BlockPos p = new BlockPos(center.getX() + dx, dy, center.getZ() + dz);
					BlockState st = world.getBlockState(p);
					if (st.isOf(Blocks.SOUL_SAND)) {
						Found w = tryWaterColumn(world, p);
						if (w != null && !containsRoot(out, w)) out.add(w);
					} else if (isUpwardPiston(st)) {
						Found h = tryHoneyColumn(world, p);
						if (h != null && !containsRoot(out, h)) out.add(h);
					}
				}
			}
		}
	}

	private static boolean containsRoot(List<Found> list, Found f) {
		for (Found x : list) {
			if (x.rootX() == f.rootX() && x.rootY() == f.rootY() && x.rootZ() == f.rootZ()
				&& x.kind().equals(f.kind())) return true;
		}
		return false;
	}

	private static BlockPos findSoulSandBelow(ClientWorld world, BlockPos start) {
		BlockPos cursor = start;
		for (int i = 0; i <= WATER_PROBE_MAX; i++) {
			if (world.getBlockState(cursor).isOf(Blocks.SOUL_SAND)) return cursor;
			if (!isWaterSourceOrBubble(world, cursor)) return null;
			cursor = cursor.down();
		}
		return null;
	}

	/**
	 * Bubble-only columns on soul sand are valid (no still-source required).
	 * Reject kelp, short UC (&lt;3), tall elevators (&gt;7), and bottom side entry holes.
	 */
	private static Found tryWaterColumn(ClientWorld world, BlockPos soulSand) {
		if (!world.getBlockState(soulSand).isOf(Blocks.SOUL_SAND)) return null;
		BlockPos above = soulSand.up();
		BlockState aboveState = world.getBlockState(above);
		if (isKelp(aboveState)) return null;
		if (!isWaterSourceOrBubble(world, above)) return null;

		int top = above.getY();
		int maxY = Math.min(soulSand.getY() + WATER_PROBE_MAX, world.getTopYInclusive());
		for (int y = above.getY(); y <= maxY; y++) {
			BlockPos p = new BlockPos(soulSand.getX(), y, soulSand.getZ());
			BlockState st = world.getBlockState(p);
			if (isKelp(st)) return null;
			if (!isWaterSourceOrBubble(world, p)) {
				if (y == above.getY()) return null;
				break;
			}
			top = y;
		}

		int height = top - soulSand.getY();
		if (height < WATER_READY_MIN) return null;
		if (height > WATER_READY_MAX) return null; // elevator / farm shaft
		if (hasBottomSideOpening(world, soulSand)) return null;

		// Pearl floats in the top water/bubble cell (same XZ as soul sand).
		int seatY = top;
		return new Found(soulSand.getX(), seatY, soulSand.getZ(),
			soulSand.getX(), soulSand.getY(), soulSand.getZ(),
			StasisChamberSlot.KIND_WATER);
	}

	/**
	 * When a stored water seat XZ drifted off its root (legacy orphan), re-probe from the seat
	 * down to soul sand. Returns null if no ready column sits under the seat.
	 */
	public static Found retargetWaterFromSeat(ClientWorld world, int seatX, int seatY, int seatZ) {
		if (world == null) return null;
		BlockPos start = new BlockPos(seatX, seatY, seatZ);
		BlockPos sand = findSoulSandBelow(world, start);
		if (sand == null && isWaterSourceOrBubble(world, start.down())) {
			sand = findSoulSandBelow(world, start.down());
		}
		if (sand == null) return null;
		Found found = tryWaterColumn(world, sand);
		if (found == null) return null;
		if (found.rootX() != seatX || found.rootZ() != seatZ) return null;
		return found;
	}

	/** Elevator entry: air / walkable hole beside the lower water cells. */
	private static boolean hasBottomSideOpening(ClientWorld world, BlockPos soulSand) {
		for (int dy = 1; dy <= 3; dy++) {
			BlockPos cell = soulSand.up(dy);
			if (!isWaterSourceOrBubble(world, cell)) continue;
			for (Direction dir : Direction.Type.HORIZONTAL) {
				BlockPos n = cell.offset(dir);
				BlockState st = world.getBlockState(n);
				if (st.isAir()) return true;
				if (isWaterSourceOrBubble(world, n) || isKelp(st)) continue;
				if (!st.blocksMovement() && st.getCollisionShape(world, n).isEmpty()) return true;
			}
		}
		return false;
	}

	private static Found tryHoneyColumn(ClientWorld world, BlockPos piston) {
		BlockState ps = world.getBlockState(piston);
		if (!isUpwardPiston(ps)) return null;

		BlockPos firstHoney = piston.up();
		if (!world.getBlockState(firstHoney).isOf(Blocks.HONEY_BLOCK)) {
			BlockPos alt = piston.up(2);
			if (world.getBlockState(alt).isOf(Blocks.HONEY_BLOCK)) firstHoney = alt;
			else return null;
		}

		int top = firstHoney.getY();
		int maxY = Math.min(piston.getY() + MAX_HONEY_HEIGHT, world.getTopYInclusive());
		for (int y = firstHoney.getY() + 1; y <= maxY; y++) {
			BlockPos p = new BlockPos(piston.getX(), y, piston.getZ());
			if (!world.getBlockState(p).isOf(Blocks.HONEY_BLOCK)) break;
			top = y;
		}

		int seatY = firstHoney.getY() + (top - firstHoney.getY()) / 2;
		BlockPos honeyAt = new BlockPos(piston.getX(), seatY, piston.getZ());
		if (!world.getBlockState(honeyAt).isOf(Blocks.HONEY_BLOCK)) {
			honeyAt = firstHoney;
			seatY = firstHoney.getY();
		}

		BlockPos seat = null;
		for (Direction dir : Direction.Type.HORIZONTAL) {
			BlockPos cand = honeyAt.offset(dir);
			if (isPearlSeatSpace(world, cand)) {
				seat = cand;
				break;
			}
		}
		if (seat == null) return null;

		return new Found(seat.getX(), seat.getY(), seat.getZ(),
			piston.getX(), piston.getY(), piston.getZ(),
			StasisChamberSlot.KIND_HONEY);
	}

	private static BlockPos findUpwardPistonUnderHoney(ClientWorld world, BlockPos honey) {
		BlockPos cursor = honey;
		for (int i = 0; i < MAX_HONEY_HEIGHT; i++) {
			if (!world.getBlockState(cursor).isOf(Blocks.HONEY_BLOCK)) break;
			BlockPos down = cursor.down();
			BlockState ds = world.getBlockState(down);
			if (isUpwardPiston(ds)) return down;
			if (ds.isOf(Blocks.PISTON_HEAD)) {
				BlockPos under = down.down();
				if (isUpwardPiston(world.getBlockState(under))) return under;
			}
			if (!ds.isOf(Blocks.HONEY_BLOCK) && !ds.isOf(Blocks.PISTON_HEAD)) return null;
			cursor = down;
		}
		return null;
	}

	private static boolean isPistonBlock(BlockState state) {
		Block b = state.getBlock();
		return b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.PISTON_HEAD;
	}

	private static boolean isUpwardPiston(BlockState state) {
		Block b = state.getBlock();
		if (b != Blocks.PISTON && b != Blocks.STICKY_PISTON) return false;
		if (!state.contains(Properties.FACING)) return false;
		return state.get(Properties.FACING) == Direction.UP;
	}

	private static boolean isKelp(BlockState state) {
		if (state == null) return false;
		return state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT);
	}

	private static boolean isWaterSourceOrBubble(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (isKelp(state)) return false;
		if (state.isOf(Blocks.BUBBLE_COLUMN)) return true;
		return isWaterSource(world, pos);
	}

	private static boolean isWaterSource(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		FluidState fluid = state.getFluidState();
		if (fluid.isEmpty() || !fluid.isOf(Fluids.WATER)) return false;
		return fluid.isStill();
	}

	private static boolean isPearlSeatSpace(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) return true;
		if (isWaterSourceOrBubble(world, pos)) return true;
		return !state.blocksMovement() && state.getCollisionShape(world, pos).isEmpty();
	}
}
