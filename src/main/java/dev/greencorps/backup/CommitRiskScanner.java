package dev.greencorps.backup;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compares loaded dirty chunks against the last snapshot for severe change /
 * hostile-material / storage-strip signals before auto-commit.
 */
public final class CommitRiskScanner {
	private static final double SEVERE_AIR_RATIO = 0.35;
	private static final int MIN_SAMPLES = 64;
	private static final int HOSTILE_SPIKE = 48;
	private static final int STORAGE_LOSS = 8;
	private static final int OBSIDIAN_SPIKE = 64;

	private CommitRiskScanner() {}

	public record Report(boolean risky, int changedChunks, List<String> reasons) {
		public static Report clean(int changed) {
			return new Report(false, changed, List.of());
		}
	}

	public static Report scan(BackupEngine engine, Home home) {
		if (engine == null || home == null) return Report.clean(0);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) return Report.clean(0);
		ClientWorld world = mc.world;
		List<ChunkPos> dirty = engine.dirtyChunkPositions(home.id);
		if (dirty.isEmpty()) return Report.clean(0);

		SnapshotManifest prev;
		try {
			SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), home.id);
			Optional<SnapshotIndex.Entry> latest = idx.latest();
			if (latest.isEmpty()) return Report.clean(dirty.size());
			prev = SnapshotManifest.read(engine.manifestPath(home.id, latest.get().filename));
		} catch (IOException e) {
			return Report.clean(dirty.size());
		}

		int changed = 0;
		int solidToAir = 0;
		int samples = 0;
		int hostileDelta = 0;
		int storageDelta = 0;
		int obsidianDelta = 0;

		for (ChunkPos pos : dirty) {
			WorldChunk live = world.getChunkManager().getWorldChunk(pos.x, pos.z);
			if (live == null) continue;
			changed++;
			String prevHash = prev.getChunk(pos.x, pos.z);
			BlockStats now = BlockStats.fromWorldChunk(live);
			BlockStats before = BlockStats.EMPTY;
			if (prevHash != null) {
				try {
					byte[] raw = engine.store().getChunkObject(prevHash);
					if (!ChunkCapture.isLegacyJsonBlob(raw)) {
						before = BlockStats.fromNbt(ChunkCapture.readNbt(raw));
					}
				} catch (IOException ignored) {}
			}
			samples += Math.max(now.solid, before.solid);
			solidToAir += Math.max(0, before.solid - now.solid);
			hostileDelta += (now.hostile - before.hostile);
			storageDelta += (before.storage - now.storage);
			obsidianDelta += (now.obsidian - before.obsidian);
		}

		List<String> reasons = new ArrayList<>();
		reasons.add(changed + " dirty chunk(s) reviewed");
		List<String> flags = new ArrayList<>();
		if (samples >= MIN_SAMPLES && solidToAir > samples * SEVERE_AIR_RATIO) {
			flags.add("Severe change: large solid→air loss (" + solidToAir + " cells)");
		}
		if (hostileDelta >= HOSTILE_SPIKE) {
			flags.add("Hostile materials spike (+" + hostileDelta + " TNT/lava/fire/wither/portal)");
		}
		if (storageDelta >= STORAGE_LOSS) {
			flags.add("Likely stripped storage (−" + storageDelta + " chests/shulkers/barrels)");
		}
		if (obsidianDelta >= OBSIDIAN_SPIKE) {
			flags.add("Unexplained obsidian increase (+" + obsidianDelta + ")");
		}
		if (flags.isEmpty()) return Report.clean(changed);
		reasons.addAll(flags);
		return new Report(true, changed, List.copyOf(reasons));
	}

	static final class BlockStats {
		static final BlockStats EMPTY = new BlockStats(0, 0, 0, 0);
		final int solid;
		final int hostile;
		final int storage;
		final int obsidian;

		BlockStats(int solid, int hostile, int storage, int obsidian) {
			this.solid = solid;
			this.hostile = hostile;
			this.storage = storage;
			this.obsidian = obsidian;
		}

		static BlockStats fromWorldChunk(WorldChunk chunk) {
			int solid = 0, hostile = 0, storage = 0, obsidian = 0;
			ChunkSection[] sections = chunk.getSectionArray();
			if (sections == null) return EMPTY;
			for (ChunkSection sec : sections) {
				if (sec == null || sec.isEmpty()) continue;
				for (int y = 0; y < 16; y += 2) {
					for (int z = 0; z < 16; z += 2) {
						for (int x = 0; x < 16; x += 2) {
							BlockState state = sec.getBlockState(x, y, z);
							Block b = state.getBlock();
							if (state.isAir()) continue;
							solid++;
							if (isHostile(b)) hostile++;
							if (isStorage(b)) storage++;
							if (b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN) obsidian++;
						}
					}
				}
			}
			return new BlockStats(solid, hostile, storage, obsidian);
		}

		static BlockStats fromNbt(NbtCompound chunk) {
			if (chunk == null || !chunk.contains("sections", NbtElement.LIST_TYPE)) return EMPTY;
			int solid = 0, hostile = 0, storage = 0, obsidian = 0;
			NbtList sections = chunk.getList("sections", NbtElement.COMPOUND_TYPE);
			for (int si = 0; si < sections.size(); si++) {
				NbtCompound sec = sections.getCompound(si);
				if (!sec.contains("block_states", NbtElement.COMPOUND_TYPE)) continue;
				NbtCompound states = sec.getCompound("block_states");
				if (!states.contains("palette", NbtElement.LIST_TYPE)) continue;
				NbtList palette = states.getList("palette", NbtElement.COMPOUND_TYPE);
				int[] counts = new int[palette.size()];
				if (!states.contains("data", NbtElement.LONG_ARRAY_TYPE)) {
					if (palette.size() == 1) {
						BlockProxy p = proxy(palette.getCompound(0));
						if (!p.air) {
							solid += 64;
							if (p.hostile) hostile += 64;
							if (p.storage) storage += 8;
							if (p.obsidian) obsidian += 64;
						}
					}
					continue;
				}
				long[] data = states.getLongArray("data");
				int bits = Math.max(4, bitsFor(palette.size()));
				int perLong = Math.max(1, 64 / bits);
				int mask = (1 << bits) - 1;
				for (int ly = 0; ly < 16; ly += 2) {
					for (int lz = 0; lz < 16; lz += 2) {
						for (int lx = 0; lx < 16; lx += 2) {
							int idx = ly * 256 + lz * 16 + lx;
							int word = idx / perLong;
							int shift = (idx % perLong) * bits;
							if (word < 0 || word >= data.length) continue;
							int pi = (int) ((data[word] >>> shift) & mask);
							if (pi < 0 || pi >= palette.size()) continue;
							counts[pi]++;
						}
					}
				}
				for (int i = 0; i < palette.size(); i++) {
					BlockProxy p = proxy(palette.getCompound(i));
					int n = counts[i];
					if (p.air || n == 0) continue;
					solid += n;
					if (p.hostile) hostile += n;
					if (p.storage) storage += n;
					if (p.obsidian) obsidian += n;
				}
			}
			return new BlockStats(solid, hostile, storage, obsidian);
		}

		private static int bitsFor(int paletteSize) {
			int bits = 4;
			while ((1 << bits) < paletteSize) bits++;
			return Math.min(8, bits);
		}

		private static BlockProxy proxy(NbtCompound entry) {
			String name = entry.getString("Name");
			if (name == null || name.isBlank() || name.equals("minecraft:air")
				|| name.equals("minecraft:cave_air") || name.equals("minecraft:void_air")) {
				return new BlockProxy(true, false, false, false);
			}
			try {
				Block b = Registries.BLOCK.get(Identifier.of(name));
				return new BlockProxy(false, isHostile(b), isStorage(b),
					b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN);
			} catch (Exception e) {
				return new BlockProxy(false, false, false, false);
			}
		}

		private record BlockProxy(boolean air, boolean hostile, boolean storage, boolean obsidian) {}

		private static boolean isHostile(Block b) {
			return b == Blocks.TNT || b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE
				|| b == Blocks.WITHER_ROSE || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL
				|| b == Blocks.END_PORTAL_FRAME || b == Blocks.COBBLESTONE || b == Blocks.NETHERRACK;
		}

		private static boolean isStorage(Block b) {
			return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL
				|| b == Blocks.ENDER_CHEST || b instanceof ShulkerBoxBlock;
		}
	}
}
