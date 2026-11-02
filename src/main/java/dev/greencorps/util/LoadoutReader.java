package dev.greencorps.util;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Rockets, elytras and totems on the player, shared by Companion stats and
 * Discord Pings so both report the same numbers.
 * Elytra pool uses the Meteor ElytraSwap durability threshold when present.
 */
public final class LoadoutReader {
	/** Fallback when ElytraSwap (or its durability setting) is absent. */
	public static final int DEFAULT_ELYTRA_SWAP_THRESHOLD = 2;

	/**
	 * @param rockets           rockets loose in the inventory and offhand
	 * @param rocketsAccessible rockets plus those in carried shulkers and the ender chest
	 * @param elytraPct         worn elytra durability %, or -1 when none is worn
	 * @param elytraCount       worn plus carried elytras
	 * @param elytraPoolPct     usable pool % (swap-threshold aware); -1 when no elytras at all
	 * @param usableSpares      carried (not worn) elytras above the swap threshold
	 * @param totems            totems of undying in the inventory and offhand
	 */
	public record Loadout(int rockets, int rocketsAccessible, int elytraPct, int elytraCount,
						  int elytraPoolPct, int usableSpares, int totems) {}

	private LoadoutReader() {}

	public static Loadout read(ClientPlayerEntity player) {
		int threshold = elytraSwapThresholdPct();
		double poolSum = 0;
		int poolN = 0;

		ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);
		int worn = 0;
		int elytraPct = -1;
		if (chest.isOf(Items.ELYTRA)) {
			worn = 1;
			elytraPct = remainingDurabilityPct(chest);
			// Only elytras strictly above the swap threshold contribute to the pool.
			if (elytraPct > threshold) {
				poolSum += usableElytraSlice(elytraPct, threshold);
				poolN++;
			}
		}

		int immediate = 0;
		int nested = 0;
		int carried = 0;
		int spares = 0;
		int totems = 0;
		PlayerInventory inv = player.getInventory();
		// Main slots and the offhand once each (inv.size() also spans armor and offhand).
		ItemStack[] stacks = new ItemStack[PlayerInventory.MAIN_SIZE + 1];
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) stacks[i] = inv.getStack(i);
		stacks[PlayerInventory.MAIN_SIZE] = player.getOffHandStack();
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty()) continue;
			if (stack.isOf(Items.FIREWORK_ROCKET)) immediate += stack.getCount();
			else nested += countRocketsInContainer(stack);
			if (stack.isOf(Items.TOTEM_OF_UNDYING)) totems += stack.getCount();
			if (stack.isOf(Items.ELYTRA)) {
				carried += stack.getCount();
				int rem = remainingDurabilityPct(stack);
				if (rem > threshold) {
					double slice = usableElytraSlice(rem, threshold);
					for (int n = 0; n < stack.getCount(); n++) {
						poolSum += slice;
						poolN++;
						spares++;
					}
				}
			}
		}
		int count = worn + carried;
		int pool;
		// -1 only when no elytras at all; 0% when none clear the swap threshold.
		if (count <= 0) pool = -1;
		else if (poolN <= 0) pool = 0;
		else pool = Math.max(0, Math.min(100, (int) Math.round(100.0 * poolSum / poolN)));
		return new Loadout(immediate, immediate + nested + countRocketsInEnder(player),
			elytraPct, count, pool, spares, totems);
	}

	/** Remaining durability percent 0–100 for an elytra stack. */
	static int remainingDurabilityPct(ItemStack stack) {
		int max = stack.getMaxDamage();
		if (max <= 0) return 100;
		return Math.max(0, Math.min(100, (int) Math.round(100.0 * (max - stack.getDamage()) / max)));
	}

	/**
	 * Fraction of one elytra still usable above the swap threshold
	 * ({@code max(0, remaining - threshold) / max(1, 100 - threshold)}).
	 */
	private static double usableElytraSlice(int remainingPct, int thresholdPct) {
		int thr = Math.max(0, Math.min(99, thresholdPct));
		return Math.max(0, remainingPct - thr) / (double) Math.max(1, 100 - thr);
	}

	/**
	 * Meteor ElytraSwap "Durability Threshold" (percent remaining). Tries common
	 * module/setting name spellings; falls back to {@link #DEFAULT_ELYTRA_SWAP_THRESHOLD}.
	 */
	private static int elytraSwapThresholdPct() {
		try {
			Modules modules = Modules.get();
			if (modules == null) return DEFAULT_ELYTRA_SWAP_THRESHOLD;
			Module m = modules.get("ElytraSwap");
			if (m == null) m = modules.get("elytra-swap");
			if (m == null) return DEFAULT_ELYTRA_SWAP_THRESHOLD;
			Setting<?> s = m.settings.get("Durability Threshold");
			if (s == null) s = m.settings.get("durability-threshold");
			if (s == null) s = m.settings.get("durability");
			if (s == null) return DEFAULT_ELYTRA_SWAP_THRESHOLD;
			Object v = s.get();
			if (v instanceof Number n) {
				return Math.max(0, Math.min(99, n.intValue()));
			}
		} catch (RuntimeException ignored) {
			// Addon absent or settings shape unexpected.
		}
		return DEFAULT_ELYTRA_SWAP_THRESHOLD;
	}

	/** Last seen ender chest contents ({@link EnderChestCache}); the client never gets the player's own copy. */
	private static int countRocketsInEnder(ClientPlayerEntity player) {
		try {
			ItemStack[] ender = EnderChestCache.items();
			if (ender == null) return 0;
			int n = 0;
			for (ItemStack stack : ender) {
				if (stack == null || stack.isEmpty()) continue;
				if (stack.isOf(Items.FIREWORK_ROCKET)) n += stack.getCount();
				else n += countRocketsInContainer(stack);
			}
			return n;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static int countRocketsInContainer(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) {
			return 0;
		}
		ContainerComponent contents = stack.get(DataComponentTypes.CONTAINER);
		if (contents == null) return 0;
		int n = 0;
		for (ItemStack inner : contents.iterateNonEmpty()) {
			if (inner.isOf(Items.FIREWORK_ROCKET)) n += inner.getCount();
		}
		return n;
	}
}
