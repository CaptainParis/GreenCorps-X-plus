package dev.greencorps.mixin;

import dev.greencorps.modules.ItemFrameControl;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skips item frames {@link ItemFrameControl} hides (or replaces with ESP) before their render state is built. */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererGc<T extends Entity, S extends EntityRenderState> {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void greencorps$hideItemFrames(T entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof ItemFrameEntity frame && ItemFrameControl.actionFor(frame).skipsVanilla()) cir.setReturnValue(false);
	}
}
