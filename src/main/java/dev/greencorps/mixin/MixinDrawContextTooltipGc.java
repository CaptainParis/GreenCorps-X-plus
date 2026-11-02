package dev.greencorps.mixin;

import dev.greencorps.gui.tooltip.GcStorageTooltipComponent;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Tooltip+ frame-style shulker / ender chest previews replace the whole tooltip: no vanilla background,
 * item lines or duplicate title. Positioned and layered like a vanilla tooltip (z 400).
 */
@Mixin(DrawContext.class)
public abstract class MixinDrawContextTooltipGc {
	@Shadow
	@Final
	private MatrixStack matrices;

	@Shadow
	public abstract int getScaledWindowWidth();

	@Shadow
	public abstract int getScaledWindowHeight();

	@Inject(
		method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;IILnet/minecraft/client/gui/tooltip/TooltipPositioner;Lnet/minecraft/util/Identifier;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void greencorps$standaloneStorage(TextRenderer textRenderer, List<TooltipComponent> components, int x, int y,
		TooltipPositioner positioner, Identifier texture, CallbackInfo ci) {
		GcStorageTooltipComponent frame = null;
		for (TooltipComponent c : components) {
			if (c instanceof GcStorageTooltipComponent s && s.standalone()) {
				frame = s;
				break;
			}
		}
		if (frame == null) return;
		int w = frame.getWidth(textRenderer);
		int h = frame.getHeight(textRenderer);
		Vector2ic pos = positioner.getPosition(getScaledWindowWidth(), getScaledWindowHeight(), x, y, w, h);
		matrices.push();
		matrices.translate(0f, 0f, 400f);
		frame.drawItems(textRenderer, pos.x(), pos.y(), w, h, (DrawContext) (Object) this);
		matrices.pop();
		ci.cancel();
	}
}
