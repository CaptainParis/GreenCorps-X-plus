package dev.greencorps.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.greencorps.modules.EndFloor;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.Fog;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Camera-sized forcefield fill for the End void floor and the second plane.
 * Chunk and block grids at the real height come from the module. A plane pulled
 * in toward the camera (so it stays visible from high up) draws thick chunk lines here.
 */
public final class EndFloorRenderer {
	private EndFloorRenderer() {
	}

	public static void render(WorldRenderContext context, EndFloor mod) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null || !mod.isActive()) return;
		if (mc.world.getRegistryKey() != World.END) return;
		boolean drawFloor = mod.drawFloor();
		boolean drawHelper = mod.drawHelper();
		if (!drawFloor && !drawHelper) return;

		Vec3d camera = context.camera().getPos();
		int radius = mod.radius();
		int vis = mod.visibleDistance();
		double far = mc.gameRenderer.getFarPlaneDistance();
		int x0 = EndFloor.patchMin(mc.player.getBlockX(), radius);
		int x1 = EndFloor.patchMax(mc.player.getBlockX(), radius);
		int z0 = EndFloor.patchMin(mc.player.getBlockZ(), radius);
		int z1 = EndFloor.patchMax(mc.player.getBlockZ(), radius);
		double floorY = drawFloor ? EndFloor.drawPlaneY(camera.y, mod.floorY(), vis, far) : Double.NaN;
		double helperY = drawHelper ? EndFloor.drawPlaneY(camera.y, mod.helperLevel(), vis, far) : Double.NaN;
		floorY = separate(camera.y, floorY, helperY, Math.abs(camera.y - mod.floorY()), Math.abs(camera.y - mod.helperLevel()));
		helperY = separate(camera.y, helperY, floorY, Math.abs(camera.y - mod.helperLevel()), Math.abs(camera.y - mod.floorY()));

		Fog previousFog = RenderSystem.getShaderFog();
		float fogFar = (float) Math.max(far, vis);
		RenderSystem.setShaderFog(new Fog(fogFar, fogFar + 1.0f, previousFog.shape(),
			previousFog.red(), previousFog.green(), previousFog.blue(), previousFog.alpha()));
		try {
			var floor = mod.floorColor();
			var helper = mod.helperColor();
			if (!Double.isNaN(floorY)) {
				drawPlane(camera, x0, x1, z0, z1, floorY, floor.r, floor.g, floor.b);
				if (!EndFloor.drawnAtRealHeight(camera.y, mod.floorY(), vis, far)) {
					drawChunkGrid(camera, x0, x1, z0, z1, floorY, floor.r, floor.g, floor.b);
				}
			}
			if (!Double.isNaN(helperY)) {
				drawPlane(camera, x0, x1, z0, z1, helperY, helper.r, helper.g, helper.b);
				if (!EndFloor.drawnAtRealHeight(camera.y, mod.helperLevel(), vis, far)) {
					drawChunkGrid(camera, x0, x1, z0, z1, helperY, helper.r, helper.g, helper.b);
				}
			}
		} finally {
			RenderSystem.setShaderFog(previousFog);
		}
	}

	/** When both planes clamp to the same height, push the farther one a little farther away. */
	private static double separate(double cameraY, double drawY, double otherDrawY, double dist, double otherDist) {
		if (Double.isNaN(drawY) || Double.isNaN(otherDrawY)) return drawY;
		if (Math.abs(drawY - otherDrawY) > 1.0) return drawY;
		if (dist <= otherDist) return drawY;
		double sign = Math.signum(drawY - cameraY);
		if (sign == 0) sign = -1;
		return drawY + sign * 2.0;
	}

	/** Thick chunk-edge lines for the pulled-in plane (block grid stays on the real-height path). */
	private static void drawChunkGrid(Vec3d camera, int x0, int x1, int z0, int z1, double y, int red, int green, int blue) {
		RenderLayer layer = RenderLayer.getLines();
		layer.startDrawing();
		RenderSystem.lineWidth(3.0f);
		BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);
		double py = y + 0.02;
		int a = EndFloor.GRID_ALPHA;
		for (int x = x0; x <= x1; x += EndFloor.GRID) {
			line(buffer, camera, x, py, z0, x, py, z1, red, green, blue, a);
		}
		for (int z = z0; z <= z1; z += EndFloor.GRID) {
			line(buffer, camera, x0, py, z, x1, py, z, red, green, blue, a);
		}
		BuiltBuffer built = buffer.endNullable();
		if (built != null) {
			BufferRenderer.drawWithGlobalProgram(built);
		}
		layer.endDrawing();
	}

	private static void line(BufferBuilder buffer, Vec3d camera, double x0, double y0, double z0,
							 double x1, double y1, double z1, int red, int green, int blue, int alpha) {
		float dx = (float) (x1 - x0);
		float dy = (float) (y1 - y0);
		float dz = (float) (z1 - z0);
		float len = MathHelper.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0e-4f) return;
		dx /= len;
		dy /= len;
		dz /= len;
		buffer.vertex((float) (x0 - camera.x), (float) (y0 - camera.y), (float) (z0 - camera.z))
			.color(red, green, blue, alpha).normal(dx, dy, dz);
		buffer.vertex((float) (x1 - camera.x), (float) (y1 - camera.y), (float) (z1 - camera.z))
			.color(red, green, blue, alpha).normal(dx, dy, dz);
	}

	private static void drawPlane(Vec3d camera, int x0, int x1, int z0, int z1, double y, int red, int green, int blue) {
		RenderLayer layer = RenderLayer.getWorldBorder(MinecraftClient.isFabulousGraphicsOrBetter());
		layer.startDrawing();
		RenderSystem.setShaderColor(red / 255.0f, green / 255.0f, blue / 255.0f, EndFloor.FILL_ALPHA);

		float scroll = (Util.getMeasuringTimeMs() % 3000L) / 3000.0f;
		float yRel = (float) (y - camera.y);
		float xRel0 = (float) (x0 - camera.x);
		float xRel1 = (float) (x1 - camera.x);
		float zRel0 = (float) (z0 - camera.z);
		float zRel1 = (float) (z1 - camera.z);
		float uSpan = (x1 - x0) * 0.5f;
		float vSpan = (z1 - z0) * 0.5f;

		BufferBuilder buffer = Tessellator.getInstance().begin(
			VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
		quad(buffer, xRel0, yRel, zRel0, xRel1, zRel1, scroll, uSpan, vSpan, false);
		quad(buffer, xRel0, yRel, zRel0, xRel1, zRel1, scroll, uSpan, vSpan, true);

		BuiltBuffer built = buffer.endNullable();
		if (built != null) {
			BufferRenderer.drawWithGlobalProgram(built);
		}
		layer.endDrawing();
		RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
	}

	private static void quad(BufferBuilder buffer, float x0, float y, float z0, float x1, float z1,
							 float scroll, float uSpan, float vSpan, boolean flip) {
		float u0 = scroll;
		float u1 = scroll + uSpan;
		float v0 = scroll;
		float v1 = scroll + vSpan;
		if (!flip) {
			buffer.vertex(x0, y, z0).texture(u0, v0);
			buffer.vertex(x0, y, z1).texture(u0, v1);
			buffer.vertex(x1, y, z1).texture(u1, v1);
			buffer.vertex(x1, y, z0).texture(u1, v0);
		} else {
			buffer.vertex(x0, y, z0).texture(u0, v0);
			buffer.vertex(x1, y, z0).texture(u1, v0);
			buffer.vertex(x1, y, z1).texture(u1, v1);
			buffer.vertex(x0, y, z1).texture(u0, v1);
		}
	}
}
