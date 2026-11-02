package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.world.World;

/**
 * End-only void floor and a second grid plane. Off until the player turns it on.
 * The floor sits on the dimension bottom. The second plane's height is the editable level.
 * Thick lines mark chunk edges (16 blocks). A light dotted block grid fades in near each plane.
 */
public class EndFloor extends Module {
	/** Chunk edge spacing. */
	public static final int GRID = 16;
	public static final int PATCH = 64;
	/** Vertical distance where the block grid fully fades out. */
	public static final int BLOCK_FADE_DISTANCE = 32;
	/** 33 percent of 255 — chunk lines. */
	public static final int GRID_ALPHA = 84;
	/** Light block-grid max alpha (fades with distance). */
	public static final int BLOCK_GRID_ALPHA = 40;
	/** Dash on-length in blocks. */
	public static final double DASH_ON = 0.35;
	/** Dash off-length in blocks. */
	public static final double DASH_OFF = 0.65;
	/** 10 percent. */
	public static final float FILL_ALPHA = 0.10f;
	/** Default visible range. High enough to see the void floor from the top of the End. */
	public static final int DEFAULT_VISIBLE_DISTANCE = 512;

	public enum OnElytra {
		OFF("Off"),
		END_FLOOR_ONLY("End floor only"),
		HELPER_ONLY("Helper only"),
		BOTH("Both");

		private final String label;

		OnElytra(String label) {
			this.label = label;
		}

		public boolean includesFloor() {
			return this == END_FLOOR_ONLY || this == BOTH;
		}

