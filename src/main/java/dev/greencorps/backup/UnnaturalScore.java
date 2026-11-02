package dev.greencorps.backup;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.world.chunk.WorldChunk;

/** Counts player-placed containers and machines so the map can tint those chunks. */
public final class UnnaturalScore {
	private UnnaturalScore() {}

	public static int scoreChunk(WorldChunk chunk) {
		if (chunk == null) return 0;
		int hits = 0;
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (be != null && unnatural(be.getType())) hits++;
		}
		return hits;
	}

	private static boolean unnatural(BlockEntityType<?> type) {
		return type == BlockEntityType.CHEST
			|| type == BlockEntityType.TRAPPED_CHEST
			|| type == BlockEntityType.BARREL
			|| type == BlockEntityType.SHULKER_BOX
			|| type == BlockEntityType.HOPPER
			|| type == BlockEntityType.DROPPER
			|| type == BlockEntityType.DISPENSER
			|| type == BlockEntityType.FURNACE
			|| type == BlockEntityType.BLAST_FURNACE
			|| type == BlockEntityType.SMOKER
			|| type == BlockEntityType.BREWING_STAND
			|| type == BlockEntityType.BEACON
			|| type == BlockEntityType.MOB_SPAWNER
			|| type == BlockEntityType.ENDER_CHEST;
	}

	/** Raise the ARGB alpha of {@code packed} as hits increase. RGB stays put. */
	public static int boostAlpha(int packed, int hits) {
		if (hits <= 0) return packed;
		int alpha = (packed >>> 24) & 0xFF;
		int boosted = Math.min(255, alpha + hits * 18);
		return (boosted << 24) | (packed & 0x00FFFFFF);
	}
}
