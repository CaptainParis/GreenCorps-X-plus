package dev.greencorps.mixin;

import dev.greencorps.modules.ItemFrameControl;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

/** Item Frame Control click-through: the crosshair entity raycast ignores hidden frames and filled frames on storage blocks (1.21.4 path). */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererCrosshairGc {
	@ModifyArg(
		method = "findCrosshairTarget",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/entity/projectile/ProjectileUtil;raycast(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;D)Lnet/minecraft/util/hit/EntityHitResult;"
		)
	)
	private Predicate<Entity> greencorps$skipIgnoredFrames(Predicate<Entity> filter) {
		return ItemFrameControl.skipIgnoredFrames(filter);
	}
}
