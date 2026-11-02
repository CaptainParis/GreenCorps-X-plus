package dev.greencorps.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
	@Accessor("x")
	int greencorps$getX();

	@Accessor("x")
	void greencorps$setX(int x);

	@Accessor("y")
	int greencorps$getY();

	@Accessor("backgroundWidth")
	int greencorps$getBackgroundWidth();

	@Accessor("backgroundHeight")
	int greencorps$getBackgroundHeight();

	@Accessor("focusedSlot")
	Slot greencorps$getFocusedSlot();
}
