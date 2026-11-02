package dev.greencorps.util;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Vanilla breeze trial-spawner podium (1.21.4
 * {@code trial_chambers/spawner/breeze/breeze.nbt}):
 * trial_spawner at (x,y,z) with chiseled_tuff at the four corners one block below.
 */
public final class BreezeMatcher {
	private BreezeMatcher() {
	}

	public static boolean isBreezeTrialSpawner(World world, BlockPos spawnerPos) {
		if (world == null || spawnerPos == null) return false;
		if (!(world.getBlockEntity(spawnerPos) instanceof TrialSpawnerBlockEntity)) return false;
		if (!world.getBlockState(spawnerPos).isOf(Blocks.TRIAL_SPAWNER)) return false;

		return isChiseledTuff(world, spawnerPos.add(-1, -1, -1))
			&& isChiseledTuff(world, spawnerPos.add(-1, -1, 1))
			&& isChiseledTuff(world, spawnerPos.add(1, -1, -1))
			&& isChiseledTuff(world, spawnerPos.add(1, -1, 1));
	}

	public static boolean isBreezeTrialSpawner(World world, BlockEntity be) {
		if (!(be instanceof TrialSpawnerBlockEntity)) return false;
		return isBreezeTrialSpawner(world, be.getPos());
	}

	private static boolean isChiseledTuff(World world, BlockPos pos) {
		return world.getBlockState(pos).isOf(Blocks.CHISELED_TUFF);
	}
}
