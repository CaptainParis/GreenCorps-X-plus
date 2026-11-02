package dev.greencorps.export;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes a 64×64 {@code icon.png} for Singleplayer save list from exported chunk NBTs.
 */
public final class SurfaceIconRenderer {
	private static final int SIZE = 64;

	private SurfaceIconRenderer() {}

	public static void writeIcon(Path worldDir, Map<Long, NbtCompound> destChunks) throws IOException {
		if (worldDir == null || destChunks == null || destChunks.isEmpty()) return;
		int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
		int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
		for (long key : destChunks.keySet()) {
			int cx = ChunkPos.getPackedX(key);
			int cz = ChunkPos.getPackedZ(key);
			minCx = Math.min(minCx, cx);
			maxCx = Math.max(maxCx, cx);
			minCz = Math.min(minCz, cz);
			maxCz = Math.max(maxCz, cz);
		}
		int spanX = Math.max(1, (maxCx - minCx + 1) * 16);
		int spanZ = Math.max(1, (maxCz - minCz + 1) * 16);
		int[][] argb = new int[SIZE][SIZE];
		int grass = packArgb(0x55, 0x88, 0x55);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				argb[y][x] = grass;
			}
		}
		for (Map.Entry<Long, NbtCompound> e : destChunks.entrySet()) {
			int cx = ChunkPos.getPackedX(e.getKey());
			int cz = ChunkPos.getPackedZ(e.getKey());
			int[] top = sampleTopColors(e.getValue());
			if (top == null) continue;
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					int worldX = cx * 16 + lx;
					int worldZ = cz * 16 + lz;
					int px = (int) ((worldX - minCx * 16L) * (SIZE - 1L) / Math.max(1, spanX - 1));
					int pz = (int) ((worldZ - minCz * 16L) * (SIZE - 1L) / Math.max(1, spanZ - 1));
					px = Math.max(0, Math.min(SIZE - 1, px));
					pz = Math.max(0, Math.min(SIZE - 1, pz));
					argb[pz][px] = top[lz * 16 + lx];
				}
			}
		}
		try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false)) {
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) {
					image.setColorArgb(x, y, argb[y][x]);
				}
			}
			image.writeTo(worldDir.resolve("icon.png"));
		}
	}

	private static int[] sampleTopColors(NbtCompound chunk) {
		if (chunk == null || !chunk.contains("sections", NbtElement.LIST_TYPE)) return null;
		NbtList sections = chunk.getList("sections", NbtElement.COMPOUND_TYPE);
		int[] bestY = new int[256];
		int[] bestColor = new int[256];
		int fallback = packArgb(0x55, 0x88, 0x55);
		for (int i = 0; i < 256; i++) {
			bestY[i] = Integer.MIN_VALUE;
			bestColor[i] = fallback;
		}
		for (int si = 0; si < sections.size(); si++) {
			NbtCompound sec = sections.getCompound(si);
			if (!sec.contains("block_states", NbtElement.COMPOUND_TYPE)) continue;
			int sectionY = sec.contains("Y", NbtElement.BYTE_TYPE) ? sec.getByte("Y") : sec.getInt("Y");
			NbtCompound states = sec.getCompound("block_states");
			if (!states.contains("palette", NbtElement.LIST_TYPE)) continue;
			NbtList palette = states.getList("palette", NbtElement.COMPOUND_TYPE);
			if (palette.isEmpty()) continue;
			if (!states.contains("data", NbtElement.LONG_ARRAY_TYPE) && palette.size() == 1) {
				NbtCompound only = palette.getCompound(0);
				if (isAir(only)) continue;
				int color = paletteColor(only);
				int baseY = sectionY * 16 + 15;
				for (int i = 0; i < 256; i++) {
					if (baseY >= bestY[i]) {
						bestY[i] = baseY;
						bestColor[i] = color;
					}
				}
				continue;
			}
			if (!states.contains("data", NbtElement.LONG_ARRAY_TYPE)) continue;
			long[] data = states.getLongArray("data");
			int bits = Math.max(4, bitsFor(palette.size()));
			int perLong = Math.max(1, 64 / bits);
			int mask = (1 << bits) - 1;
			for (int ly = 15; ly >= 0; ly--) {
				int worldY = sectionY * 16 + ly;
				for (int lz = 0; lz < 16; lz++) {
					for (int lx = 0; lx < 16; lx++) {
						int idx = ly * 256 + lz * 16 + lx;
						int word = idx / perLong;
						int shift = (idx % perLong) * bits;
						if (word < 0 || word >= data.length) continue;
						int pi = (int) ((data[word] >>> shift) & mask);
						if (pi < 0 || pi >= palette.size()) continue;
						NbtCompound entry = palette.getCompound(pi);
						if (isAir(entry)) continue;
						int flat = lz * 16 + lx;
						if (worldY >= bestY[flat]) {
							bestY[flat] = worldY;
							bestColor[flat] = paletteColor(entry);
						}
					}
				}
			}
		}
		return bestColor;
	}

	private static int bitsFor(int paletteSize) {
		int bits = 4;
		while ((1 << bits) < paletteSize) bits++;
		return Math.min(8, bits);
	}

	private static boolean isAir(NbtCompound paletteEntry) {
		String name = paletteEntry.getString("Name");
		return name == null || name.isBlank() || name.equals("minecraft:air")
			|| name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
	}

	private static int paletteColor(NbtCompound paletteEntry) {
		String name = paletteEntry.getString("Name");
		if (name == null || name.isBlank()) return packArgb(0x55, 0x88, 0x55);
		try {
			Block block = Registries.BLOCK.get(Identifier.of(name));
			if (block == null || block == Blocks.AIR) return packArgb(0x55, 0x88, 0x55);
			BlockState state = block.getDefaultState();
			MapColor mc = state.getMapColor(null, null);
			if (mc == null) return packArgb(0x88, 0x88, 0x88);
			int rgb = mc.color;
			int r = (rgb >> 16) & 0xFF;
			int g = (rgb >> 8) & 0xFF;
			int b = rgb & 0xFF;
			return packArgb(r, g, b);
		} catch (Exception e) {
			return packArgb(0x88, 0x88, 0x88);
		}
	}

	private static int packArgb(int r, int g, int b) {
		return (0xFF << 24) | (r << 16) | (g << 8) | b;
	}
}
