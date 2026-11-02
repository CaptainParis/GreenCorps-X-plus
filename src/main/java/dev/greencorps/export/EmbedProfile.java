package dev.greencorps.export;

import dev.greencorps.backup.Home;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.Fluids;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.SerializedChunk;

import java.util.HashMap;
import java.util.Map;

/** Natural-edge profile for embedded seed matching. */
public final class EmbedProfile {
	public final String dimension;
	public final boolean endDimension;
	public final String dominantBiomeId;
	public final double waterFraction;
	public final int medianSurfaceY;
	public final int sampleColumns;

	public EmbedProfile(String dimension, boolean endDimension, String dominantBiomeId,
						double waterFraction, int medianSurfaceY, int sampleColumns) {
		this.dimension = dimension;
		this.endDimension = endDimension;
		this.dominantBiomeId = dominantBiomeId;
		this.waterFraction = waterFraction;
		this.medianSurfaceY = medianSurfaceY;
		this.sampleColumns = sampleColumns;
	}

	public static EmbedProfile fromRestacked(Home home, Map<Long, NbtCompound> destChunks) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null || destChunks == null || destChunks.isEmpty()) {
			boolean end = home != null && "minecraft:the_end".equals(home.dimension);
			return new EmbedProfile(home == null ? "minecraft:overworld" : home.dimension,
				end, end ? "minecraft:end_highlands" : "minecraft:plains", 0, 64, 0);
		}
		DynamicRegistryManager regs = mc.world.getRegistryManager();
		HeightLimitView heightLimit = mc.world;
		boolean end = "minecraft:the_end".equals(home.dimension);

		Map<String, Integer> biomeCounts = new HashMap<>();
		int water = 0;
		int columns = 0;
		int[] surfaces = new int[4096];
		int surfaceN = 0;

		int half = home.radiusChunks;
		int destCx = home.centerChunkX; // approximate — use spawn-centered after transform via chunk set
		// Use bounding box of dest chunks for outer ring
		int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
		for (long k : destChunks.keySet()) {
			int cx = ChunkPos.getPackedX(k);
			int cz = ChunkPos.getPackedZ(k);
			minCx = Math.min(minCx, cx);
			maxCx = Math.max(maxCx, cx);
			minCz = Math.min(minCz, cz);
			maxCz = Math.max(maxCz, cz);
		}

		for (Map.Entry<Long, NbtCompound> e : destChunks.entrySet()) {
			int cx = ChunkPos.getPackedX(e.getKey());
			int cz = ChunkPos.getPackedZ(e.getKey());
			int edgeDist = Math.min(Math.min(cx - minCx, maxCx - cx), Math.min(cz - minCz, maxCz - cz));
			if (edgeDist > 1) continue; // outer ring only
			try {
				SerializedChunk sc = SerializedChunk.fromNbt(heightLimit, regs, e.getValue());
				for (SerializedChunk.SectionData sd : sc.sectionData()) {
					ChunkSection section = sd.chunkSection();
					if (section == null) continue;
					// sample biomes at 4 corners of section
					for (int i = 0; i < 4; i++) {
						var b = section.getBiome(i & 1, 0, i >> 1);
						if (b != null) {
							Identifier id = b.getKey().map(k -> k.getValue()).orElse(Identifier.of("minecraft", "plains"));
							biomeCounts.merge(id.toString(), 1, Integer::sum);
						}
					}
					int baseY = sd.y() * 16;
					for (int lz = 0; lz < 16; lz += 4) {
						for (int lx = 0; lx < 16; lx += 4) {
							columns++;
							int surface = baseY;
							boolean wet = false;
							for (int ly = 15; ly >= 0; ly--) {
								BlockState st = section.getBlockState(lx, ly, lz);
								if (st == null || st.isAir()) continue;
								surface = baseY + ly;
								if (st.getFluidState().getFluid() != Fluids.EMPTY
									|| st.isOf(Blocks.WATER) || st.isOf(Blocks.LAVA)) {
									wet = true;
								}
								break;
							}
							if (wet) water++;
							if (surfaceN < surfaces.length) surfaces[surfaceN++] = surface;
						}
					}
				}
			} catch (Exception ignored) {}
		}

		String dominant = end ? "minecraft:end_highlands" : "minecraft:plains";
		int best = 0;
		for (Map.Entry<String, Integer> be : biomeCounts.entrySet()) {
			if (be.getValue() > best) {
				best = be.getValue();
				dominant = be.getKey();
			}
		}
		int median = 64;
		if (surfaceN > 0) {
			java.util.Arrays.sort(surfaces, 0, surfaceN);
			median = surfaces[surfaceN / 2];
		}
		double wf = columns == 0 ? 0 : (double) water / columns;
		return new EmbedProfile(home.dimension, end, dominant, wf, median, columns);
	}
}
