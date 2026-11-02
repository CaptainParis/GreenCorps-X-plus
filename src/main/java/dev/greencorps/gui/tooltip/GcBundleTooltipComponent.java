package dev.greencorps.gui.tooltip;

import meteordevelopment.meteorclient.utils.tooltip.MeteorTooltipData;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Vanilla 1.21.4 bundle tooltip (24px slots, highlight sprites, selected item name) that shows every stack
 * instead of the first 12: rows of 8 in reading order from the top-left (see {@link BundleGrid}), with a
 * full-width fill bar labelled with items, percent and stacks.
 */
public final class GcBundleTooltipComponent implements TooltipComponent, MeteorTooltipData {
	private static final Identifier SLOT_BACKGROUND = Identifier.ofVanilla("container/bundle/slot_background");
	private static final Identifier SLOT_HIGHLIGHT_BACK = Identifier.ofVanilla("container/bundle/slot_highlight_back");
	private static final Identifier SLOT_HIGHLIGHT_FRONT = Identifier.ofVanilla("container/bundle/slot_highlight_front");
	// Bar colours sampled from vanilla's progress bar sprites (drawn with fills so the bar can stretch).
	private static final int BAR_BORDER = 0xFF000000;
	private static final int BAR_BACK = 0xFF2B2B2B;
	private static final int BAR_FILL = 0xFF7181DB;
	private static final int BAR_FULL = 0xFFD54646;

	public static final int SLOT = 24;
	private static final int BAR_H = 13;

	/** The component vanilla drew last; a pin freezes the tooltip at its origin. */
	private static GcBundleTooltipComponent lastDrawn;

	private final ItemStack bundle;
	private final List<ItemStack> stacks;
	/** 0..1 bundle occupancy. */
	private final float occupancy;
	private final int selectedIndex;
	/** Cell under the mouse while the tooltip is pinned, or -1. */
	private int hoveredIndex = -1;
	/** Screen-space origin of the last draw, for pinned hit tests. */
	private int drawX;
	private int drawY;

	public GcBundleTooltipComponent(ItemStack bundle, List<ItemStack> stacks, float occupancy, int selectedIndex) {
		this.bundle = bundle;
		this.stacks = stacks;
		this.occupancy = occupancy;
		this.selectedIndex = selectedIndex;
	}

	@Override
	public TooltipComponent getComponent() {
		return this;
	}

	private int cols() {
		return BundleGrid.columns(stacks.size());
	}

	private int rows() {
		return BundleGrid.rows(stacks.size());
	}

	public int size() {
		return stacks.size();
	}

	public ItemStack stack(int index) {
		return index >= 0 && index < stacks.size() ? stacks.get(index) : ItemStack.EMPTY;
	}

	public static GcBundleTooltipComponent lastDrawn() {
		return lastDrawn;
	}

	public ItemStack bundle() {
		return bundle;
	}

	/** Where the last draw put the component (absolute screen coordinates). */
	public int originX() {
		return drawX;
	}

	public int originY() {
		return drawY;
	}

	public void setHoveredIndex(int index) {
		hoveredIndex = index;
	}

	@Override
	public boolean isSticky() {
		return true;
	}

	@Override
	public int getHeight(TextRenderer textRenderer) {
		return rows() * SLOT + BAR_H + 8;
	}

	@Override
	public int getWidth(TextRenderer textRenderer) {
		return Math.max(cols() * SLOT, textRenderer.getWidth(BundleGrid.fillLabel(occupancy, stacks.size())) + 10);
	}

	/** Index of the cell at the mouse in the last drawn frame, or -1. */
	public int cellAt(double mouseX, double mouseY) {
		for (int i = 0; i < stacks.size(); i++) {
			int sx = cellX(drawX, i);
			int sy = cellY(drawY, i);
			if (mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT) return i;
		}
		return -1;
	}

	private int cellX(int x, int i) {
		return x + BundleGrid.col(i, stacks.size()) * SLOT;
	}

	private int cellY(int y, int i) {
		return y + BundleGrid.row(i, stacks.size()) * SLOT;
	}

	@Override
	public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext context) {
		drawX = x;
		drawY = y;
		lastDrawn = this;
		for (int i = 0; i < stacks.size(); i++) {
			int sx = cellX(x, i);
			int sy = cellY(y, i);
			boolean highlight = i == selectedIndex || i == hoveredIndex;
			context.drawGuiTexture(RenderLayer::getGuiTextured, highlight ? SLOT_HIGHLIGHT_BACK : SLOT_BACKGROUND, sx, sy, SLOT, SLOT);
			ItemStack stack = stacks.get(i);
			context.drawItem(stack, sx + 4, sy + 4, i);
			context.drawStackOverlay(textRenderer, stack, sx + 4, sy + 4);
			if (highlight) {
				context.drawGuiTexture(RenderLayer::getGuiTexturedOverlay, SLOT_HIGHLIGHT_FRONT, sx, sy, SLOT, SLOT);
			}
		}

		int named = hoveredIndex >= 0 ? hoveredIndex : selectedIndex;
		if (named >= 0 && named < stacks.size()) {
			ItemStack stack = stacks.get(named);
			Text name = stack.getFormattedName();
			int nameX = x + width / 2;
			context.drawTooltip(textRenderer, name, nameX - textRenderer.getWidth(name) / 2, y - 15,
				stack.get(DataComponentTypes.TOOLTIP_STYLE));
		}

		// Full-width bar under the grid.
		int barY = y + rows() * SLOT + 4;
		boolean full = occupancy >= 1f;
		int inner = width - 2;
		int fill = Math.max(0, Math.min(inner, Math.round(occupancy * inner)));
		context.fill(x, barY, x + width, barY + BAR_H, BAR_BORDER);
		context.fill(x + 1, barY + 1, x + width - 1, barY + BAR_H - 1, BAR_BACK);
		context.fill(x + 1, barY + 1, x + 1 + fill, barY + BAR_H - 1, full ? BAR_FULL : BAR_FILL);
		context.drawCenteredTextWithShadow(textRenderer, BundleGrid.fillLabel(occupancy, stacks.size()), x + width / 2, barY + 3, 0xFFFFFF);
	}
}
