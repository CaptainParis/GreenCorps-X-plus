package dev.greencorps.backup;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gzip NBT for a loaded client chunk. Legacy captures that start with {@code '{'} are rejected
 * by the exporter.
 */
public final class ChunkCapture {
	private ChunkCapture() {}

	public static boolean isLegacyJsonBlob(byte[] raw) {
		if (raw == null) return false;
		int i = 0;
		while (i < raw.length && raw[i] >= 0 && raw[i] <= ' ') i++;
		return i < raw.length && raw[i] == '{';
	}

	public static NbtCompound readNbt(byte[] raw) throws IOException {
		if (raw == null) throw new IOException("null chunk");
		if (raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
			return NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtSizeTracker.ofUnlimitedBytes());
		}
		return NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(raw)), NbtSizeTracker.ofUnlimitedBytes());
	}

	/** Singleplayer will not load a chunk whose status is still a proto stage. */
	public static void ensurePlayableStatus(NbtCompound nbt) {
		if (nbt == null) return;
		nbt.putString("Status", "minecraft:full");
		if (!nbt.contains("DataVersion")) nbt.putInt("DataVersion", ChunkObject.MC_DATA_VERSION);
	}

	public static void collectFilledMapIds(NbtCompound root, Set<Integer> ids) {
		if (root == null || ids == null) return;
		walk(root, ids);
	}

	public static byte[] gzipWorldChunk(WorldChunk chunk) throws IOException {
		NbtCompound nbt = toNbt(chunk);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		NbtIo.writeCompressed(nbt, out);
		return out.toByteArray();
	}

	static NbtCompound toNbt(WorldChunk chunk) throws IOException {
		if (chunk == null || chunk.getWorld() == null) throw new IOException("no chunk");
		DynamicRegistryManager regs = chunk.getWorld().getRegistryManager();
		RegistryWrapper.WrapperLookup lookup = regs;
		Registry<Biome> biomes = regs.getOrThrow(RegistryKeys.BIOME);
		int minSectionY = chunk.getBottomY() >> 4;
		ChunkSection[] sections = chunk.getSectionArray();
		List<SerializedChunk.SectionData> sectionData = new ArrayList<>();
		if (sections != null) {
			for (int i = 0; i < sections.length; i++) {
				ChunkSection sec = sections[i];
				if (sec == null || sec.isEmpty()) continue;
				sec.calculateCounts();
				sectionData.add(new SerializedChunk.SectionData(
					minSectionY + i, sec, new ChunkNibbleArray(), new ChunkNibbleArray()));
			}
		}

		List<NbtCompound> blockEntities = new ArrayList<>();
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			NbtCompound tag = be.createNbtWithIdentifyingData(lookup);
			if (tag != null) blockEntities.add(tag);
		}
		ContainerCapture.overlay(chunk.getPos(), blockEntities, lookup);

		Map<Heightmap.Type, long[]> heightmaps = new EnumMap<>(Heightmap.Type.class);
		for (Map.Entry<Heightmap.Type, Heightmap> e : chunk.getHeightmaps()) {
			if (e.getKey() != null && e.getValue() != null) {
				heightmaps.put(e.getKey(), e.getValue().asLongArray());
			}
		}

		ChunkPos pos = chunk.getPos();
		SerializedChunk serialized = new SerializedChunk(
			biomes,
			pos,
			minSectionY,
			System.currentTimeMillis(),
			chunk.getInhabitedTime(),
			ChunkStatus.FULL,
			null,
			null,
			UpgradeData.NO_UPGRADE_DATA,
			null,
			heightmaps,
			new Chunk.TickSchedulers(List.of(), List.of()),
			emptyPost(),
			false,
			sectionData,
			decorationEntities(chunk),
			blockEntities,
			emptyStructures()
		);
		NbtCompound nbt = serialized.serialize();
		nbt.put("structures", emptyStructures());
		nbt.putInt("xPos", pos.x);
		nbt.putInt("zPos", pos.z);
		nbt.putInt("DataVersion", ChunkObject.MC_DATA_VERSION);
		ensurePlayableStatus(nbt);
		return nbt;
	}

	/**
	 * Item frames (with their maps), glow item frames, paintings, leash knots and armour stands standing in this
	 * chunk. They are entities, not blocks, so a capture without them lost every mapart wall.
	 */
	static List<NbtCompound> decorationEntities(WorldChunk chunk) {
		List<NbtCompound> out = new ArrayList<>();
		World world = chunk.getWorld();
		ChunkPos pos = chunk.getPos();
		Box box = new Box(pos.getStartX(), world.getBottomY(), pos.getStartZ(),
			pos.getEndX() + 1, world.getTopYInclusive() + 1, pos.getEndZ() + 1);
		for (Entity e : world.getEntitiesByClass(Entity.class, box, ChunkCapture::isDecoration)) {
			BlockPos bp = e.getBlockPos();
			if ((bp.getX() >> 4) != pos.x || (bp.getZ() >> 4) != pos.z) continue;
			NbtCompound tag = new NbtCompound();
			if (e.saveSelfNbt(tag)) out.add(tag);
		}
		return out;
	}

	public static boolean isDecoration(Entity e) {
		return e instanceof AbstractDecorationEntity || e instanceof ArmorStandEntity;
	}

	public static boolean isDecoration(EntityType<?> type) {
		return type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME || type == EntityType.PAINTING
			|| type == EntityType.LEASH_KNOT || type == EntityType.ARMOR_STAND;
	}

	private static it.unimi.dsi.fastutil.shorts.ShortList[] emptyPost() {
		return new it.unimi.dsi.fastutil.shorts.ShortList[0];
	}

	private static NbtCompound emptyStructures() {
		NbtCompound s = new NbtCompound();
		s.put("structures", new NbtCompound());
		s.put("References", new NbtCompound());
		return s;
	}

	private static void walk(NbtElement el, Set<Integer> ids) {
		if (el instanceof NbtCompound c) {
			noteMap(c, ids);
			for (String key : c.getKeys()) {
				NbtElement child = c.get(key);
				if (child != null) walk(child, ids);
			}
		} else if (el instanceof NbtList list) {
			for (int i = 0; i < list.size(); i++) {
				NbtElement child = list.get(i);
				if (child != null) walk(child, ids);
			}
		}
	}

	private static void noteMap(NbtCompound c, Set<Integer> ids) {
		if (c.contains("minecraft:map_id", NbtElement.INT_TYPE)) ids.add(c.getInt("minecraft:map_id"));
		if (c.contains("map_id", NbtElement.INT_TYPE)) {
			String id = c.contains("id", NbtElement.STRING_TYPE) ? c.getString("id") : "";
			if (id.isEmpty() || id.contains("filled_map") || c.contains("components")) ids.add(c.getInt("map_id"));
		}
		if (!c.contains("id", NbtElement.STRING_TYPE)) return;
		if (!c.getString("id").contains("filled_map")) return;
		if (!c.contains("components", NbtElement.COMPOUND_TYPE)) return;
		NbtCompound comp = c.getCompound("components");
		if (comp.contains("minecraft:map_id", NbtElement.INT_TYPE)) {
			ids.add(comp.getInt("minecraft:map_id"));
		} else if (comp.contains("minecraft:map_id", NbtElement.COMPOUND_TYPE)) {
			NbtCompound boxed = comp.getCompound("minecraft:map_id");
			if (boxed.contains("id", NbtElement.INT_TYPE)) ids.add(boxed.getInt("id"));
		}
	}
}
