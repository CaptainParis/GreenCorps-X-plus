package dev.greencorps.gui.tooltip;

import dev.greencorps.modules.TooltipPlus;
import meteordevelopment.meteorclient.utils.tooltip.MeteorTooltipData;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Language;
import net.minecraft.text.OrderedText;
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
	/** Vanilla's highlight sprites are faint: a light well and an outline mark the selected / hovered stack. */
	private static final int MARK_FILL = 0x50FFFFFF;
	private static final int MARK_SELECTED = 0xFFFFFFFF;
	private static final int MARK_HOVERED = 0xFFFFD34D;
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
	/** Drawn as the pinned tooltip (which shows the hovered map itself) rather than the hover tooltip. */
	private boolean pinned;
	private static final int NAME_H = 12;
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

	public int hoveredIndex() {
		return hoveredIndex;
	}

	public int selectedIndex() {
		return selectedIndex;
	}

	/** The stack the tooltip is about: the hovered cell of a pinned bundle, else the scroll selection; -1 if none. */
	public int focusedIndex() {
		int i = hoveredIndex >= 0 ? hoveredIndex : selectedIndex;
		return i >= 0 && i < stacks.size() ? i : -1;
	}

	public void setPinned(boolean pinned) {
		this.pinned = pinned;
	}

	@Override
	public boolean isSticky() {
		return true;
	}

	@Override
	public int getHeight(TextRenderer textRenderer) {
		return nameRow() + rows() * SLOT + BAR_H + 8;
	}

	/**
	 * Height of the name row at the top: the selected (or hovered) stack's name sits inside the tooltip instead of
	 * floating over its title lines. Always there, so the pinned and the hover tooltip have the same layout.
	 */
	private int nameRow() {
		return NAME_H;
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

	private static void outline(DrawContext context, int x, int y, int size, int color) {
		context.fill(x, y, x + size, y + 1, color);
		context.fill(x, y + size - 1, x + size, y + size, color);
		context.fill(x, y + 1, x + 1, y + size - 1, color);
		context.fill(x + size - 1, y + 1, x + size, y + size - 1, color);
	}

	private int cellX(int x, int i) {
		return x + BundleGrid.col(i, stacks.size()) * SLOT;
	}

	private int cellY(int y, int i) {
		return y + nameRow() + BundleGrid.row(i, stacks.size()) * SLOT;
	}

	@Override
	public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext context) {
		drawX = x;
		drawY = y;
		lastDrawn = this;
		if (!pinned) {
			TooltipPlus tip = TooltipPlus.get();
			if (tip != null) tip.drawHoverBundleMap(context, this, x, y, width, height);
		}
		for (int i = 0; i < stacks.size(); i++) {
			int sx = cellX(x, i);
			int sy = cellY(y, i);
			boolean highlight = i == selectedIndex || i == hoveredIndex;
			context.drawGuiTexture(RenderLayer::getGuiTextured, highlight ? SLOT_HIGHLIGHT_BACK : SLOT_BACKGROUND, sx, sy, SLOT, SLOT);
			if (highlight) context.fill(sx + 2, sy + 2, sx + SLOT - 2, sy + SLOT - 2, MARK_FILL);
			ItemStack stack = stacks.get(i);
			context.drawItem(stack, sx + 4, sy + 4, i);
			context.drawStackOverlay(textRenderer, stack, sx + 4, sy + 4);
			if (highlight) {
				context.drawGuiTexture(RenderLayer::getGuiTexturedOverlay, SLOT_HIGHLIGHT_FRONT, sx, sy, SLOT, SLOT);
				// Hovered (pinned bundle) in amber when it is not the selected stack, so both stay readable.
				outline(context, sx + 1, sy + 1, SLOT - 2, i == selectedIndex ? MARK_SELECTED : MARK_HOVERED);
			}
		}

		// Pinned: the cell under the cursor first, then the scroll selection.
		int named = focusedIndex();
		Text name = named >= 0 ? stacks.get(named).getFormattedName()
			: Text.literal(pinned ? "Hover a stack" : "Scroll to pick a stack").formatted(Formatting.DARK_GRAY);
		OrderedText line = Language.getInstance().reorder(textRenderer.trimToWidth(name, width));
		context.drawTextWithShadow(textRenderer, line, x + (width - textRenderer.getWidth(line)) / 2, y + 1, 0xFFFFFFFF);

		// Full-width bar under the grid.
		int barY = y + nameRow() + rows() * SLOT + 4;
		boolean full = occupancy >= 1f;
		int inner = width - 2;
		int fill = Math.max(0, Math.min(inner, Math.round(occupancy * inner)));
		context.fill(x, barY, x + width, barY + BAR_H, BAR_BORDER);
		context.fill(x + 1, barY + 1, x + width - 1, barY + BAR_H - 1, BAR_BACK);
		context.fill(x + 1, barY + 1, x + 1 + fill, barY + BAR_H - 1, full ? BAR_FULL : BAR_FILL);
		context.drawCenteredTextWithShadow(textRenderer, BundleGrid.fillLabel(occupancy, stacks.size()), x + width / 2, barY + 3, 0xFFFFFF);
	}
}
