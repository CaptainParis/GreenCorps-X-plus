package dev.greencorps.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flatness score (0–100) for Java pillager-outpost spawn footprint.
 * Samples surface Y in the ±36 horizontal spawn window; score is the share of
 * samples within ±2 of the median surface height.
 */
public final class OutpostTerrainScore {
	public static final int HALF_FOOTPRINT = 36;
	public static final int Y_BELOW = 30;
	public static final int Y_ABOVE = 28;
	public static final int MIN_SAMPLES = 25;
	public static final int FLAT_TOLERANCE = 2;

	private OutpostTerrainScore() {
	}

	public static int score(World world, BlockPos anchor, int topFloorY, int sampleStep) {
		if (world == null || anchor == null) return 0;
		int step = Math.max(2, sampleStep);
		List<Integer> heights = new ArrayList<>();

		int minY = topFloorY - Y_BELOW;
		int maxY = topFloorY + Y_ABOVE;

		for (int x = anchor.getX() - HALF_FOOTPRINT; x <= anchor.getX() + HALF_FOOTPRINT; x += step) {
			for (int z = anchor.getZ() - HALF_FOOTPRINT; z <= anchor.getZ() + HALF_FOOTPRINT; z += step) {
				int chunkX = x >> 4;
				int chunkZ = z >> 4;
				if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
				Chunk chunk = world.getChunk(chunkX, chunkZ);
				if (chunk == null) continue;

				Integer surface = topOpaqueY(world, x, z, minY, maxY);
				if (surface != null) heights.add(surface);
			}
		}

		if (heights.size() < MIN_SAMPLES) return 0;

		Collections.sort(heights);
		int median = heights.get(heights.size() / 2);
		int flat = 0;
		for (int h : heights) {
			if (Math.abs(h - median) <= FLAT_TOLERANCE) flat++;
		}
		return (int) Math.round(100.0 * flat / heights.size());
	}

	private static Integer topOpaqueY(World world, int x, int z, int minY, int maxY) {
		int worldBottom = world.getBottomY();
		int worldTop = worldBottom + world.getHeight();
		int from = Math.min(maxY, worldTop - 1);
		int to = Math.max(minY, worldBottom);

		for (int y = from; y >= to; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState state = world.getBlockState(pos);
			if (state.isAir()) continue;
			if (state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA)) continue;
			if (!state.isOpaqueFullCube()) continue;
			if (state.isIn(BlockTags.LEAVES)) continue;
			return y;
		}
		return null;
	}
}
