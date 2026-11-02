package dev.greencorps.mixin;

import dev.greencorps.gui.map.HomeMapMenu;
import dev.greencorps.gui.map.StasisMapMenu;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;
import xaero.map.mods.gui.WaypointReader;

import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * Adds "Set As Home (Backup)" to Xaero waypoint right-click menus.
 */
@Mixin(value = WaypointReader.class, remap = false)
public abstract class MixinWaypointReaderHome {
	private static Field rightClickDimField;

	@Inject(method = "getRightClickOptions", at = @At("RETURN"))
	private void greencorps$appendSetHome(Waypoint w, IRightClickableElement target,
			CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
		ArrayList<RightClickOption> options = cir.getReturnValue();
		if (options == null || w == null || target == null) return;
		HomeMapMenu.appendWaypoint(target, options, w, resolveDimId());
		StasisMapMenu.appendWaypoint(target, options, w, resolveDimId());
	}

	private static String resolveDimId() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return "minecraft:overworld";
		Screen screen = mc.currentScreen;
		if (screen != null && screen.getClass().getName().equals("xaero.map.gui.GuiMap")) {
			try {
				if (rightClickDimField == null) {
					rightClickDimField = screen.getClass().getDeclaredField("rightClickDim");
					rightClickDimField.setAccessible(true);
				}
				@SuppressWarnings("unchecked")
				RegistryKey<World> dim = (RegistryKey<World>) rightClickDimField.get(screen);
				if (dim != null) return dim.getValue().toString();
			} catch (Exception ignored) {}
		}
		if (mc.world != null) return mc.world.getRegistryKey().getValue().toString();
		return "minecraft:overworld";
	}
}
