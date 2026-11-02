package dev.greencorps.export;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.gen.chunk.BlendingData;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Height-only blend ring around restacked capture (v1 — no ores/caves/trees).
 */
public final class HeightRingSculpt {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/height-ring");

	private HeightRingSculpt() {}

	public static void sculpt(Path destWorld, String levelName, ExportTransform tx, EmbedProfile profile,
							  Map<Long, NbtCompound> capturedDest, int blendRadiusChunks) throws Exception {
		if (blendRadiusChunks < 1 || capturedDest == null || capturedDest.isEmpty()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) return;

		Registry<Biome> biomes = mc.world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
		int minSectionY = mc.world.getBottomSectionCoord();
		int bottomY = mc.world.getBottomY();

		int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
		for (long k : capturedDest.keySet()) {
			minCx = Math.min(minCx, ChunkPos.getPackedX(k));
			maxCx = Math.max(maxCx, ChunkPos.getPackedX(k));
			minCz = Math.min(minCz, ChunkPos.getPackedZ(k));
			maxCz = Math.max(maxCz, ChunkPos.getPackedZ(k));
		}

		boolean end = profile.endDimension;
		BlockState deep = end ? Blocks.END_STONE.getDefaultState() : Blocks.STONE.getDefaultState();
		BlockState mid = end ? Blocks.END_STONE.getDefaultState() : Blocks.DIRT.getDefaultState();
		BlockState top = end ? Blocks.END_STONE.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
		boolean waterish = !end && profile.waterFraction >= 0.45;
		int targetSurface = profile.medianSurfaceY;

		Path regionDir = destWorld.resolve("region");
		Files.createDirectories(regionDir);
		RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
		StorageKey storageKey = new StorageKey(levelName, dim, "region");
		Map<Long, RegionFile> regions = new HashMap<>();
		Set<Long> written = new HashSet<>();

		try {
			for (int cx = minCx - blendRadiusChunks; cx <= maxCx + blendRadiusChunks; cx++) {
				for (int cz = minCz - blendRadiusChunks; cz <= maxCz + blendRadiusChunks; cz++) {
					long key = ChunkPos.toLong(cx, cz);
					int chebInside = chebyshevOutside(cx, cz, minCx, maxCx, minCz, maxCz);
					if (chebInside < 1 || chebInside > blendRadiusChunks) continue;
					if (capturedDest.containsKey(key)) continue; // never overwrite capture

					double t = smoothstep(chebInside / (double) blendRadiusChunks);
					int surface = (int) Math.round(MathHelper.lerp(t, targetSurface, targetSurface + (end ? 0 : 4)));
					boolean outer = chebInside == blendRadiusChunks;

					NbtCompound nbt = buildColumnChunk(biomes, minSectionY, bottomY, cx, cz,
						surface, deep, mid, top, waterish && t > 0.55, outer);
					ChunkPos pos = new ChunkPos(cx, cz);
					int rx = pos.getRegionX();
					int rz = pos.getRegionZ();
					long rkey = ChunkPos.toLong(rx, rz);
					RegionFile rf = regions.get(rkey);
					if (rf == null) {
						Path regionPath = regionDir.resolve("r." + rx + "." + rz + ".mca");
						rf = new RegionFile(storageKey, regionPath, regionDir, false);
						regions.put(rkey, rf);
					}
					try (DataOutputStream out = rf.getChunkOutputStream(pos)) {
						NbtIo.writeCompound(nbt, out);
					}
					written.add(key);
				}
			}
		} finally {
			for (RegionFile rf : regions.values()) {
				try { rf.close(); } catch (Exception ignored) {}
			}
		}
		LOG.info("height ring wrote {} chunks radius={}", written.size(), blendRadiusChunks);
	}

	private static int chebyshevOutside(int cx, int cz, int minCx, int maxCx, int minCz, int maxCz) {
		int dx = 0;
		if (cx < minCx) dx = minCx - cx;
		else if (cx > maxCx) dx = cx - maxCx;
		int dz = 0;
		if (cz < minCz) dz = minCz - cz;
		else if (cz > maxCz) dz = cz - maxCz;
		if (dx == 0 && dz == 0) return 0;
		return Math.max(dx, dz);
	}

	private static double smoothstep(double x) {
		x = MathHelper.clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}

	private static NbtCompound buildColumnChunk(Registry<Biome> biomes, int minSectionY, int bottomY,
												int cx, int cz, int surfaceY,
												BlockState deep, BlockState mid, BlockState top,
												boolean waterSurface, boolean blending) {
		int maxSection = Math.max(minSectionY, (surfaceY >> 4));
		int sectionCount = maxSection - minSectionY + 1;
		List<SerializedChunk.SectionData> sectionData = new ArrayList<>();
		for (int si = 0; si < sectionCount; si++) {
			int sectionY = minSectionY + si;
			ChunkSection section = new ChunkSection(biomes);
			int baseY = sectionY * 16;
			boolean any = false;
			for (int ly = 0; ly < 16; ly++) {
				int y = baseY + ly;
				if (y < bottomY) continue;
				for (int lz = 0; lz < 16; lz++) {
					for (int lx = 0; lx < 16; lx++) {
						BlockState st;
						if (y > surfaceY) {
							continue;
						} else if (waterSurface && y == surfaceY) {
							st = Blocks.WATER.getDefaultState();
						} else if (y == surfaceY) {
							st = top;
						} else if (y >= surfaceY - 3) {
							st = mid;
						} else {
							st = deep;
						}
						section.setBlockState(lx, ly, lz, st);
						any = true;
					}
				}
			}
			if (!any) continue;
			section.calculateCounts();
			sectionData.add(new SerializedChunk.SectionData(
				sectionY, section, new ChunkNibbleArray(), new ChunkNibbleArray()));
		}

		BlendingData.Serialized blend = blending
			? new BlendingData.Serialized(-4, 20, Optional.empty())
			: null;

		SerializedChunk sc = new SerializedChunk(
			biomes,
			new ChunkPos(cx, cz),
			minSectionY,
			System.currentTimeMillis(),
			0L,
			ChunkStatus.FULL,
			blend,
			null,
			UpgradeData.NO_UPGRADE_DATA,
			null,
			new EnumMap<>(net.minecraft.world.Heightmap.Type.class),
			new Chunk.TickSchedulers(List.of(), List.of()),
			new it.unimi.dsi.fastutil.shorts.ShortList[0],
			false,
			sectionData,
			List.of(),
			List.of(),
			new NbtCompound()
		);
		NbtCompound nbt = sc.serialize();
		dev.greencorps.backup.ChunkCapture.ensurePlayableStatus(nbt);
		return nbt;
	}
}
