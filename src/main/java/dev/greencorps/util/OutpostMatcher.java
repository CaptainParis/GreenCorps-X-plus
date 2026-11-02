package dev.greencorps.util;

import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Ominous (illager) banners on pillager outpost watchtowers.
 * Vanilla ominous banners have 8 pattern layers and are not craftable in Java.
 */
public final class OutpostMatcher {
	public static final int MIN_PATTERN_LAYERS = 6;
	public static final int MIN_BANNERS_TO_CONFIRM = 3;
	public static final int CLUSTER_HORIZONTAL = 24;
	public static final int CLUSTER_VERTICAL = 48;

	private OutpostMatcher() {
	}

	public static boolean isOminousBanner(World world, BlockEntity be) {
		if (!(be instanceof BannerBlockEntity banner)) return false;
		BlockState state = world.getBlockState(be.getPos());
		if (!(state.getBlock() instanceof AbstractBannerBlock bannerBlock)) return false;

		DyeColor base = bannerBlock.getColor();
		if (base != DyeColor.WHITE && base != DyeColor.LIGHT_GRAY && base != DyeColor.GRAY) {
			return false;
		}

		BannerPatternsComponent patterns = banner.getPatterns();
		if (patterns == null) return false;
		return patterns.layers().size() >= MIN_PATTERN_LAYERS;
	}

	public static boolean sameCluster(BlockPos a, BlockPos b) {
		int dx = Math.abs(a.getX() - b.getX());
		int dy = Math.abs(a.getY() - b.getY());
		int dz = Math.abs(a.getZ() - b.getZ());
		return dx <= CLUSTER_HORIZONTAL && dz <= CLUSTER_HORIZONTAL && dy <= CLUSTER_VERTICAL;
	}
}
