package dev.greencorps.mixin;

import dev.greencorps.modules.ItemFrameControl;
import net.minecraft.client.render.entity.ItemFrameEntityRenderer;
import net.minecraft.client.render.entity.state.ItemFrameEntityRenderState;
import net.minecraft.entity.decoration.ItemFrameEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item Frame Control: "Hide frame" drops the border; "Contents only" draws the frame as an empty frame. */
@Mixin(ItemFrameEntityRenderer.class)
public abstract class MixinItemFrameEntityRendererGc {
	@Inject(
		method = "updateRenderState(Lnet/minecraft/entity/decoration/ItemFrameEntity;Lnet/minecraft/client/render/entity/state/ItemFrameEntityRenderState;F)V",
		at = @At("TAIL")
	)
	private void greencorps$hideContents(ItemFrameEntity frame, ItemFrameEntityRenderState state, float tickDelta, CallbackInfo ci) {
		ItemFrameControl.Action action = ItemFrameControl.actionFor(frame);
		if (action == ItemFrameControl.Action.HIDE_FRAME) {
			// Vanilla's invisible-frame path: no border, the map or item drawn flush with the wall.
			state.invisible = true;
		} else if (action == ItemFrameControl.Action.HIDE_CONTENTS) {
			state.itemRenderState.clear();
			state.mapId = null;
		}
	}
}
