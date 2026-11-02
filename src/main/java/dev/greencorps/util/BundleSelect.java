package dev.greencorps.util;

import dev.greencorps.modules.MapDuperPlus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket;

/**
 * Selects a bundle stack on the client and the server.
 * <p>
 * Vanilla toggles: selecting the already selected index clears it, so a select sent while the server's
 * selection differs from ours picks the wrong stack. Clearing first (-1 always clears) makes every select
 * land on {@code index} whatever either side had. 2b2t counts the index from the other end, so the server
 * gets the mirrored index there (Map Duper+ <i>bundle-order</i>; Auto = on for 2b2t).
 */
public final class BundleSelect {
	private BundleSelect() {}

	/** @param index stack index in the client's order, or -1 to clear */
	public static void select(MinecraftClient mc, int slotId, ItemStack bundle, int index) {
		if (mc.getNetworkHandler() == null) return;
		BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		int size = contents == null ? 0 : contents.size();
		BundleItem.setSelectedStackIndex(bundle, -1);
		mc.getNetworkHandler().sendPacket(new BundleItemSelectedC2SPacket(slotId, -1));
		if (index < 0 || index >= size) return;
		BundleItem.setSelectedStackIndex(bundle, index);
		mc.getNetworkHandler().sendPacket(new BundleItemSelectedC2SPacket(slotId, serverIndex(mc, index, size)));
	}

	public static int serverIndex(MinecraftClient mc, int index, int size) {
		return reversed(mc) ? size - 1 - index : index;
	}

	public static boolean reversed(MinecraftClient mc) {
		MapDuperPlus duper = MapDuperPlus.get();
		if (duper != null) return duper.reverseBundleIndex();
		return WorldAllowlist.allowsJoin(WorldAllowlist.joinedHost(mc), WorldAllowlist.proxyAddresses());
	}
}