		public boolean includesHelper() {
			return this == HELPER_ONLY || this == BOTH;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private final SettingGroup sgRender = settings.createGroup("Render");

	private final Setting<Boolean> endFloorLayer = sgRender.add(new BoolSetting.Builder()
		.name("end-floor-layer")
		.description("Draw the void floor and its grid.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> helperLayer = sgRender.add(new BoolSetting.Builder()
		.name("helper-layer")
		.description("Draw the second grid plane.")
		.defaultValue(false)
		.build()
	);

	private final Setting<OnElytra> onElytra = sgRender.add(new EnumSetting.Builder<OnElytra>()
		.name("on-elytra")
		.description("Also show these layers while wearing an elytra.")
		.defaultValue(OnElytra.OFF)
		.build()
	);

	private final Setting<SettingColor> floorColor = sgRender.add(new ColorSetting.Builder()
		.name("floor-color")
		.description("Color of the void floor and its grid.")
		.defaultValue(new SettingColor(255, 255, 255, 255))
		.visible(this::floorSettingsVisible)
		.build()
	);

	private final Setting<SettingColor> helperColor = sgRender.add(new ColorSetting.Builder()
		.name("helper-color")
		.description("Color of the second grid plane.")
		.defaultValue(new SettingColor(70, 140, 255, 255))
		.visible(this::helperSettingsVisible)
		.build()
	);

	private final Setting<Integer> helperLevel = sgRender.add(new IntSetting.Builder()
		.name("helper-level")
		.description("Height of the second grid plane.")
		.defaultValue(275)
		.min(0)
		.max(320)
		.sliderRange(0, 320)
		.visible(this::helperSettingsVisible)
		.build()
	);

	private final Setting<Integer> radius = sgRender.add(new IntSetting.Builder()
		.name("radius")
		.description("Half-size of the floor and the helper grid, in blocks.")
		.defaultValue(PATCH)
		.min(GRID)
		.max(1024)
		.sliderRange(GRID, 256)
		.build()
	);

	private final Setting<Integer> visibleDistance = sgRender.add(new IntSetting.Builder()
		.name("visible-distance")
		.description("How far away the floor stays visible, in blocks. Raise this to keep seeing it from high up.")
		.defaultValue(DEFAULT_VISIBLE_DISTANCE)
		.min(32)
		.max(8192)
		.sliderRange(64, 2048)
		.build()
	);

	private final Color lineColor = new Color();

	public EndFloor() {
		super(GreenCorps.CATEGORY, "end-floor",
			"A void floor and helper plane in the End, with a thick chunk grid and a near-plane dotted block grid.");
	}

	public static EndFloor get() {
		return Modules.get().get(EndFloor.class);
	}

	/** True when the checkbox is on, or elytra mode includes the layer while an elytra is worn. */
	public static boolean layerActive(boolean checkbox, boolean elytraIncludesLayer, boolean wearingElytra) {
		return checkbox || (wearingElytra && elytraIncludesLayer);
	}

	public SettingColor floorColor() {
		return floorColor.get();
	}

	public SettingColor helperColor() {
		return helperColor.get();
	}

	public int helperLevel() {
		return helperLevel.get();
	}

	public int radius() {
		return Math.max(GRID, radius.get());
	}

	public int visibleDistance() {
		return Math.max(32, visibleDistance.get());
	}

	public int floorY() {
		return mc.world == null ? 0 : mc.world.getBottomY();
	}

	public boolean inEnd() {
		return mc.world != null && mc.world.getRegistryKey() == World.END;
	}

	public boolean wearingElytra() {
		return mc.player != null
			&& mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
	}

	public boolean drawFloor() {
		return layerActive(endFloorLayer.get(), onElytra.get().includesFloor(), wearingElytra());
	}

	public boolean drawHelper() {
		return layerActive(helperLayer.get(), onElytra.get().includesHelper(), wearingElytra());
	}

	private boolean floorSettingsVisible() {
		return endFloorLayer.get() || onElytra.get().includesFloor();
	}

	private boolean helperSettingsVisible() {
		return helperLayer.get() || onElytra.get().includesHelper();
	}

	/** West/north edge of the camera patch, on a 16-block line. */
	public static int patchMin(int block, int radius) {
		return alignDown(block - Math.max(GRID, radius));
	}

	/** East/south edge of the camera patch, on a 16-block line. */
	public static int patchMax(int block, int radius) {
		int r = Math.max(GRID, radius);
		return alignDown(block + r + GRID - 1);
	}

	/**
	 * Y used to draw a plane. NaN when the camera is farther than {@code visibleDistance}.
	 * When the real plane is past the view frustum, the draw Y moves in toward the camera
	 * so the floor stays on screen.
	 */
	public static double drawPlaneY(double cameraY, double planeY, int visibleDistance, double farPlane) {
		double signed = planeY - cameraY;
		double dist = Math.abs(signed);
		if (dist > visibleDistance) return Double.NaN;
		double cap = Math.max(16.0, farPlane * 0.85);
		double drawDist = Math.min(dist, cap);
		if (drawDist < 0.05) return planeY;
		return cameraY + Math.copySign(drawDist, signed);
	}

	/** True when {@link #drawPlaneY} keeps the plane on its real height. */
	public static boolean drawnAtRealHeight(double cameraY, double planeY, int visibleDistance, double farPlane) {
		double draw = drawPlaneY(cameraY, planeY, visibleDistance, farPlane);
		return !Double.isNaN(draw) && Math.abs(draw - planeY) < 0.51;
	}

	public static int alignDown(int block) {
		return Math.floorDiv(block, GRID) * GRID;
	}

	/**
	 * Alpha for the dotted block grid. 0 when farther than {@link #BLOCK_FADE_DISTANCE}
	 * from the real plane; {@link #BLOCK_GRID_ALPHA} when on the plane.
	 */
	public static int blockGridAlpha(double cameraY, double planeY) {
		double dist = Math.abs(cameraY - planeY);
		if (dist >= BLOCK_FADE_DISTANCE) return 0;
		return (int) Math.round(BLOCK_GRID_ALPHA * (1.0 - dist / BLOCK_FADE_DISTANCE));
	}

	@EventHandler
	private void onRender(Render3DEvent event) {
		if (!inEnd() || mc.player == null || mc.gameRenderer == null || mc.gameRenderer.getCamera() == null) return;
		boolean floor = drawFloor();
		boolean helper = drawHelper();
		if (!floor && !helper) return;
		double cameraY = mc.gameRenderer.getCamera().getPos().y;
		double far = mc.gameRenderer.getFarPlaneDistance();
		int r = radius();
		int vis = visibleDistance();
		int x0 = patchMin(mc.player.getBlockX(), r);
		int x1 = patchMax(mc.player.getBlockX(), r);
		int z0 = patchMin(mc.player.getBlockZ(), r);
		int z1 = patchMax(mc.player.getBlockZ(), r);
		if (floor) {
			drawGridIfReal(event, floorY(), floorColor.get(), x0, x1, z0, z1, cameraY, vis, far);
		}
		if (helper) {
			drawGridIfReal(event, helperLevel.get(), helperColor.get(), x0, x1, z0, z1, cameraY, vis, far);
		}
	}

	/** Meteor lines stay on the real height. A pulled-in plane is drawn with the fill. */
	private void drawGridIfReal(Render3DEvent event, int y, SettingColor color, int x0, int x1, int z0, int z1,
								double cameraY, int vis, double far) {
		if (!drawnAtRealHeight(cameraY, y, vis, far)) return;
		drawChunkGrid(event, y, color, x0, x1, z0, z1);
		drawBlockGrid(event, y, color, x0, x1, z0, z1, cameraY);
	}

	private void drawChunkGrid(Render3DEvent event, int y, SettingColor color, int x0, int x1, int z0, int z1) {
		lineColor.set(color);
		lineColor.a = GRID_ALPHA;
		double py = y + 0.02;
		for (int x = x0; x <= x1; x += GRID) {
			event.renderer.line(x, py, z0, x, py, z1, lineColor);
		}
		for (int z = z0; z <= z1; z += GRID) {
			event.renderer.line(x0, py, z, x1, py, z, lineColor);
		}
	}

	private void drawBlockGrid(Render3DEvent event, int y, SettingColor color, int x0, int x1, int z0, int z1,
							   double cameraY) {
		int alpha = blockGridAlpha(cameraY, y);
		if (alpha <= 0) return;
		lineColor.set(color);
		lineColor.a = alpha;
		double py = y + 0.015;
		for (int x = x0; x <= x1; x++) {
			if (Math.floorMod(x, GRID) == 0) continue;
			drawDotted(event, x, py, z0, x, py, z1);
		}
		for (int z = z0; z <= z1; z++) {
			if (Math.floorMod(z, GRID) == 0) continue;
			drawDotted(event, x0, py, z, x1, py, z);
		}
	}

	private void drawDotted(Render3DEvent event, double x0, double y0, double z0,
							double x1, double y1, double z1) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double dz = z1 - z0;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0e-4) return;
		double ux = dx / len;
		double uy = dy / len;
		double uz = dz / len;
		double period = DASH_ON + DASH_OFF;
		for (double pos = 0; pos < len; pos += period) {
			double end = Math.min(pos + DASH_ON, len);
			event.renderer.line(
				x0 + ux * pos, y0 + uy * pos, z0 + uz * pos,
				x0 + ux * end, y0 + uy * end, z0 + uz * end,
				lineColor
			);
		}
	}
}
