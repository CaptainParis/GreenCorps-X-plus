package dev.greencorps.gui.tooltip;

import meteordevelopment.meteorclient.utils.tooltip.MeteorTooltipData;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Compact open-book page preview; page changed externally via scroll. */
public final class GcBookTooltipComponent implements TooltipComponent, MeteorTooltipData {
	private static final Identifier BOOK = Identifier.of("textures/gui/book.png");
	private static final int WIDTH = 90;
	private static final int HEIGHT = 100;
	private static final int PAGE_U = 20;
	private static final int PAGE_V = 1;
	private static final int PAGE_W = 146;
	private static final int PAGE_H = 180;

	private final Text page;
	private final int pageIndex;
	private final int pageCount;

	public GcBookTooltipComponent(Text page, int pageIndex, int pageCount) {
		this.page = page;
		this.pageIndex = pageIndex;
		this.pageCount = Math.max(1, pageCount);
	}

	@Override
	public TooltipComponent getComponent() {
		return this;
	}

	@Override
	public int getHeight(TextRenderer textRenderer) {
		return HEIGHT;
	}

	@Override
	public int getWidth(TextRenderer textRenderer) {
		return WIDTH;
	}

	@Override
	public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext context) {
		// The page of book.png (256×256): parchment at u 20-166, v 1-181, scaled into the 90×100 box.
		context.drawTexture(RenderLayer::getGuiTextured, BOOK, x, y, PAGE_U, PAGE_V, WIDTH, HEIGHT, PAGE_W, PAGE_H, 256, 256);
		String ind = (pageIndex + 1) + "/" + pageCount;
		context.drawText(textRenderer, ind, x + WIDTH - textRenderer.getWidth(ind) - 6, y + HEIGHT - 14, 0xFF000000, false);

		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(x + 10, y + 10, 1);
		matrices.scale(0.55f, 0.55f, 1f);
		int offset = 0;
		for (OrderedText line : textRenderer.wrapLines(page, 120)) {
			context.drawText(textRenderer, line, 0, offset, 0x000000, false);
			offset += 8;
			if (offset > 120) break;
		}
		matrices.pop();
	}
}
