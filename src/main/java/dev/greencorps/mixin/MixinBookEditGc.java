package dev.greencorps.mixin;

import dev.greencorps.modules.BookFiller;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PageTurnWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Fill, copy, and clear buttons under the book, plus auto-fill when an empty
 * quill opens and template save when a book is signed.
 * Priority is below Meteor so this init tail runs after Meteor adds Copy and Paste.
 */
@Mixin(value = BookEditScreen.class, priority = 900)
public abstract class MixinBookEditGc extends Screen {
	@Shadow
	private boolean signing;
	@Shadow
	private boolean dirty;
	@Shadow
	private String title;
	@Shadow
	private List<String> pages;

	@Unique
	private ButtonWidget greencorps$fill;
	@Unique
	private ButtonWidget greencorps$fillSign;
	@Unique
	private ButtonWidget greencorps$copyPage;
	@Unique
	private ButtonWidget greencorps$copyAll;
	@Unique
	private ButtonWidget greencorps$clearPage;
	@Unique
	private ButtonWidget greencorps$clearAll;
	@Unique
	private boolean greencorps$clearArmed;
	@Unique
	private long greencorps$clearArmedAt;
	@Unique
	private boolean greencorps$autoTried;

	protected MixinBookEditGc(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void greencorps$bookButtons(CallbackInfo ci) {
		int shift = greencorps$shift();
		int bookTop = 2 + shift;
		int signY = bookTop + 196;
		int rowH = 20;
		int gap = 2;
		int left = this.width / 2 - 100;
		int buttonW = (200 - gap * 2) / 3;
		int yTools = signY + rowH + gap;
		int yClear = yTools + rowH + gap;
		int yCopy = yClear + rowH + gap;
		greencorps$fill = greencorps$button("Fill", left, yTools, buttonW, rowH, () -> greencorps$mod().overlayFill(greencorps$self(), false), true);
		greencorps$fillSign = greencorps$button("Fill & Sign", left + buttonW + gap, yTools, buttonW, rowH, () -> greencorps$mod().overlayFill(greencorps$self(), true), true);
		greencorps$copyPage = greencorps$button("Copy Page", left + (buttonW + gap) * 2, yTools, buttonW, rowH, () -> greencorps$mod().overlayCopyPage(greencorps$self()), true);
		greencorps$copyAll = greencorps$button("Copy All", left, yClear, buttonW, rowH, () -> greencorps$mod().overlayCopyAll(greencorps$self()), true);
		greencorps$clearPage = greencorps$button("Clear Page", left + buttonW + gap, yClear, buttonW, rowH, () -> greencorps$mod().overlayClearPage(greencorps$self()), true);
		greencorps$clearAll = greencorps$button("Clear All", left + (buttonW + gap) * 2, yClear, buttonW, rowH, this::greencorps$clearAll, false);
		greencorps$showButtons(!signing);
		int half = (200 - gap) / 2;
		for (Element element : this.children()) {
			if (!(element instanceof ClickableWidget widget)) continue;
			if (widget == greencorps$fill || widget == greencorps$fillSign || widget == greencorps$copyPage
				|| widget == greencorps$copyAll || widget == greencorps$clearPage || widget == greencorps$clearAll) {
				continue;
			}
			if (widget instanceof PageTurnWidget) {
				widget.setY(bookTop + 157);
				continue;
			}
			if (!(widget instanceof ButtonWidget button)) continue;
			String msg = button.getMessage().getString();
			if ("Copy".equals(msg)) {
				button.setDimensionsAndPosition(half, rowH, left, yCopy);
			} else if ("Paste".equals(msg)) {
				button.setDimensionsAndPosition(half, rowH, left + half + gap, yCopy);
			} else {
				button.setY(signY);
			}
		}
	}

	@Inject(method = "renderBackground", at = @At("HEAD"))
	private void greencorps$shiftBook(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		context.getMatrices().push();
		context.getMatrices().translate(0.0f, greencorps$shift(), 0.0f);
	}

	@Inject(method = "renderBackground", at = @At("RETURN"))
	private void greencorps$unshiftBook(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		context.getMatrices().pop();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;render(Lnet/minecraft/client/gui/DrawContext;IIF)V", shift = At.Shift.AFTER))
	private void greencorps$shiftText(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		context.getMatrices().push();
		context.getMatrices().translate(0.0f, greencorps$shift(), 0.0f);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void greencorps$unshiftText(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		context.getMatrices().pop();
	}

	@ModifyConstant(method = "screenPositionToAbsolutePosition", constant = @Constant(intValue = 32))
	private int greencorps$hitOffset(int base) {
		return base + greencorps$shift();
	}

	@Unique
	private int greencorps$shift() {
		int row = 20;
		int gap = 2;
		int block = 192 + 4 + row + gap + row + gap + row + gap + row;
		int bookTop = Math.max(2, (this.height - block) / 2);
		return bookTop - 2;
	}

	@Inject(method = "updateButtons", at = @At("TAIL"))
	private void greencorps$hideOnSign(CallbackInfo ci) {
		greencorps$showButtons(!signing);
		if (signing) greencorps$disarmClear();
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void greencorps$autoFillOnce(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (greencorps$autoTried) return;
		greencorps$autoTried = true;
		BookFiller mod = BookFiller.get();
		if (mod != null) mod.autoFillScreen(greencorps$self());
	}

	@Inject(method = "finalizeBook", at = @At("RETURN"))
	private void greencorps$saveSignedTemplate(boolean sign, CallbackInfo ci) {
		if (!sign || !dirty) return;
		BookFiller mod = BookFiller.get();
		if (mod != null) mod.onBookSigned(title, List.copyOf(pages));
	}

	@Unique
	private void greencorps$clearAll() {
		long now = System.currentTimeMillis();
		if (!greencorps$clearArmed || now - greencorps$clearArmedAt > 2000L) {
			greencorps$clearArmed = true;
			greencorps$clearArmedAt = now;
			greencorps$clearAll.setMessage(Text.literal("Clear all?"));
			return;
		}
		greencorps$disarmClear();
		greencorps$mod().overlayClearAll(greencorps$self());
	}

	@Unique
	private void greencorps$disarmClear() {
		greencorps$clearArmed = false;
		if (greencorps$clearAll != null) greencorps$clearAll.setMessage(Text.literal("Clear All"));
	}

	@Unique
	private void greencorps$showButtons(boolean show) {
		ButtonWidget[] buttons = {
			greencorps$fill, greencorps$fillSign, greencorps$copyPage,
			greencorps$copyAll, greencorps$clearPage, greencorps$clearAll
		};
		for (ButtonWidget button : buttons) {
			if (button == null) continue;
			button.visible = show;
			button.active = show;
		}
	}

	@Unique
	private ButtonWidget greencorps$button(String label, int x, int y, int w, int h, Runnable action, boolean disarmClear) {
		return addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
			if (disarmClear) greencorps$disarmClear();
			if (BookFiller.get() != null) action.run();
		}).dimensions(x, y, w, h).build());
	}

	@Unique
	private BookFiller greencorps$mod() {
		BookFiller mod = BookFiller.get();
		if (mod == null) throw new IllegalStateException("Book Filler is not loaded");
		return mod;
	}

	@Unique
	private BookEditScreen greencorps$self() {
		return (BookEditScreen) (Object) this;
	}
}
