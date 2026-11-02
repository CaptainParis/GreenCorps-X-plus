package dev.greencorps.gui.tooltip;

import dev.greencorps.util.BannerPatternItems;
import dev.greencorps.util.ItemStackBytes;
import meteordevelopment.meteorclient.utils.tooltip.MeteorTooltipData;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BannerBlockEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.model.ModelBaker;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DyeColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Banner preview: the banner on the left; on the right the layers in their colours, then a "Needs" grid in a
 * small vanilla-grey panel (dyes merged with counts, pattern items once; as wide as it needs, up to 6) and the
 * item size at the bottom. The gap between banner and text equals the gap above the banner.
 */
public final class GcBannerPlusTooltipComponent implements TooltipComponent, MeteorTooltipData {
	private static final int SLOT = 18;
	private static final int ING_COLS = 6;
	private static final int PAD = 4;
	/** Same as the space above the banner (vanilla's 2px under the first line + PAD). */
	private static final int GAP = 6;
	/** Grey panel border around the Needs slots. */
	private static final int PANEL_PAD = 3;
	private static final int LINE_H = 10;
	private static final int SECTION_GAP = 5;

	private final DyeColor baseColor;
	private final BannerPatternsComponent patterns;
	private final ModelPart bannerField;
	private final List<Line> lines = new ArrayList<>();
	private final List<ItemStack> ingredients = new ArrayList<>();
	private final boolean showComponents;
	private final boolean showIngredients;
	private final boolean showFileSize;
	private final int fileSizeBytes;

	private record Line(String text, int rgb) {}

	public GcBannerPlusTooltipComponent(
		DyeColor baseColor,
		BannerPatternsComponent patterns,
		ItemStack sourceStack,
		boolean showComponents,
		boolean showIngredients,
		boolean showFileSize
	) {
		this.baseColor = baseColor;
		this.patterns = patterns;
		this.showComponents = showComponents;
		this.showIngredients = showIngredients;
		this.showFileSize = showFileSize;
		this.fileSizeBytes = ItemStackBytes.estimate(sourceStack);
		this.bannerField = mc.getLoadedEntityModels().getModelPart(EntityModelLayers.STANDING_BANNER_FLAG).getChild("flag");

		lines.add(new Line(prettyDye(baseColor) + " Base", dyeRgb(baseColor)));
		// One dye per layer, merged by colour; pattern items are not used up, so each shows once.
		Map<Item, Integer> dyes = new LinkedHashMap<>();
		Set<Item> patternItems = new LinkedHashSet<>();
		dyes.merge(BannerPatternItems.dyeFor(baseColor), 1, Integer::sum);
		for (BannerPatternsComponent.Layer layer : patterns.layers()) {
			DyeColor c = layer.color();
			String patternKey = layer.pattern().getKey().map(k -> k.getValue().getPath()).orElse("pattern");
			lines.add(new Line(prettyDye(c) + " " + prettyPattern(patternKey), dyeRgb(c)));
			dyes.merge(BannerPatternItems.dyeFor(c), 1, Integer::sum);
			Item patternItem = BannerPatternItems.requiredItem(patternKey);
			if (patternItem != null) patternItems.add(patternItem);
		}
		dyes.forEach((item, n) -> ingredients.add(new ItemStack(item, n)));
		patternItems.forEach(item -> ingredients.add(new ItemStack(item)));
	}

	private static int dyeRgb(DyeColor c) {
		int e = c.getEntityColor();
		return 0xFF000000 | (e & 0xFFFFFF);
	}

