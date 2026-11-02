package dev.greencorps.mixin;

import dev.greencorps.gui.GcScreenWing;
import dev.greencorps.gui.MapDuperWing;
import dev.greencorps.modules.MapDuperPlus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While Map Duper+ is on, the inventory / crafting screen, its recipe book and the Map Duper wing are centred
 * as one group. Book closed: the container moves left so container + wing are centred. Book open: the book
 * ({@code getLeft}, which the book's widgets are placed from, plus the category tabs, which read
 * {@code leftOffset} directly) and the container move left by the same amount, so book + container + wing are
 * centred.
 */
@Mixin(RecipeBookWidget.class)
public abstract class MixinRecipeBookFindLeftGc {
	/** Vanilla RecipeBookWidget metrics (1.21.4). */
	@Unique
	private static final int BOOK_W = 147;
	@Unique
	private static final int BOOK_OFFSET = 86;
	/** Inventory and crafting screens are both 176 wide. */
	@Unique
	private static final int CONTAINER_W = 176;

	@Shadow
	private int parentWidth;

	@Shadow
	private boolean narrow;

	@Shadow
	private int leftOffset;

	@Shadow
	public abstract boolean isOpen();

	@Inject(method = "findLeftEdge", at = @At("RETURN"), cancellable = true)
	private void greencorps$shiftForMapDuperWing(int width, int backgroundWidth, CallbackInfoReturnable<Integer> cir) {
		if (!greencorps$applies()) return;
		if (isOpen()) {
			if (!narrow) cir.setReturnValue(cir.getReturnValueI() - greencorps$openShift(width, backgroundWidth));
			return;
		}
		int combined = backgroundWidth + GcScreenWing.GAP + MapDuperWing.PANEL_W;
		if (width < combined + 8) return;
		cir.setReturnValue((width - combined) / 2);
	}

	@Inject(method = "getLeft", at = @At("RETURN"), cancellable = true)
	private void greencorps$shiftBook(CallbackInfoReturnable<Integer> cir) {
		if (!isOpen() || narrow || !greencorps$applies()) return;
		cir.setReturnValue(cir.getReturnValueI() - greencorps$openShift(parentWidth, CONTAINER_W));
	}

	/** The category tabs compute their x from leftOffset instead of getLeft; move them with the book. */
	@Redirect(
		method = "refreshTabButtons",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/screen/recipebook/RecipeBookWidget;leftOffset:I", opcode = Opcodes.GETFIELD)
	)
	private int greencorps$shiftTabs(RecipeBookWidget self) {
		int offset = leftOffset;
		if (!isOpen() || narrow || !greencorps$applies()) return offset;
		return offset + greencorps$openShift(parentWidth, CONTAINER_W);
	}

	/** How far left the open book + container move so book, container and wing are centred (0 if they do not fit). */
	@Unique
	private static int greencorps$openShift(int width, int backgroundWidth) {
		int bookLeft = (width - BOOK_W) / 2 - BOOK_OFFSET;
		int containerLeft = 177 + (width - backgroundWidth - 200) / 2;
		int wingRight = containerLeft + backgroundWidth + GcScreenWing.GAP + MapDuperWing.PANEL_W;
		int groupW = wingRight - bookLeft;
		if (groupW > width - 8) return 0;
		return Math.max(0, bookLeft - (width - groupW) / 2);
	}

	@Unique
	private static boolean greencorps$applies() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || !(mc.currentScreen instanceof HandledScreen<?> hs)) return false;
		if (!(hs instanceof InventoryScreen || hs instanceof CraftingScreen)) return false;
		MapDuperPlus mod = MapDuperPlus.get();
		return mod != null && mod.isActive();
	}
}
