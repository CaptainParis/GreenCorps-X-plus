package dev.greencorps.backup;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the last chest, barrel, or shulker the player opened so the next chunk
 * capture can write its items. Client chunks often omit container contents until opened.
 */
public final class ContainerCapture {
	private static final Map<BlockPos, List<ItemStack>> SNAPSHOTS = new ConcurrentHashMap<>();
	private static volatile BlockPos pending;

	public BlockPos lastInteracted() {
		noteCrosshair();
		if (!containerScreenOpen()) return null;
		return pending;
	}

	public void onScreenOpen() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.currentScreen == null || pending == null) return;
		if (!(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)) return;
		ScreenHandler handler = screen.getScreenHandler();
		int slots = containerSlots(handler);
		if (slots <= 0) return;
		List<ItemStack> stacks = new ArrayList<>(slots);
		for (int i = 0; i < slots && i < handler.slots.size(); i++) {
			stacks.add(handler.getSlot(i).getStack().copy());
		}
		SNAPSHOTS.put(pending.toImmutable(), stacks);
	}

	/** Replace or append block-entity items for positions in this chunk. */
	public static void overlay(ChunkPos chunkPos, List<NbtCompound> blockEntities, RegistryWrapper.WrapperLookup lookup) {
		if (chunkPos == null || blockEntities == null || lookup == null || SNAPSHOTS.isEmpty()) return;
		for (Map.Entry<BlockPos, List<ItemStack>> e : SNAPSHOTS.entrySet()) {
			BlockPos pos = e.getKey();
			if ((pos.getX() >> 4) != chunkPos.x || (pos.getZ() >> 4) != chunkPos.z) continue;
			NbtCompound tag = find(blockEntities, pos);
			if (tag == null) {
				tag = new NbtCompound();
				tag.putString("id", "minecraft:chest");
				tag.putInt("x", pos.getX());
				tag.putInt("y", pos.getY());
				tag.putInt("z", pos.getZ());
				blockEntities.add(tag);
			}
			tag.put("Items", items(e.getValue(), lookup));
		}
	}

	private static NbtCompound find(List<NbtCompound> blockEntities, BlockPos pos) {
		for (NbtCompound tag : blockEntities) {
			if (!tag.contains("x") || !tag.contains("y") || !tag.contains("z")) continue;
			if (tag.getInt("x") == pos.getX() && tag.getInt("y") == pos.getY() && tag.getInt("z") == pos.getZ()) {
				return tag;
			}
		}
		return null;
	}

	private static NbtList items(List<ItemStack> stacks, RegistryWrapper.WrapperLookup lookup) {
		NbtList list = new NbtList();
		for (int i = 0; i < stacks.size(); i++) {
			ItemStack stack = stacks.get(i);
			if (stack == null || stack.isEmpty()) continue;
			if (!(stack.toNbt(lookup) instanceof NbtCompound tag)) continue;
			tag.putByte("Slot", (byte) i);
			list.add(tag);
		}
		return list;
	}

	private static void noteCrosshair() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) return;
		if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
		if (hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		if (isWatched(mc.world, pos)) pending = pos.toImmutable();
	}

	private static boolean containerScreenOpen() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)) {
			return false;
		}
		return containerSlots(screen.getScreenHandler()) > 0;
	}

	private static int containerSlots(ScreenHandler handler) {
		if (handler instanceof GenericContainerScreenHandler generic) return generic.getRows() * 9;
		if (handler instanceof ShulkerBoxScreenHandler) return 27;
		return 0;
	}

	private static boolean isWatched(World world, BlockPos pos) {
		Block block = world.getBlockState(pos).getBlock();
		return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
			|| block instanceof ShulkerBoxBlock;
	}
}