	private static String prettyDye(DyeColor c) {
		String s = c.getName().replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String prettyPattern(String path) {
		String s = path.replace('_', ' ');
		StringBuilder b = new StringBuilder();
		for (String w : s.split(" ")) {
			if (w.isEmpty()) continue;
			if (!b.isEmpty()) b.append(' ');
			b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return b.toString();
	}

	@Override
	public TooltipComponent getComponent() {
		return this;
	}

	/** Drawn size of the flag: 20×40 model pixels at 3 GUI px each. */
	private int bannerW() {
		return 60;
	}

	private int bannerH() {
		return 120;
	}

	private int panelW() {
		return ingredientCols() * SLOT + PANEL_PAD * 2;
	}

	private int panelH() {
		return ingredientRows() * SLOT + PANEL_PAD * 2;
	}

	private int ingredientCols() {
		return Math.max(1, Math.min(ING_COLS, ingredients.size()));
	}

	private int ingredientRows() {
		return (ingredients.size() + ING_COLS - 1) / ING_COLS;
	}

	private boolean showsIngredients() {
		return showIngredients && !ingredients.isEmpty();
	}

	private String sizeText() {
		return "Size: " + fileSizeBytes + " bytes";
	}

	private int rightColW(TextRenderer textRenderer) {
		int max = 48;
		if (showComponents) {
			for (Line line : lines) max = Math.max(max, textRenderer.getWidth(line.text));
		}
		if (showsIngredients()) max = Math.max(max, Math.max(textRenderer.getWidth("Needs"), panelW()));
		if (showFileSize) max = Math.max(max, textRenderer.getWidth(sizeText()));
		return max;
	}

	private int rightColH() {
		int h = 0;
		if (showComponents) h += lines.size() * LINE_H;
		if (showsIngredients()) h += (h > 0 ? SECTION_GAP : 0) + LINE_H + panelH();
		if (showFileSize) h += (h > 0 ? SECTION_GAP : 0) + LINE_H;
		return h;
	}

	@Override
	public int getHeight(TextRenderer textRenderer) {
		return Math.max(bannerH(), rightColH()) + PAD * 2;
	}

	@Override
	public int getWidth(TextRenderer textRenderer) {
		return PAD + bannerW() + GAP + rightColW(textRenderer) + PAD;
	}

	@Override
	public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext context) {
		// Banner art, left
		DiffuseLighting.disableGuiDepthLighting();
		MatrixStack matrices = context.getMatrices();
		matrices.push();
		// The canvas model draws ~4px left of its origin; shift so the flag's left edge sits at PAD.
		matrices.translate(x + PAD + 4, y + PAD, 0);
		matrices.push();
		matrices.translate(0.5, 16, 0);
		matrices.scale(6, -6, 1);
		matrices.scale(1.6f, -1.6f, -1.6f);
		matrices.push();
		matrices.translate(2.5, 8.5, 0);
		matrices.scale(5, 5, 5);
		VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
		bannerField.pitch = 0f;
		bannerField.pivotY = -32f;
		BannerBlockEntityRenderer.renderCanvas(
			matrices, immediate, 0xF000F0, OverlayTexture.DEFAULT_UV,
			bannerField, ModelBaker.BANNER_BASE, true, baseColor, patterns
		);
		matrices.pop();
		matrices.pop();
		immediate.draw();
		matrices.pop();
		DiffuseLighting.enableGuiDepthLighting();

		int tx = x + PAD + bannerW() + GAP;
		int ty = y + PAD;
		if (showComponents) {
			for (Line line : lines) {
				context.drawText(textRenderer, line.text, tx, ty + 1, line.rgb, true);
				ty += LINE_H;
			}
		}
		if (showsIngredients()) {
			if (ty > y + PAD) ty += SECTION_GAP;
			context.drawText(textRenderer, "Needs", tx, ty + 1, 0xFF8A8A8A, true);
			ty += LINE_H;
			drawPanel(context, tx, ty, panelW(), panelH());
			int cols = ingredientCols();
			for (int i = 0; i < ingredients.size(); i++) {
				int sx = tx + PANEL_PAD + (i % cols) * SLOT;
				int sy = ty + PANEL_PAD + (i / cols) * SLOT;
				drawSlot(context, sx, sy);
				ItemStack stack = ingredients.get(i);
				context.drawItem(stack, sx + 1, sy + 1);
				context.drawStackOverlay(textRenderer, stack, sx + 1, sy + 1);
			}
			ty += panelH();
		}
		if (showFileSize) {
			if (ty > y + PAD) ty += SECTION_GAP;
			context.drawText(textRenderer, sizeText(), tx, ty + 1, 0xFF8A8A8A, true);
		}
	}

	/** Small vanilla GUI panel: black outline, light / dark bevel, grey body. */
	private static void drawPanel(DrawContext ctx, int x, int y, int w, int h) {
		ctx.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
		ctx.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
		ctx.fill(x + 1, y + 1, x + w - 2, y + 2, 0xFFFFFFFF);
		ctx.fill(x + 1, y + 1, x + 2, y + h - 2, 0xFFFFFFFF);
		ctx.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, 0xFF555555);
		ctx.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, 0xFF555555);
	}

	/** Vanilla inventory slot: dark top-left, white bottom-right, grey well. */
	private static void drawSlot(DrawContext ctx, int sx, int sy) {
		ctx.fill(sx, sy, sx + 17, sy + 1, 0xFF373737);
		ctx.fill(sx, sy + 1, sx + 1, sy + 17, 0xFF373737);
		ctx.fill(sx + 1, sy + 17, sx + 18, sy + 18, 0xFFFFFFFF);
		ctx.fill(sx + 17, sy + 1, sx + 18, sy + 17, 0xFFFFFFFF);
		ctx.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF8B8B8B);
		ctx.fill(sx + 17, sy, sx + 18, sy + 1, 0xFF8B8B8B);
		ctx.fill(sx, sy + 17, sx + 1, sy + 18, 0xFF8B8B8B);
	}
}
