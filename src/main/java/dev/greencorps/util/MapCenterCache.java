package dev.greencorps.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.map.MapDecoration;
import net.minecraft.item.map.MapDecorationTypes;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.HashMap;
import java.util.Map;

/**
 * Learns map centers client-side (MapState.centerX/Z are always 0 on client).
 * Inverts player decoration bytes against local player position + scale.
 */
public final class MapCenterCache {
	public static final class Center {
		public final int x;
		public final int z;
		public final byte scale;
		public final String dimension;

		public Center(int x, int z, byte scale, String dimension) {
			this.x = x;
			this.z = z;
			this.scale = scale;
			this.dimension = dimension;
		}
	}

	private final Map<Integer, Center> centers = new HashMap<>();

	public Center get(int mapId) {
		return centers.get(mapId);
	}

	public Map<Integer, Center> all() {
		return centers;
	}

	public void learnFrom(int mapId, MapState state) {
		if (state == null) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) return;

		MapDecoration playerDeco = null;
		for (MapDecoration deco : state.getDecorations()) {
			if (deco.type() == MapDecorationTypes.PLAYER
				|| deco.type() == MapDecorationTypes.PLAYER_OFF_MAP
				|| deco.type() == MapDecorationTypes.PLAYER_OFF_LIMITS) {
				playerDeco = deco;
				break;
			}
		}
		if (playerDeco == null) return;

		byte scale = state.scale;
		int size = 128;
		double worldPerPixel = Math.pow(2, scale);
		double relX = (playerDeco.x() + 0.5) * worldPerPixel;
		double relZ = (playerDeco.z() + 0.5) * worldPerPixel;
		double centerX = mc.player.getX() - relX;
		double centerZ = mc.player.getZ() - relZ;

		int grid = (int) (size * worldPerPixel);
		if (grid <= 0) grid = size;
		int snapX = (int) Math.floor(centerX / grid) * grid + grid / 2;
		int snapZ = (int) Math.floor(centerZ / grid) * grid + grid / 2;
		String dim = mc.world.getRegistryKey().getValue().toString();
		centers.put(mapId, new Center(snapX, snapZ, scale, dim));
	}

	public NbtList toNbt() {
		NbtList list = new NbtList();
		for (Map.Entry<Integer, Center> e : centers.entrySet()) {
			NbtCompound tag = new NbtCompound();
			tag.putInt("id", e.getKey());
			tag.putInt("x", e.getValue().x);
			tag.putInt("z", e.getValue().z);
			tag.putByte("s", e.getValue().scale);
			tag.putString("d", e.getValue().dimension);
			list.add(tag);
		}
		return list;
	}

	public void fromNbt(NbtList list) {
		centers.clear();
		if (list == null) return;
		for (int i = 0; i < list.size(); i++) {
			NbtCompound tag = list.getCompound(i);
			centers.put(
				tag.getInt("id"),
				new Center(tag.getInt("x"), tag.getInt("z"), tag.getByte("s"), tag.getString("d"))
			);
		}
	}
}
