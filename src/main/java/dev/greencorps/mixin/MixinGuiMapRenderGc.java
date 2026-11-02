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
import xaero.map.gui.GuiMap;

/**
 * Xaero GuiMap mid-frame injects (godseye pattern).
 * TAIL is wrong — pose no longer matches map projection after waypoints/widgets.
 * Hover card stays on {@link MixinScreenRenderGc} TAIL.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinGuiMapRenderGc {
	@Unique
	private static final Logger greencorps$LOG = LoggerFactory.getLogger("greencorps/map");
	@Unique
	private static boolean greencorps$mapLayersDrawn;
	@Unique
	private static boolean greencorps$loggedFallback;
	@Unique
	private static GcMapView greencorps$frameView;

	@Inject(method = "method_25394", at = @At("HEAD"), require = 0)
	private void greencorps$beginMapFrame(DrawContext graphics, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
		greencorps$mapLayersDrawn = false;
		greencorps$frameView = GcMapView.from((Screen) (Object) this);
	}

	@Inject(
		method = "method_25394",
		at = @At(
			value = "INVOKE",
			target = "Lxaero/map/mods/SupportXaeroMinimap;renderWaypointsMenu(Lnet/minecraft/class_332;Lxaero/map/gui/GuiMap;DIIIIZZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/class_310;)Lxaero/map/element/HoveredMapElementHolder;",
			shift = At.Shift.BEFORE
		),
		require = 0
	)
	private void greencorps$renderHomes(DrawContext graphics, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
		if (greencorps$mapLayersDrawn) return;
		greencorps$mapLayersDrawn = true;
		greencorps$safeMapLayers(graphics, mouseX, mouseY);
	}

	@Inject(
		method = "method_25394",
		at = @At(
			value = "INVOKE",
			target = "Lxaero/map/gui/ScreenBase;method_25394(Lnet/minecraft/class_332;IIF)V",
			shift = At.Shift.BEFORE
		),
		require = 0
	)
	private void greencorps$renderHomesFallback(DrawContext graphics, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
		if (greencorps$mapLayersDrawn) return;
		greencorps$mapLayersDrawn = true;
		if (!greencorps$loggedFallback) {
			greencorps$loggedFallback = true;
			greencorps$LOG.debug("GuiMap primary mid-frame inject missed; using ScreenBase fallback");
		}
		greencorps$safeMapLayers(graphics, mouseX, mouseY);
	}

	/** Never crash GuiMap if the jar was hot-replaced mid-session (corrupt zip / class load). */
	@Unique
	private void greencorps$safeMapLayers(DrawContext graphics, int mouseX, int mouseY) {
		try {
			HomeMapRenderer.renderMapLayers((Screen) (Object) this, graphics, mouseX, mouseY, greencorps$frameView);
		} catch (Throwable t) {
			if (!greencorps$loggedFallback) {
				greencorps$loggedFallback = true;
				greencorps$LOG.warn("Home map layers skipped: {}", t.toString());
			}
		}
	}
}
