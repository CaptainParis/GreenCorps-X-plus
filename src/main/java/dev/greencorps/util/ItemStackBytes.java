package dev.greencorps.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.ComponentChanges;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;

import java.io.ByteArrayOutputStream;

/**
 * Rough on-disk / packet size estimate for tooltip footers (Rusher-style "N bytes").
 */
public final class ItemStackBytes {
	private ItemStackBytes() {}

	public static int estimate(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc != null && mc.player != null) {
				var ops = RegistryOps.of(NbtOps.INSTANCE, mc.player.getRegistryManager());
				var encoded = ItemStack.CODEC.encodeStart(ops, stack);
				if (encoded.result().isPresent() && encoded.result().get() instanceof NbtCompound compound) {
					return byteSize(compound);
				}
			}
			ComponentChanges changes = stack.getComponentChanges();
			return Math.max(8, changes.toString().length());
		} catch (Throwable t) {
			return Math.max(8, stack.getItem().toString().length() + stack.getCount());
		}
	}

	private static int byteSize(NbtCompound nbt) {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			NbtIo.writeCompressed(nbt, bos);
			return bos.size();
		} catch (Throwable t) {
			return Math.max(8, nbt.toString().length());
		}
	}
}
