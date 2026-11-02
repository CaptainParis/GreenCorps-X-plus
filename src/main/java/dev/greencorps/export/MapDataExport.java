package dev.greencorps.export;

import dev.greencorps.backup.ChunkCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes {@code data/map_&lt;id&gt;.dat} from client {@link MapState} so filled maps
 * in item frames / inventory display in Singleplayer.
 * Centers are rewritten into dest-space (never live 2b2t coordinates).
 */
public final class MapDataExport {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/map-export");

	private MapDataExport() {}

	public static void writeMaps(Path destWorld, Set<Integer> mapIds) {
		writeMaps(destWorld, mapIds, null);
	}

	public static void writeMaps(Path destWorld, Set<Integer> mapIds, ExportTransform tx) {
		if (destWorld == null || mapIds == null || mapIds.isEmpty()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) return;
		RegistryWrapper.WrapperLookup regs = mc.world.getRegistryManager();
		Path dataDir = destWorld.resolve("data");
		try {
			Files.createDirectories(dataDir);
		} catch (IOException e) {
			LOG.warn("map data dir: {}", e.toString());
			return;
		}
		int written = 0;
		for (Integer id : mapIds) {
			if (id == null) continue;
			try {
				MapState state = FilledMapItem.getMapState(new MapIdComponent(id), mc.world);
				if (state == null) continue;
				NbtCompound nbt = state.writeNbt(new NbtCompound(), regs);
				if (nbt == null || nbt.isEmpty()) continue;
				rewriteCenters(nbt, tx);
				Path file = dataDir.resolve("map_" + id + ".dat");
				NbtCompound root = new NbtCompound();
				root.put("data", nbt);
				root.putInt("DataVersion", dev.greencorps.backup.ChunkObject.MC_DATA_VERSION);
				NbtIo.writeCompressed(root, file);
				written++;
			} catch (Exception e) {
				LOG.warn("map {} export: {}", id, e.toString());
			}
		}
		if (written > 0) {
			LOG.info("wrote {} map data file(s) to {}", written, dataDir.getFileName());
		}
	}

	/** Rewrite centerX/centerZ (+ banner positions) into dest space; strip if no transform. */
	public static void rewriteCenters(NbtCompound mapData, ExportTransform tx) {
		if (mapData == null) return;
		if (tx == null) {
			mapData.putInt("centerX", 0);
			mapData.putInt("centerZ", 0);
			stripDecorations(mapData);
			return;
		}
		int cx = mapData.contains("centerX") ? mapData.getInt("centerX") : 0;
		int cz = mapData.contains("centerZ") ? mapData.getInt("centerZ") : 0;
		int[] t = tx.transformMapCenter(cx, cz);
		mapData.putInt("centerX", t[0]);
		mapData.putInt("centerZ", t[1]);
		if (mapData.contains("banners", NbtElement.LIST_TYPE)) {
			NbtList banners = mapData.getList("banners", NbtElement.COMPOUND_TYPE);
			NbtList out = new NbtList();
			for (int i = 0; i < banners.size(); i++) {
				NbtCompound b = banners.getCompound(i).copy();
				if (b.contains("x") && b.contains("z")) {
					int[] bt = tx.transformBlock(b.getInt("x"), b.getInt("z"));
					b.putInt("x", bt[0]);
					b.putInt("z", bt[1]);
				}
				out.add(b);
			}
			mapData.put("banners", out);
		}
		if (mapData.contains("frames", NbtElement.LIST_TYPE)) {
			// Frame decorations leak absolute coords — strip; pixel art remains.
			mapData.put("frames", new NbtList());
		}
	}

	static void stripDecorations(NbtCompound mapData) {
		if (mapData.contains("banners")) mapData.put("banners", new NbtList());
		if (mapData.contains("frames")) mapData.put("frames", new NbtList());
	}

	/** Map ids from chunk NBT entities + local player inventory / ender chest. */
	public static Set<Integer> collectMapIds(Iterable<NbtCompound> chunkNbts) {
		Set<Integer> ids = new HashSet<>();
		if (chunkNbts != null) {
			for (NbtCompound c : chunkNbts) {
				ChunkCapture.collectFilledMapIds(c, ids);
			}
		}
		collectFromLocalPlayer(ids);
		return ids;
	}

	public static void collectFromLocalPlayer(Set<Integer> out) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.player == null || out == null) return;
		ClientPlayerEntity p = mc.player;
		for (int i = 0; i < p.getInventory().size(); i++) {
			addStack(p.getInventory().getStack(i), out);
		}
		var ender = p.getEnderChestInventory();
		for (int i = 0; i < ender.size(); i++) {
			addStack(ender.getStack(i), out);
		}
	}

	private static void addStack(ItemStack stack, Set<Integer> out) {
		if (stack == null || stack.isEmpty() || !stack.isOf(Items.FILLED_MAP)) return;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		if (mid != null) out.add(mid.id());
	}
}
