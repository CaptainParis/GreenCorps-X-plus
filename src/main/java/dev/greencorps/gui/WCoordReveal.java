package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

/**
 * Masked coord cell: digits show as {@code #}. Click copies {@code x y z}.
 * Hold for {@link #HOLD_MS} to read the numbers; release hides them again.
 */
public final class WCoordReveal extends WPressable {
	public static final long HOLD_MS = 450L;

	private final int xCoord;
	private final int yCoord;
	private final int zCoord;
	private long pressStartedMs = -1L;

	public WCoordReveal(int x, int y, int z) {
		this.xCoord = x;
		this.yCoord = y;
		this.zCoord = z;
		this.tooltip = "Click to copy. Hold longer to read.";
		this.action = () -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc != null && mc.keyboard != null) {
				mc.keyboard.setClipboard(plain());
			}
			tooltip = "Copied.";
		};
	}

	public String plain() {
		return xCoord + " " + yCoord + " " + zCoord;
	}

	/** Digits become #; minus and spaces stay. */
	public static String maskDigits(String plain) {
		if (plain == null || plain.isEmpty()) return "";
		StringBuilder sb = new StringBuilder(plain.length());
		for (int i = 0; i < plain.length(); i++) {
			char c = plain.charAt(i);
			sb.append(c >= '0' && c <= '9' ? '#' : c);
		}
		return sb.toString();
	}

	@Override
	public boolean onMouseClicked(double mouseX, double mouseY, int button, boolean used) {
		boolean hit = super.onMouseClicked(mouseX, mouseY, button, used);
		if (hit && pressed) pressStartedMs = System.currentTimeMillis();
		return hit;
	}

	@Override
	public boolean onMouseReleased(double mouseX, double mouseY, int button) {
		pressStartedMs = -1L;
		return super.onMouseReleased(mouseX, mouseY, button);
	}

	private boolean unveiled() {
		if (!pressed || pressStartedMs < 0L) return false;
		return System.currentTimeMillis() - pressStartedMs >= HOLD_MS;
	}

	@Override
	protected void onCalculateSize() {
		double pad = theme.scale(2);
		width = pad + theme.textWidth(maskDigits("-30000000 99999 -30000000")) + pad;
		height = pad + theme.textHeight() + pad;
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = theme.scale(2);
		boolean open = unveiled();
		String text = open ? plain() : maskDigits(plain());
		Color fg = open ? new Color(230, 230, 235, 255) : new Color(120, 120, 128, 220);
		if (mouseOver) fg = open ? new Color(255, 255, 255, 255) : new Color(160, 160, 170, 255);
		renderer.text(text, x + pad, y + pad, fg, false);
		if (!open && tooltip != null && tooltip.equals("Copied.")) {
			tooltip = "Click to copy. Hold longer to read.";
		}
	}
}
