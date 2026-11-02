package dev.greencorps.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores an underground lower-hemisphere volume for solid density (spawn-proof friendliness)
 * and optional ore richness. Samples on a coarse 3D grid inside the hemisphere only.
 */
public final class SolidPocketScore {
	public static final int MIN_SAMPLES = 40;
	/** Reject if more than this fraction of samples are water (ocean/aquifer). */
	public static final double MAX_WATER_FRACTION = 0.15;

	private SolidPocketScore() {
	}

	public static final class Result {
		public final int density;
		public final int ore;
		public final int samples;
		public final boolean valid;

		public Result(int density, int ore, int samples, boolean valid) {
			this.density = density;
			this.ore = ore;
			this.samples = samples;
			this.valid = valid;
		}

		public static Result invalid() {
			return new Result(0, 0, 0, false);
		}
	}

	/**
	 * @param scoreOres when false, ore field is 0 and ore blocks are not classified specially
	 */
	public static Result score(World world, BlockPos anchor, int radius, int sampleStep, boolean scoreOres) {
		if (world == null || anchor == null) return Result.invalid();
		int r = Math.max(8, radius);
		int step = Math.max(2, sampleStep);
		int r2 = r * r;

		int samples = 0;
		int solid = 0;
		int water = 0;
		int ore = 0;
		int solidForOre = 0;

		BlockPos.Mutable mutable = new BlockPos.Mutable();

		for (int dx = -r; dx <= r; dx += step) {
			for (int dz = -r; dz <= r; dz += step) {
				for (int dy = -r; dy <= 0; dy += step) {
					int dist2 = dx * dx + dy * dy + dz * dz;
					if (dist2 > r2) continue;

					int x = anchor.getX() + dx;
					int y = anchor.getY() + dy;
					int z = anchor.getZ() + dz;

					int chunkX = x >> 4;
					int chunkZ = z >> 4;
					if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
					Chunk chunk = world.getChunk(chunkX, chunkZ);
					if (chunk == null) continue;

					mutable.set(x, y, z);
					BlockState state = world.getBlockState(mutable);
					samples++;

					if (state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA)) {
						water++;
						continue;
					}

					boolean opaque = state.isOpaqueFullCube();
					if (opaque) {
						solid++;
						if (scoreOres) {
							solidForOre++;
							if (isOre(state)) ore++;
						}
					}
				}
			}
		}

		if (samples < MIN_SAMPLES) return Result.invalid();
		if ((double) water / samples > MAX_WATER_FRACTION) return Result.invalid();

		int density = (int) Math.round(100.0 * solid / samples);
		int oreScore = 0;
		if (scoreOres && solidForOre > 0) {
			oreScore = (int) Math.round(100.0 * ore / solidForOre);
		}
		return new Result(density, oreScore, samples, true);
	}

	/**
	 * Collects non-solid (cave/air) sample positions inside the lower hemisphere for ESP.
	 * Caps at {@code maxMarkers}. Uses a coarse step for cost control.
	 */
	public static List<BlockPos> collectCaveMarkers(World world, BlockPos anchor, int radius, int sampleStep, int maxMarkers) {
		List<BlockPos> out = new ArrayList<>();
		if (world == null || anchor == null || maxMarkers <= 0) return out;

		int r = Math.max(8, radius);
		int step = Math.max(6, sampleStep);
		int r2 = r * r;
		BlockPos.Mutable mutable = new BlockPos.Mutable();

		for (int dx = -r; dx <= r && out.size() < maxMarkers; dx += step) {
			for (int dz = -r; dz <= r && out.size() < maxMarkers; dz += step) {
				for (int dy = -r; dy <= 0 && out.size() < maxMarkers; dy += step) {
					int dist2 = dx * dx + dy * dy + dz * dz;
					if (dist2 > r2) continue;

					int x = anchor.getX() + dx;
					int y = anchor.getY() + dy;
					int z = anchor.getZ() + dz;
					if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

					mutable.set(x, y, z);
					BlockState state = world.getBlockState(mutable);
					if (state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA)) continue;
					if (state.isOpaqueFullCube()) continue;
					out.add(new BlockPos(x, y, z));
				}
			}
		}
		return out;
	}

	private static boolean isOre(BlockState state) {
		return state.isIn(BlockTags.COAL_ORES)
			|| state.isIn(BlockTags.IRON_ORES)
			|| state.isIn(BlockTags.COPPER_ORES)
			|| state.isIn(BlockTags.GOLD_ORES)
			|| state.isIn(BlockTags.REDSTONE_ORES)
			|| state.isIn(BlockTags.LAPIS_ORES)
			|| state.isIn(BlockTags.DIAMOND_ORES)
			|| state.isIn(BlockTags.EMERALD_ORES)
			|| state.isOf(Blocks.NETHER_QUARTZ_ORE)
			|| state.isOf(Blocks.NETHER_GOLD_ORE)
			|| state.isOf(Blocks.ANCIENT_DEBRIS);
	}
}
