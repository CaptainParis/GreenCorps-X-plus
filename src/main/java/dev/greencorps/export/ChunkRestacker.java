package dev.greencorps.export;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.UpgradeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Restacks captured chunk NBT through {@link ExportTransform} (yaw + optional origin shift).
 * Strip structure starts; keep item-frame Fixed flags.
 */
public final class ChunkRestacker {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/restack");

	private ChunkRestacker() {}

	public record RestackResult(Map<Long, NbtCompound> chunksByPos, int blockCount, int entityCount) {}

	public static RestackResult restack(Map<Long, NbtCompound> sourceChunks, ExportTransform tx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) {
			throw new IllegalStateException("restack requires client world registries");
		}
		DynamicRegistryManager regs = mc.world.getRegistryManager();
		HeightLimitView heightLimit = mc.world;
		Registry<Biome> biomes = regs.getOrThrow(RegistryKeys.BIOME);
		int minSectionY = heightLimit.getBottomSectionCoord();
		int topY = heightLimit.getTopYInclusive();
		int bottomY = heightLimit.getBottomY();

		Map<Long, ChunkSection[]> destSections = new HashMap<>();
		Map<Long, List<NbtCompound>> destEntities = new HashMap<>();
		Map<Long, List<NbtCompound>> destBlockEntities = new HashMap<>();
		int blocks = 0;
		int entities = 0;

		for (Map.Entry<Long, NbtCompound> e : sourceChunks.entrySet()) {
			NbtCompound srcNbt = e.getValue();
			SerializedChunk serialized;
			try {
				serialized = SerializedChunk.fromNbt(heightLimit, regs, srcNbt);
			} catch (Exception ex) {
				LOG.warn("skip chunk decode {},{}: {}",
					ChunkPos.getPackedX(e.getKey()), ChunkPos.getPackedZ(e.getKey()), ex.toString());
				continue;
			}
			ChunkPos srcPos = serialized.chunkPos();

			for (SerializedChunk.SectionData sd : serialized.sectionData()) {
				ChunkSection section = sd.chunkSection();
				if (section == null || section.isEmpty()) continue;
				int sectionY = sd.y();
				int baseY = sectionY * 16;
				for (int ly = 0; ly < 16; ly++) {
					int y = baseY + ly;
					if (y < bottomY || y > topY) continue;
					for (int lz = 0; lz < 16; lz++) {
						for (int lx = 0; lx < 16; lx++) {
							BlockState state = section.getBlockState(lx, ly, lz);
							if (state == null || state.isAir()) continue;
							int wx = srcPos.getStartX() + lx;
							int wz = srcPos.getStartZ() + lz;
							int[] t = tx.transformBlock(wx, wz);
							BlockState rotated = state.rotate(tx.blockRotation);
							place(destSections, biomes, t[0], y, t[1], rotated, minSectionY);
							blocks++;
						}
					}
				}
			}

			for (NbtCompound be : serialized.blockEntities()) {
				NbtCompound copy = be.copy();
				int bx = copy.getInt("x");
				int by = copy.getInt("y");
				int bz = copy.getInt("z");
				BlockPos np = tx.transformPos(new BlockPos(bx, by, bz));
				copy.putInt("x", np.getX());
				copy.putInt("y", np.getY());
				copy.putInt("z", np.getZ());
				long key = ChunkPos.toLong(np.getX() >> 4, np.getZ() >> 4);
				destBlockEntities.computeIfAbsent(key, k -> new ArrayList<>()).add(copy);
			}

			for (NbtCompound ent : serialized.entities()) {
				NbtCompound copy = transformEntity(ent, tx);
				if (copy == null) continue;
				NbtList pos = copy.getList("Pos", NbtElement.DOUBLE_TYPE);
				int ecx = (int) Math.floor(pos.getDouble(0)) >> 4;
				int ecz = (int) Math.floor(pos.getDouble(2)) >> 4;
				long key = ChunkPos.toLong(ecx, ecz);
				destEntities.computeIfAbsent(key, k -> new ArrayList<>()).add(copy);
				entities++;
			}
		}

		Map<Long, NbtCompound> out = new HashMap<>();
		for (Map.Entry<Long, ChunkSection[]> e : destSections.entrySet()) {
			long key = e.getKey();
			ChunkPos pos = new ChunkPos(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
			List<SerializedChunk.SectionData> sectionData = new ArrayList<>();
			ChunkSection[] arr = e.getValue();
			for (int i = 0; i < arr.length; i++) {
				ChunkSection sec = arr[i];
				if (sec == null || sec.isEmpty()) continue;
				sec.calculateCounts();
				sectionData.add(new SerializedChunk.SectionData(
					minSectionY + i, sec, new ChunkNibbleArray(), new ChunkNibbleArray()));
			}
			Map<Heightmap.Type, long[]> heightmaps = new EnumMap<>(Heightmap.Type.class);
			List<NbtCompound> ents = destEntities.getOrDefault(key, List.of());
			List<NbtCompound> bes = destBlockEntities.getOrDefault(key, List.of());
			SerializedChunk sc = new SerializedChunk(
				biomes,
				pos,
				minSectionY,
				System.currentTimeMillis(),
				0L,
				ChunkStatus.FULL,
				null,
				null,
				UpgradeData.NO_UPGRADE_DATA,
				null,
				heightmaps,
				new Chunk.TickSchedulers(List.of(), List.of()),
				new it.unimi.dsi.fastutil.shorts.ShortList[0],
				false,
				sectionData,
				ents,
				bes,
				new NbtCompound()
			);
			NbtCompound nbt = sc.serialize();
			nbt.remove("structures");
			nbt.put("structures", emptyStructures());
			dev.greencorps.backup.ChunkCapture.ensurePlayableStatus(nbt);
			out.put(key, nbt);
		}

		// Orphan BEs/entities whose chunk had only entities (no blocks)
		for (Map.Entry<Long, List<NbtCompound>> e : destBlockEntities.entrySet()) {
			if (out.containsKey(e.getKey())) continue;
			out.put(e.getKey(), emptyChunk(e.getKey(), biomes, minSectionY,
				destEntities.getOrDefault(e.getKey(), List.of()), e.getValue()));
		}
		for (Map.Entry<Long, List<NbtCompound>> e : destEntities.entrySet()) {
			if (out.containsKey(e.getKey())) continue;
			out.put(e.getKey(), emptyChunk(e.getKey(), biomes, minSectionY,
				e.getValue(), destBlockEntities.getOrDefault(e.getKey(), List.of())));
		}

		LOG.info("restack yaw={} shift={} blocks={} entities={} chunks {}→{}",
			tx.yawDegrees, tx.shiftToOrigin, blocks, entities, sourceChunks.size(), out.size());
		return new RestackResult(out, blocks, entities);
	}

