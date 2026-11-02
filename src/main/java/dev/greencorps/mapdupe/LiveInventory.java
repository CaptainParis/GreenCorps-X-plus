package dev.greencorps.mapdupe;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.BundleItem;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/** Reads the open screen into an {@link InvSim}. */
public final class LiveInventory {
	/** Copy All / All bundles skip filters. */
	public record Filters(boolean skipUnlocked, boolean skipBlank, boolean skipUnnamed) {}

	private LiveInventory() {}

	public static Station stationFor(Screen screen) {
		if (screen instanceof InventoryScreen) return Station.INVENTORY;
		if (screen instanceof CraftingScreen) return Station.CRAFTING;
		if (screen instanceof CartographyTableScreen) return Station.CARTOGRAPHY;
		return null;
	}

	/** Screens Sort Maps works in: the copy stations plus an open shulker box. */
	public static Station sortStationFor(Screen screen) {
		if (screen instanceof ShulkerBoxScreen) return Station.SHULKER;
		return stationFor(screen);
	}

	/** Null when the screen's slots do not match the vanilla layout (modded screen). */
	public static InvSim read(ScreenHandler handler, Station station, Filters filters, World world) {
		if (handler.slots.size() != station.slotCount) return null;
		InvSim sim = new InvSim(station);
		for (int id = 0; id < station.slotCount; id++) {
			if (id == station.result) continue;
			sim.set(id, toSim(handler.getSlot(id).getStack(), filters, world));
		}
		sim.setCursor(toSim(handler.getCursorStack(), filters, world));
		return sim;
	}

	/** Stackability identity: item + components (count excluded). */
	public static long key(ItemStack stack) {
		if (stack.isEmpty()) return 0L;
		return ((long) System.identityHashCode(stack.getItem()) << 32) ^ (ItemStack.hashCode(stack) & 0xFFFFFFFFL);
	}

	/**
	 * A bundle's identity without its contents (colour, name, …). The item hash includes the contents, so with
	 * {@link #key} a bundle became "a different item" the moment a map came out, and the click check stopped the
	 * job before the map went back in. Contents are compared separately ({@link SimStack#sameAs}).
	 */
	static long bundleKey(ItemStack stack) {
		ItemStack bare = stack.copy();
		bare.set(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
		return key(bare);
	}

	static SimStack toSim(ItemStack stack, Filters filters, World world) {
		if (stack.isEmpty()) return SimStack.NONE;
		long key = key(stack);
		if (stack.isOf(Items.FILLED_MAP)) {
			return SimStack.filledMap(key, stack.getCount()).skipped(skip(stack, filters, world));
		}
		if (stack.isOf(Items.MAP)) return SimStack.emptyMap(key, stack.getCount());
		BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
		if (contents != null) {
			List<SimStack> inner = new ArrayList<>();
			for (ItemStack s : contents.iterate()) inner.add(toSim(s, filters, world));
			return SimStack.bundle(bundleKey(stack), inner).withContents(inner, BundleItem.getSelectedStackIndex(stack));
		}
		return SimStack.other(key, stack.getCount(), stack.getMaxCount());
	}

	private static boolean skip(ItemStack map, Filters filters, World world) {
		if (filters == null) return false;
		if (filters.skipUnnamed() && !map.contains(DataComponentTypes.CUSTOM_NAME)) return true;
		MapState state = world == null ? null : FilledMapItem.getMapState(map, world);
		if (state == null) return false;
		if (filters.skipUnlocked() && !state.locked) return true;
		return filters.skipBlank() && isBlank(state);
	}

	private static boolean isBlank(MapState state) {
		if (state.colors == null) return true;
		for (byte b : state.colors) {
			if (b != 0) return false;
		}
		return true;
	}
}
