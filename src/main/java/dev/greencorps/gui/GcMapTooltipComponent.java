package dev.greencorps.gui;

import dev.greencorps.modules.TooltipPlus;
import dev.greencorps.util.MapPreviewNames;
import dev.greencorps.util.MapPreviewStore;
import meteordevelopment.meteorclient.utils.tooltip.MeteorTooltipData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.render.MapRenderState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.util.Identifier;

/**
 * Rusher/Meteor-style large mapart hover: parchment + 128 art from live MapState or disk cache.
 */
public final class GcMapTooltipComponent implements TooltipComponent, MeteorTooltipData {
	private static final Identifier MAP_BACKGROUND = Identifier.of("textures/map/map_background.png");

	private final ItemStack stack;
	private final MapRenderState mapRenderState = new MapRenderState();
	/** Fixed size multiplier, or NaN to follow Tooltip+ map-scale. */
	private final double fixedScale;

	public GcMapTooltipComponent(ItemStack stack) {
		this(stack, Double.NaN);
	}

	public GcMapTooltipComponent(ItemStack stack, double scale) {
		this.stack = stack.copy();
		this.fixedScale = scale;
	}

	private double scale() {
		if (!Double.isNaN(fixedScale)) return fixedScale;
		TooltipPlus tip = TooltipPlus.get();
		return tip == null ? 1.0 : tip.mapTooltipScale();
	}

	@Override
	public int getHeight(TextRenderer textRenderer) {
		return (int) ((128 + 16) * scale()) + 2;
	}

	@Override
	public int getWidth(TextRenderer textRenderer) {
		return (int) ((128 + 16) * scale());
	}

	@Override
	public TooltipComponent getComponent() {
		return this;
	}

	@Override
	public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext context) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		double scale = scale();

		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(x, y, 0);
		matrices.scale((float) scale * 2, (float) scale * 2, 0);
		matrices.scale((64 + 8) / 64f, (64 + 8) / 64f, 0);
		context.drawTexture(RenderLayer::getGuiTextured, MAP_BACKGROUND, 0, 0, 0, 0, 0, 64, 64, 64, 64);
		matrices.pop();

		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		MapState state = mid != null && mc.world != null
			? FilledMapItem.getMapState(mid, mc.world)
			: null;

		matrices.push();
		matrices.translate(x, y, 0);
		matrices.scale((float) scale, (float) scale, 0);
		matrices.translate(8, 8, 0);

		if (state != null && mid != null) {
			VertexConsumerProvider.Immediate consumer = mc.getBufferBuilders().getEntityVertexConsumers();
			mc.getMapRenderer().update(mid, state, mapRenderState);
			mc.getMapRenderer().draw(mapRenderState, matrices, consumer, false, 0xF000F0);
			consumer.draw();
		} else {
			Identifier tex = resolveCacheTexture();
			if (tex != null) {
				context.drawTexture(RenderLayer::getGuiTextured, tex, 0, 0, 0f, 0f, 128, 128, 128, 128, 128, 128);
			}
		}
		matrices.pop();
	}

	private Identifier resolveCacheTexture() {
		MapPreviewStore store = MapPreviewStore.get();
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		if (mid != null) {
			Identifier t = store.textureForMapId(mid.id());
			if (t != null) return t;
		}
		return store.textureForUniqueName(MapPreviewNames.keyOf(stack));
	}
}
