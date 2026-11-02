package dev.greencorps.mixin;

import dev.greencorps.gui.map.GcMapView;
import dev.greencorps.gui.map.HomeMapRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the home hover card above Screen widgets when GuiMap is open. */
@Mixin(Screen.class)
public abstract class MixinScreenRenderGc {
	@Unique
	private static final Logger greencorps$LOG = LoggerFactory.getLogger("greencorps/map");
	@Unique
	private static boolean greencorps$loggedCardFail;

	@Inject(method = "render", at = @At("TAIL"))
	private void greencorps$homeCard(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (!GcMapView.isGuiMap(self)) return;
		try {
			// Uses HomeMapRenderer.lastView from the mid-frame inject (same GuiMap frame).
			HomeMapRenderer.renderCard(self, context, mouseX, mouseY);
		} catch (Throwable t) {
			if (!greencorps$loggedCardFail) {
				greencorps$loggedCardFail = true;
				greencorps$LOG.warn("Home map card skipped: {}", t.toString());
			}
		}
	}
}
