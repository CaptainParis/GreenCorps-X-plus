package dev.greencorps.anticlient;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Reads the live client world into the pure model types. Game thread only. */
public final class Sensor {
	private Sensor() {}

	public static HeldKind held(ItemStack s) {
		if (s.isEmpty()) return HeldKind.NONE;
		if (s.isOf(Items.MACE)) return HeldKind.MACE;
		if (s.isOf(Items.END_CRYSTAL)) return HeldKind.CRYSTAL;
		if (s.isOf(Items.WIND_CHARGE)) return HeldKind.WIND_CHARGE;
		return HeldKind.OTHER;
	}

	public static ChestKind chest(PlayerEntity p) {
		ItemStack s = p.getEquippedStack(EquipmentSlot.CHEST);
		if (s.isEmpty()) return ChestKind.NONE;
		return s.isOf(Items.ELYTRA) ? ChestKind.ELYTRA : ChestKind.ARMOR;
	}

	public static int latency(MinecraftClient mc, PlayerEntity p) {
		if (mc.getNetworkHandler() == null) return -1;
		PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(p.getUuid());
		return e == null ? -1 : e.getLatency();
	}

	public static Observation observe(MinecraftClient mc, PlayerEntity p, int tick) {
		boolean ground = p.isOnGround();
		double ground0 = ground ? p.getY() : groundBelow(mc, p.getX(), p.getY(), p.getZ());
		return new Observation(tick, p.getX(), p.getY(), p.getZ(), ground, ground0, p.isGliding(),
			held(p.getMainHandStack()), held(p.getOffHandStack()), chest(p), latency(mc, p));
	}

	public static double groundBelow(MinecraftClient mc, double x, double y, double z) {
		if (mc.world == null) return y - 80;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		int top = (int) Math.floor(y - 0.001);
		for (int dy = 0; dy < 96; dy++) {
			int by = top - dy;
			if (by < mc.world.getBottomY()) break;
			pos.set(bx, by, bz);
			BlockState bs = mc.world.getBlockState(pos);
			var shape = bs.getCollisionShape(mc.world, pos);
			if (!shape.isEmpty()) return by + shape.getMax(Direction.Axis.Y);
		}
		return y - 96;
	}

	public static List<CrystalView> crystals(MinecraftClient mc, ClientPlayerEntity me, double radius) {
		List<CrystalView> out = new ArrayList<>();
		if (mc.world == null) return out;
		Box box = me.getBoundingBox().expand(radius);
		for (EndCrystalEntity e : mc.world.getEntitiesByClass(EndCrystalEntity.class, box, x -> true)) {
			out.add(new CrystalView(e.getId(), e.getX(), e.getY(), e.getZ(), e.age));
		}
		return out;
	}

	/** Tools the counter plan can use, counted over the whole inventory and offhand. */
	public static Inventory inventory(ClientPlayerEntity me) {
		int wind = 0, pearls = 0, string = 0, snow = 0, totems = 0;
		var inv = me.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.main.get(i);
			if (s.isEmpty()) continue;
			if (s.isOf(Items.WIND_CHARGE)) wind += s.getCount();
			else if (s.isOf(Items.ENDER_PEARL)) pearls += s.getCount();
			else if (s.isOf(Items.STRING)) string += s.getCount();
			else if (s.isOf(Items.SNOWBALL)) snow += s.getCount();
			else if (s.isOf(Items.TOTEM_OF_UNDYING)) totems += s.getCount();
		}
		ItemStack off = me.getOffHandStack();
		if (off.isOf(Items.TOTEM_OF_UNDYING)) totems += off.getCount();
		return new Inventory(wind, pearls, string, snow, totems);
	}

	public static int mainInventoryTotems(ClientPlayerEntity me) {
		int n = 0;
		var inv = me.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.main.get(i);
			if (s.isOf(Items.TOTEM_OF_UNDYING)) n += s.getCount();
		}
		return n;
	}

	public static String heldCode(HeldKind k) {
		return switch (k) {
			case MACE -> "mace";
			case CRYSTAL -> "crystal";
			case WIND_CHARGE -> "wind";
			case OTHER -> "other";
			case NONE -> "none";
		};
	}
}
