package dev.greencorps.gui.map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/**
 * Reflection surface into xaero.map.gui.GuiMap.
 * Yarn: use {@link net.minecraft.client.util.Window#getFramebufferWidth()} (Mojmap's getWidth).
 */
public final class GcMapView {
	private static Field cameraXField;
	private static Field cameraZField;
	private static Field scaleField;
	private static Field lastViewedDimField;
	private static boolean resolved;
	private static boolean available;

	public final double cameraX;
	public final double cameraZ;
	public final double pxPerBlockGui;
	public final double centerX;
	public final double centerZ;
	public final int screenW;
	public final int screenH;
	public final String dimensionId;

	private GcMapView(double cameraX, double cameraZ, double pxPerBlockGui,
					  double centerX, double centerZ, int screenW, int screenH, String dimensionId) {
		this.cameraX = cameraX;
		this.cameraZ = cameraZ;
		this.pxPerBlockGui = pxPerBlockGui;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.screenW = screenW;
		this.screenH = screenH;
		this.dimensionId = dimensionId;
	}

	public static boolean isGuiMap(Screen screen) {
		return screen != null && screen.getClass().getName().equals("xaero.map.gui.GuiMap");
	}

	public static GcMapView from(Screen screen) {
		if (!isGuiMap(screen)) return null;
		resolve();
		if (!available) return null;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			double cameraX = cameraXField.getDouble(screen);
			double cameraZ = cameraZField.getDouble(screen);
			double nativeScale = scaleField.getDouble(screen);
			if (nativeScale <= 0 || Double.isNaN(nativeScale)) return null;

			double guiScale = Math.max(1.0, mc.getWindow().getScaleFactor());
			double pxPerBlockGui = nativeScale / guiScale;
			// Xaero uses framebuffer pixels; Yarn getWidth() is the window, not the framebuffer.
			double centerX = (mc.getWindow().getFramebufferWidth() / 2.0) / guiScale;
			double centerZ = (mc.getWindow().getFramebufferHeight() / 2.0) / guiScale;
			int screenW = (int) Math.round(mc.getWindow().getFramebufferWidth() / guiScale);
			int screenH = (int) Math.round(mc.getWindow().getFramebufferHeight() / guiScale);

			return new GcMapView(cameraX, cameraZ, pxPerBlockGui, centerX, centerZ,
				screenW, screenH, resolveDimension(screen, mc));
		} catch (Exception e) {
			return null;
		}
	}

	public double screenX(double worldX) {
		return centerX + (worldX - cameraX) * pxPerBlockGui;
	}

	public double screenZ(double worldZ) {
		return centerZ + (worldZ - cameraZ) * pxPerBlockGui;
	}

	public double screenToWorldX(double screenX) {
		return cameraX + (screenX - centerX) / pxPerBlockGui;
	}

	public double screenToWorldZ(double screenY) {
		return cameraZ + (screenY - centerZ) / pxPerBlockGui;
	}

	private static String resolveDimension(Screen screen, MinecraftClient mc) {
		try {
			if (lastViewedDimField != null) {
				@SuppressWarnings("unchecked")
				RegistryKey<World> dim = (RegistryKey<World>) lastViewedDimField.get(screen);
				if (dim != null) return dim.getValue().toString();
			}
		} catch (Exception ignored) {}
		if (mc.world != null) return mc.world.getRegistryKey().getValue().toString();
		return "minecraft:overworld";
	}

	private static void resolve() {
		if (resolved) return;
		resolved = true;
		try {
			Class<?> cls = Class.forName("xaero.map.gui.GuiMap");
			cameraXField = cls.getDeclaredField("cameraX");
			cameraZField = cls.getDeclaredField("cameraZ");
			scaleField = cls.getDeclaredField("scale");
			cameraXField.setAccessible(true);
			cameraZField.setAccessible(true);
			scaleField.setAccessible(true);
			try {
				lastViewedDimField = cls.getDeclaredField("lastViewedDimensionId");
				lastViewedDimField.setAccessible(true);
			} catch (NoSuchFieldException e) {
				lastViewedDimField = null;
			}
			available = true;
		} catch (Exception e) {
			available = false;
		}
	}
}
