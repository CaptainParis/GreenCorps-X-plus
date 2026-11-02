package dev.greencorps.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.greencorps.backup.Home;
import dev.greencorps.modules.BaseBackup;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Util;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.border.WorldBorderStage;

/**
 * Client-only visual forcefield walls on home geofences (vanilla look, no collision).
 * Does not mutate {@code world.getWorldBorder()}.
 */
public final class HomeForcefieldRenderer {
	private HomeForcefieldRenderer() {}

	public static void render(WorldRenderContext context, BaseBackup mod) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mod.homeStore() == null || mc.world == null || mc.player == null) return;
		if (!mod.isActive() || !mod.drawBoundary()) return;

		Vec3d camera = context.camera().getPos();
		double viewDistanceBlocks = mc.options.getClampedViewDistance() * 16.0;
		double farPlane = mc.gameRenderer.getFarPlaneDistance();
		int approachBlocks = mod.borderApproachChunks() * 16;
		float opacity = (float) mod.boundaryOpacity();
		String dim = mc.world.getRegistryKey().getValue().toString();

		int stageColor = WorldBorderStage.STATIONARY.getColor();
		float r = ColorHelper.getRed(stageColor) / 255.0f;
		float g = ColorHelper.getGreen(stageColor) / 255.0f;
		float b = ColorHelper.getBlue(stageColor) / 255.0f;

		for (Home h : mod.homeStore().homes()) {
			if (!dim.equals(h.dimension)) continue;

			double minX = h.minBlockX();
			double maxX = h.maxBlockX();
			double minZ = h.minBlockZ();
			double maxZ = h.maxBlockZ();

			double distInsideX = Math.min(camera.x - minX, maxX - camera.x);
			double distInsideZ = Math.min(camera.z - minZ, maxZ - camera.z);
			double distToEdge = Math.min(distInsideX, distInsideZ);
			if (Math.abs(distToEdge) > approachBlocks) continue;

			WorldBorder border = new WorldBorder();
			border.setCenter(h.centerBlockX(), h.centerBlockZ());
			border.setSize(maxX - minX);

			renderHomeBorder(border, camera, viewDistanceBlocks, farPlane, r, g, b, opacity);
		}
	}

	/** Mirrors vanilla {@code WorldBorderRendering.render} with opacity applied to fade alpha. */
	private static void renderHomeBorder(
		WorldBorder border,
		Vec3d camera,
		double viewDistanceBlocks,
		double farPlaneDistance,
		float red,
		float green,
		float blue,
		float opacity
	) {
		double west = border.getBoundWest();
		double east = border.getBoundEast();
		double north = border.getBoundNorth();
		double south = border.getBoundSouth();

		if (camera.x < east - viewDistanceBlocks
			&& camera.x > west + viewDistanceBlocks
			&& camera.z < south - viewDistanceBlocks
			&& camera.z > north + viewDistanceBlocks) {
			return;
		}

		double fade = 1.0 - border.getDistanceInsideBorder(camera.x, camera.z) / viewDistanceBlocks;
		fade = Math.pow(fade, 4.0);
		fade = MathHelper.clamp(fade, 0.0, 1.0) * opacity;
		if (fade <= 0.001) return;

		float far = (float) farPlaneDistance;
		RenderLayer layer = RenderLayer.getWorldBorder(MinecraftClient.isFabulousGraphicsOrBetter());
		layer.startDrawing();

		RenderSystem.setShaderColor(red, green, blue, (float) fade);

		float scroll = (Util.getMeasuringTimeMs() % 3000L) / 3000.0f;
		float vOffset = (float) (-MathHelper.fractionalPart(camera.y * 0.5));
		float vHigh = vOffset + far;

		BufferBuilder buffer = Tessellator.getInstance().begin(
			VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);

		double camX = camera.x;
		double camZ = camera.z;

		double zMin = Math.max(MathHelper.floor(camZ - viewDistanceBlocks), north);
		double zMax = Math.min(MathHelper.ceil(camZ + viewDistanceBlocks), south);
		float uPhaseZ = (MathHelper.floor(zMin) & 1) * 0.5f;

		if (camX > east - viewDistanceBlocks) {
			float u = uPhaseZ;
			for (double z = zMin; z < zMax; z += 1.0) {
				double seg = Math.min(1.0, zMax - z);
				float uSeg = (float) seg * 0.5f;
				float x = (float) (east - camX);
				buffer.vertex(x, -far, (float) (z - camZ)).texture(scroll - u, scroll + vHigh);
				buffer.vertex(x, -far, (float) (z + seg - camZ)).texture(scroll - (uSeg + u), scroll + vHigh);
				buffer.vertex(x, far, (float) (z + seg - camZ)).texture(scroll - (uSeg + u), scroll + vOffset);
				buffer.vertex(x, far, (float) (z - camZ)).texture(scroll - u, scroll + vOffset);
				u += 0.5f;
			}
		}

		if (camX < west + viewDistanceBlocks) {
			float u = uPhaseZ;
			for (double z = zMin; z < zMax; z += 1.0) {
				double seg = Math.min(1.0, zMax - z);
				float uSeg = (float) seg * 0.5f;
				float x = (float) (west - camX);
				buffer.vertex(x, -far, (float) (z - camZ)).texture(scroll + u, scroll + vHigh);
				buffer.vertex(x, -far, (float) (z + seg - camZ)).texture(scroll + uSeg + u, scroll + vHigh);
				buffer.vertex(x, far, (float) (z + seg - camZ)).texture(scroll + uSeg + u, scroll + vOffset);
				buffer.vertex(x, far, (float) (z - camZ)).texture(scroll + u, scroll + vOffset);
				u += 0.5f;
			}
		}

		double xMin = Math.max(MathHelper.floor(camX - viewDistanceBlocks), west);
		double xMax = Math.min(MathHelper.ceil(camX + viewDistanceBlocks), east);
		float uPhaseX = (MathHelper.floor(xMin) & 1) * 0.5f;

		if (camZ > south - viewDistanceBlocks) {
			float u = uPhaseX;
			for (double x = xMin; x < xMax; x += 1.0) {
				double seg = Math.min(1.0, xMax - x);
				float uSeg = (float) seg * 0.5f;
				float z = (float) (south - camZ);
				buffer.vertex((float) (x - camX), -far, z).texture(scroll + u, scroll + vHigh);
				buffer.vertex((float) (x + seg - camX), -far, z).texture(scroll + uSeg + u, scroll + vHigh);
				buffer.vertex((float) (x + seg - camX), far, z).texture(scroll + uSeg + u, scroll + vOffset);
				buffer.vertex((float) (x - camX), far, z).texture(scroll + u, scroll + vOffset);
				u += 0.5f;
			}
		}

		if (camZ < north + viewDistanceBlocks) {
			float u = uPhaseX;
			for (double x = xMin; x < xMax; x += 1.0) {
				double seg = Math.min(1.0, xMax - x);
				float uSeg = (float) seg * 0.5f;
				float z = (float) (north - camZ);
				buffer.vertex((float) (x - camX), -far, z).texture(scroll - u, scroll + vHigh);
				buffer.vertex((float) (x + seg - camX), -far, z).texture(scroll - (uSeg + u), scroll + vHigh);
				buffer.vertex((float) (x + seg - camX), far, z).texture(scroll - (uSeg + u), scroll + vOffset);
				buffer.vertex((float) (x - camX), far, z).texture(scroll - u, scroll + vOffset);
				u += 0.5f;
			}
		}

		BuiltBuffer built = buffer.endNullable();
		if (built != null) {
			BufferRenderer.drawWithGlobalProgram(built);
		}
		layer.endDrawing();
		RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
	}
}
