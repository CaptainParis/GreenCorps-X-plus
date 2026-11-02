package dev.greencorps.gui;

import dev.greencorps.gui.wing.WingDraw;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Vanilla-styled wing attached to a native container screen (recipe-book placement).
 * Not a floating HUD overlay.
 */
public abstract class GcScreenWing {
	public static final int LABEL_COLOR = 0x404040;
	public static final int GAP = 2;
	/** Opaque vanilla-grey fill behind the panel texture. */
	public static final int PANEL_FILL = 0xFFC6C6C6;
	/** Draw above slots (~100) and inventory player (~50), under tooltips (~400). */
	private static final float DRAW_Z = 200f;

	/** Panel texture, or null for a plain vanilla panel ({@link WingDraw#panel}). */
	private final Identifier texture;
	private final int panelW;
	private final int panelH;

	protected int left;
	protected int top;

	protected GcScreenWing(Identifier texture, int panelW, int panelH) {
		this.texture = texture;
		this.panelW = panelW;
		this.panelH = panelH;
	}

	public abstract boolean appliesTo(HandledScreen<?> screen);

	public abstract boolean isModuleActive();

	public int width() {
		return panelW;
	}

	/** Left-side wings hang off the container's left edge, top-aligned, and never move the container. */
	public boolean leftSide() {
		return false;
	}

	public int height() {
		return panelH;
	}

	public final void attach(int wingLeft, int wingTop) {
		this.left = wingLeft;
		this.top = wingTop;
		onAttach();
	}

	protected void onAttach() {
	}

	public final void detach() {
		onDetach();
	}

	protected void onDetach() {
	}

	public final void render(DrawContext context, int mouseX, int mouseY) {
		var matrices = context.getMatrices();
		matrices.push();
		matrices.translate(0f, 0f, DRAW_Z);
		if (texture == null) {
			WingDraw.panel(context, left, top, panelW, panelH);
		} else {
			context.fill(left, top, left + panelW, top + panelH, PANEL_FILL);
			context.drawTexture(
				RenderLayer::getGuiTextured,
				texture,
				left,
				top,
				0,
				0,
				panelW,
				panelH,
				256,
				256
			);
		}
		drawContents(context, mouseX, mouseY);
		matrices.pop();
	}

	protected abstract void drawContents(DrawContext context, int mouseX, int mouseY);

	/** @return true if the click was consumed inside the wing */
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY)) return false;
		return onClick(mouseX, mouseY, button);
	}

	protected boolean onClick(double mouseX, double mouseY, int button) {
		return true;
	}

	/**
	 * A click on the screen outside the wing (e.g. a slot). @return true to consume it
	 * (the wing acted on it, or is busy and blocks input).
	 */
	public boolean interceptScreenClick(double mouseX, double mouseY, int button) {
		return false;
	}

	/** The screen closed for good (not a resize re-init). */
	public void onScreenClosed() {
	}

	/** @return true to consume the key */
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return false;
	}

	/** @return true to consume the scroll */
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	public boolean contains(double mx, double my) {
		return mx >= left && mx < left + panelW && my >= top && my < top + panelH;
	}

	protected void drawLabel(DrawContext context, String text, int x, int y) {
		context.drawText(net.minecraft.client.MinecraftClient.getInstance().textRenderer, text, x, y, LABEL_COLOR, false);
	}

	protected void drawLabel(DrawContext context, Text text, int x, int y) {
		context.drawText(net.minecraft.client.MinecraftClient.getInstance().textRenderer, text, x, y, LABEL_COLOR, false);
	}

	protected static boolean hit(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
