package dev.greencorps.gui.wing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Vanilla button (the game's own nine-sliced sprites) for wings. Disabled buttons can carry a reason that is
 * shown on hover, enabled ones a hint; "active" toggles draw blue.
 */
public final class WingButton {
	private static final Identifier NORMAL = Identifier.ofVanilla("widget/button");
	private static final Identifier HOVER = Identifier.ofVanilla("widget/button_highlighted");
	private static final Identifier DISABLED = Identifier.ofVanilla("widget/button_disabled");
	private static final int ACTIVE_TINT = 0xFF6FA8FF;

	public final int x;
	public final int y;
	public final int w;
	public final int h;
	private final String label;
	private final boolean enabled;
	private final int tint;
	private final String reason;
	private final Runnable action;
	/** Hover text while enabled (e.g. what a mode does), or null. */
	private String hint;

	public WingButton(int x, int y, int w, int h, String label, boolean enabled, boolean active, String reason, Runnable action) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		this.label = label;
		this.enabled = enabled;
		this.tint = active ? ACTIVE_TINT : 0;
		this.reason = reason;
		this.action = action;
	}

	private WingButton(WingButton b, int tint) {
		this.x = b.x;
		this.y = b.y;
		this.w = b.w;
		this.h = b.h;
		this.label = b.label;
		this.enabled = b.enabled;
		this.reason = b.reason;
		this.action = b.action;
		this.tint = tint;
		this.hint = b.hint;
	}

	public static WingButton of(int x, int y, int w, int h, String label, Runnable action) {
		return new WingButton(x, y, w, h, label, true, false, null, action);
	}

	/** Same button tinted (e.g. amber for a confirm step). */
	public WingButton tinted(int color) {
		return new WingButton(this, color);
	}

	/** Same button with a hover hint shown while it is enabled. */
	public WingButton withHint(String text) {
		hint = text;
		return this;
	}

	public boolean labelStartsWith(String prefix) {
		return label.startsWith(prefix);
	}

	public boolean hit(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public void draw(DrawContext ctx, int mx, int my) {
		boolean hover = enabled && hit(mx, my);
		Identifier sprite = !enabled ? DISABLED : hover ? HOVER : NORMAL;
		if (tint != 0 && enabled) {
			ctx.drawGuiTexture(RenderLayer::getGuiTextured, sprite, x, y, w, h, tint);
		} else {
			ctx.drawGuiTexture(RenderLayer::getGuiTextured, sprite, x, y, w, h);
		}
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		String shown = tr.trimToWidth(label, w - 4);
		int color = enabled ? 0xFFFFFFFF : 0xFFA0A0A0;
		ctx.drawText(tr, shown, x + (w - tr.getWidth(shown)) / 2, y + (h - 8) / 2, color, true);
	}

	/** Hover reason for a disabled button (or the hint of an enabled one), drawn last so it sits on top. */
	public void drawReason(DrawContext ctx, int mx, int my) {
		String text = enabled ? hint : reason;
		if (text == null || text.isEmpty() || !hit(mx, my)) return;
		ctx.drawTooltip(MinecraftClient.getInstance().textRenderer, Text.literal(text), mx, my);
	}

	/** @return true if the click landed on this button */
	public boolean click(double mx, double my) {
		if (!hit(mx, my)) return false;
		if (enabled && action != null) {
			action.run();
			MinecraftClient.getInstance().getSoundManager().play(
				net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1f));
		}
		return true;
	}
}
