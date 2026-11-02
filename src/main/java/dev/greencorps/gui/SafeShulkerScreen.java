package dev.greencorps.gui;

import dev.greencorps.modules.SafeShulker;

import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

/**
* A purely client-side shulker screen. Clicks are simulated locally (nothing is sent to the server)
* and recorded so SafeShulker can replay them on the real shulker afterwards.
*/
public class SafeShulkerScreen extends ShulkerBoxScreen {
	private final SafeShulker module;

	public SafeShulkerScreen(SafeShulker module, ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.module = module;
	}

	@Override
	protected void onMouseClick(Slot slot, int slotId, int button, SlotActionType actionType) {
		if (slot != null) slotId = slot.id;
		if (client == null || client.player == null) return;

		// Simulate locally instead of sending a ClickSlot packet
		handler.onSlotClick(slotId, button, actionType, client.player);
		module.recordClick(slotId, button, actionType);
	}

	@Override
	public void removed() {
		super.removed();
		module.onGhostClosed();
	}
}
