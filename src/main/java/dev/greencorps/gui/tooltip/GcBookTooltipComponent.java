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
		return 100;
	}

	@Override
	public int getWidth(TextRenderer textRenderer) {
		return 90;
	}

	@Override
	public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext context) {
		context.drawTexture(RenderLayer::getGuiTextured, BOOK, x, y, 0, 12, 0, 90, 90, 179, 179);
		String ind = (pageIndex + 1) + "/" + pageCount;
		context.drawText(textRenderer, ind, x + 90 - textRenderer.getWidth(ind) - 4, y + 90 - 10, 0xFF000000, false);

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
