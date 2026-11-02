package dev.greencorps.mixin;

import dev.greencorps.gui.GcScreenWing;
import dev.greencorps.gui.GcWings;
import dev.greencorps.gui.tooltip.GcBundleTooltipComponent;
import dev.greencorps.modules.MapSlotPreview;
import dev.greencorps.modules.TooltipPlus;
import dev.greencorps.util.ShulkerScreenTint;
import dev.greencorps.util.ShulkerTintedScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.RecipeBookScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {
	@Unique
	private GcScreenWing greencorps$wing;

	@Unique
	private boolean greencorps$wingVisible = true;

	/**
	 * Set when GreenCorps consumed a mouse press. Vanilla's mouseReleased clicks the slot under the mouse whenever
	 * the cursor holds something, so a Click To Copy press followed by its release dropped the job's cursor
	 * stack (e.g. half the empty maps) into the clicked bundle. Swallow the matching release and drags too.
	 */
	@Unique
	private int greencorps$swallowButton = -1;

	@Inject(method = "init", at = @At("TAIL"))
	private void greencorps$onInit(CallbackInfo ci) {
		HandledScreen<?> self = (HandledScreen<?>) (Object) this;
		HandledScreenAccessor acc = (HandledScreenAccessor) self;

		if (greencorps$wing != null) {
			greencorps$wing.detach();
			greencorps$wing = null;
		}
		greencorps$wingVisible = true;

		GcScreenWing wing = GcWings.createFor(self);
		if (wing == null) return;

		int wingW = wing.width();
		int screenW = self.width;
		int bgW = acc.greencorps$getBackgroundWidth();

		if (wing.leftSide()) {
			int wingLeft = acc.greencorps$getX() - GcScreenWing.GAP - wingW;
			if (wingLeft < 2) return; // no room left of the container
			wing.attach(wingLeft, acc.greencorps$getY());
			greencorps$wing = wing;
			GcWings.setAttached(self, wing);
			return;
		}

		// Too narrow: leave wing closed rather than overlapping slots.
		if (screenW < bgW + GcScreenWing.GAP + wingW + 8) {
			return;
		}

		boolean recipeBook = self instanceof RecipeBookScreen;
		if (!recipeBook) {
			// Loom and other non-recipe screens: shift once here.
			int combined = bgW + GcScreenWing.GAP + wingW;
			int newX = (screenW - combined) / 2;
			acc.greencorps$setX(newX);
		}
		// Recipe-book screens get their x from findLeftEdge (see MixinRecipeBookFindLeftGc).

		int wingLeft = acc.greencorps$getX() + bgW + GcScreenWing.GAP;
		int wingTop = acc.greencorps$getY();
		wing.attach(wingLeft, wingTop);
		greencorps$wing = wing;
		GcWings.setAttached(self, wing);
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void greencorps$reattachWing(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (greencorps$wing == null) return;

		HandledScreen<?> self = (HandledScreen<?>) (Object) this;
		if (!greencorps$wing.isModuleActive()) {
			greencorps$wingVisible = false;
			return;
		}

		HandledScreenAccessor acc = (HandledScreenAccessor) self;
		int bgW = acc.greencorps$getBackgroundWidth();
		int wingW = greencorps$wing.width();
		int wingLeft = acc.greencorps$getX() + bgW + GcScreenWing.GAP;
		int wingTop = acc.greencorps$getY();

		if (greencorps$wing.leftSide()) {
			int left = acc.greencorps$getX() - GcScreenWing.GAP - wingW;
			greencorps$wingVisible = left >= 2;
			if (greencorps$wingVisible) greencorps$wing.attach(left, wingTop);
			GcWings.setAttached(self, greencorps$wingVisible ? greencorps$wing : null);
			return;
		}

		if (self instanceof RecipeBookScreen) {
			RecipeBookWidget book = ((RecipeBookScreenAccessor) self).greencorps$getRecipeBook();
			if (book != null && book.isOpen()) {
				// Book open: park to the right of the inventory only when it fits.
				if (wingLeft + wingW > self.width - 4) {
					greencorps$wingVisible = false;
					GcWings.setAttached(self, null);
					return;
				}
			} else if (self.width < bgW + GcScreenWing.GAP + wingW + 8) {
				greencorps$wingVisible = false;
				GcWings.setAttached(self, null);
				return;
			}
		}

		greencorps$wingVisible = true;
		greencorps$wing.attach(wingLeft, wingTop);
		GcWings.setAttached(self, greencorps$wing);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void greencorps$onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (greencorps$wing != null && greencorps$wingVisible) {
			greencorps$wing.render(context, mouseX, mouseY);
		}
		TooltipPlus tip = TooltipPlus.get();
		if (tip != null) tip.renderPinnedBundle(context, mouseX, mouseY);
	}

	/** A pinned bundle tooltip stays alone on screen. */
	@Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
	private void greencorps$hideTooltipsWhilePinned(DrawContext context, int x, int y, CallbackInfo ci) {
		TooltipPlus tip = TooltipPlus.get();
		if (tip != null && tip.isBundlePinned()) ci.cancel();
	}

	/**
	 * Meteor's TooltipComponent.of shortcut hides that the Tooltip+ bundle component is sticky, so the bundle
	 * tooltip vanished while an item was on the cursor. Vanilla keeps it up to show where the item goes.
	 */
	@Inject(method = "isItemTooltipSticky", at = @At("RETURN"), cancellable = true)
	private void greencorps$stickyBundleTooltip(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && stack.getTooltipData().orElse(null) instanceof GcBundleTooltipComponent) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void greencorps$tooltipPlusKey(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (greencorps$wing != null && greencorps$wingVisible && greencorps$wing.keyPressed(keyCode, scanCode, modifiers)) {
			cir.setReturnValue(true);
			return;
		}
		TooltipPlus tip = TooltipPlus.get();
		if (tip != null && tip.onInventoryKey(keyCode, modifiers)) cir.setReturnValue(true);
	}

	/** Defer close while Map Slot Preview still has maps swapped into the hotbar. */
	@Inject(method = "close", at = @At("HEAD"), cancellable = true)
	private void greencorps$mapPreviewDeferClose(CallbackInfo ci) {
		MapSlotPreview mod = MapSlotPreview.get();
		if (mod != null && mod.onContainerCloseAttempt()) {
			ci.cancel();
		}
	}

	@Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, require = 0)
	private void greencorps$tooltipPlusScroll(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
		if (greencorps$wing != null && greencorps$wingVisible && greencorps$wing.mouseScrolled(mouseX, mouseY, verticalAmount)) {
			cir.setReturnValue(true);
			return;
		}
		TooltipPlus tip = TooltipPlus.get();
		if (tip != null && tip.onInventoryScroll(horizontalAmount, verticalAmount)) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * Optional: Lunar HandledScreen often has no mouseClicked override, so require=0.
	 * Also Tooltip+ pinned bundle clicks.
	 */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
	private void greencorps$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (greencorps$wing != null && greencorps$wingVisible
			&& (greencorps$wing.mouseClicked(mouseX, mouseY, button) || greencorps$wing.interceptScreenClick(mouseX, mouseY, button))) {
			greencorps$swallowButton = button;
			cir.setReturnValue(true);
			return;
		}
		TooltipPlus tip = TooltipPlus.get();
		if (tip != null && tip.onInventoryClick(mouseX, mouseY, button)) {
			greencorps$swallowButton = button;
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true, require = 0)
	private void greencorps$swallowRelease(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (greencorps$swallowButton != button) return;
		greencorps$swallowButton = -1;
		cir.setReturnValue(true);
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true, require = 0)
	private void greencorps$swallowDrag(double mouseX, double mouseY, int button, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
		if (greencorps$swallowButton == button) cir.setReturnValue(true);
	}

	/** Colored shulker screen: keep the title and "Inventory" labels readable on dark dyes. */
	@ModifyConstant(method = "drawForeground", constant = @Constant(intValue = 4210752), require = 0)
	private int greencorps$shulkerLabelColor(int vanilla) {
		if ((Object) this instanceof ShulkerTintedScreen tinted && tinted.greencorps$shulkerTint() != 0) {
			return ShulkerScreenTint.labelColor(tinted.greencorps$shulkerTint(), vanilla);
		}
		return vanilla;
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void greencorps$onRemoved(CallbackInfo ci) {
		MapSlotPreview mod = MapSlotPreview.get();
		if (mod != null) {
			mod.onContainerForceClosed();
		}
		TooltipPlus tip = TooltipPlus.get();
		if (tip != null) tip.onInventoryClosed();
		if (greencorps$wing != null) {
			greencorps$wing.onScreenClosed();
			greencorps$wing.detach();
			greencorps$wing = null;
		}
		greencorps$wingVisible = true;
		GcWings.setAttached((HandledScreen<?>) (Object) this, null);
	}
}
