package dev.greencorps.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.VaultBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.VaultBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Trial chamber vaults: ominous (expensive / heavy-core) vs regular copper.
 */
public final class VaultMatcher {
	private VaultMatcher() {
	}

	public static boolean isOminousVault(World world, BlockPos pos) {
		if (world == null || pos == null) return false;
		BlockState state = world.getBlockState(pos);
		if (!state.isOf(Blocks.VAULT)) return false;
		if (!state.contains(VaultBlock.OMINOUS) || !state.get(VaultBlock.OMINOUS)) return false;
		return world.getBlockEntity(pos) instanceof VaultBlockEntity;
	}

	public static boolean isOminousVault(World world, BlockEntity be) {
		if (!(be instanceof VaultBlockEntity)) return false;
		return isOminousVault(world, be.getPos());
	}

	public static boolean isRegularVault(World world, BlockPos pos) {
		if (world == null || pos == null) return false;
		BlockState state = world.getBlockState(pos);
		if (!state.isOf(Blocks.VAULT)) return false;
		if (state.contains(VaultBlock.OMINOUS) && state.get(VaultBlock.OMINOUS)) return false;
		return world.getBlockEntity(pos) instanceof VaultBlockEntity;
	}

	public static boolean isRegularVault(World world, BlockEntity be) {
		if (!(be instanceof VaultBlockEntity)) return false;
		return isRegularVault(world, be.getPos());
	}

	/** @deprecated use {@link #isOminousVault} */
	@Deprecated
	public static boolean isBlueTrialVault(World world, BlockPos pos) {
		return isOminousVault(world, pos);
	}

	/** @deprecated use {@link #isOminousVault} */
	@Deprecated
	public static boolean isBlueTrialVault(World world, BlockEntity be) {
		return isOminousVault(world, be);
	}
}