	private static NbtCompound emptyChunk(long key, Registry<Biome> biomes, int minSectionY,
										  List<NbtCompound> ents, List<NbtCompound> bes) {
		ChunkPos pos = new ChunkPos(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
		SerializedChunk sc = new SerializedChunk(
			biomes, pos, minSectionY, System.currentTimeMillis(), 0L, ChunkStatus.FULL,
			null, null, UpgradeData.NO_UPGRADE_DATA, null, new EnumMap<>(Heightmap.Type.class),
			new Chunk.TickSchedulers(List.of(), List.of()),
			new it.unimi.dsi.fastutil.shorts.ShortList[0], false,
			List.of(), ents, bes, new NbtCompound());
		NbtCompound nbt = sc.serialize();
		nbt.put("structures", emptyStructures());
		dev.greencorps.backup.ChunkCapture.ensurePlayableStatus(nbt);
		return nbt;
	}

	private static NbtCompound emptyStructures() {
		NbtCompound s = new NbtCompound();
		s.put("structures", new NbtCompound());
		s.put("References", new NbtCompound());
		return s;
	}

	private static void place(Map<Long, ChunkSection[]> dest, Registry<Biome> biomes,
							  int x, int y, int z, BlockState state, int minSectionY) {
		int cx = x >> 4;
		int cz = z >> 4;
		long key = ChunkPos.toLong(cx, cz);
		int sectionIndex = (y >> 4) - minSectionY;
		if (sectionIndex < 0) return;
		ChunkSection[] arr = dest.computeIfAbsent(key, k -> new ChunkSection[0]);
		if (sectionIndex >= arr.length) {
			ChunkSection[] grown = new ChunkSection[sectionIndex + 1];
			System.arraycopy(arr, 0, grown, 0, arr.length);
			arr = grown;
			dest.put(key, arr);
		}
		ChunkSection section = arr[sectionIndex];
		if (section == null) {
			section = new ChunkSection(biomes);
			arr[sectionIndex] = section;
		}
		section.setBlockState(x & 15, y & 15, z & 15, state == null ? Blocks.AIR.getDefaultState() : state);
	}

	static NbtCompound transformEntity(NbtCompound ent, ExportTransform tx) {
		if (ent == null || !ent.contains("Pos", NbtElement.LIST_TYPE)) return null;
		NbtCompound copy = ent.copy();
		NbtList pos = copy.getList("Pos", NbtElement.DOUBLE_TYPE);
		if (pos.size() < 3) return null;
		double[] xz = tx.transformEntityXZ(pos.getDouble(0), pos.getDouble(2));
		NbtList npos = new NbtList();
		npos.add(net.minecraft.nbt.NbtDouble.of(xz[0]));
		npos.add(net.minecraft.nbt.NbtDouble.of(pos.getDouble(1)));
		npos.add(net.minecraft.nbt.NbtDouble.of(xz[1]));
		copy.put("Pos", npos);
		if (copy.contains("Rotation", NbtElement.LIST_TYPE)) {
			NbtList rot = copy.getList("Rotation", NbtElement.FLOAT_TYPE);
			if (rot.size() >= 1) {
				NbtList nrot = new NbtList();
				nrot.add(net.minecraft.nbt.NbtFloat.of(tx.transformYaw(rot.getFloat(0))));
				if (rot.size() >= 2) nrot.add(net.minecraft.nbt.NbtFloat.of(rot.getFloat(1)));
				copy.put("Rotation", nrot);
			}
		}
		if (copy.contains("TileX", NbtElement.INT_TYPE)) {
			BlockPos tp = tx.transformPos(new BlockPos(copy.getInt("TileX"), copy.getInt("TileY"), copy.getInt("TileZ")));
			copy.putInt("TileX", tp.getX());
			copy.putInt("TileY", tp.getY());
			copy.putInt("TileZ", tp.getZ());
		}
		// Item frames ("Facing", any of 6 directions) and paintings ("facing", horizontal) turn with the blocks.
		if (copy.contains("Facing", NbtElement.BYTE_TYPE)) {
			net.minecraft.util.math.Direction d = net.minecraft.util.math.Direction.byId(copy.getByte("Facing"));
			copy.putByte("Facing", (byte) tx.blockRotation.rotate(d).getId());
		}
		if (copy.contains("facing", NbtElement.BYTE_TYPE)) {
			net.minecraft.util.math.Direction d = net.minecraft.util.math.Direction.fromHorizontalQuarterTurns(copy.getByte("facing"));
			copy.putByte("facing", (byte) tx.blockRotation.rotate(d).getHorizontalQuarterTurns());
		}
		return copy;
	}
}
