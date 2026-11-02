package dev.greencorps.mapdupe;

/** Screen slot layouts Map Duper+ works in (vanilla 1.21.4 screen handler slot ids). */
public enum Station {
	/** Survival inventory 2×2 grid. Armor 5-8 and offhand 45 are never touched. */
	INVENTORY("inventory", 0, new int[] { 1, 2, 3, 4 }, 9, 44, 46, 9),
	CRAFTING("crafting table", 0, new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }, 10, 45, 46, 10),
	/** Map in 0, empty map in 1, result 2: one copy per operation. */
	CARTOGRAPHY("cartography table", 2, new int[] { 0, 1 }, 3, 38, 39, 3),
	/** Open shulker box: Sort Maps arranges the box (0-26); no grid, nothing is crafted here. */
	SHULKER("shulker box", -1, new int[0], 27, 62, 63, 0);

	public final String label;
	public final int result;
	private final int[] inputs;
	public final int invFirst;
	public final int invLast;
	public final int slotCount;
	/** First of the 27 slots Sort Maps arranges (main inventory rows, or the shulker's contents). */
	public final int sortFirst;

	Station(String label, int result, int[] inputs, int invFirst, int invLast, int slotCount, int sortFirst) {
		this.label = label;
		this.result = result;
		this.inputs = inputs;
		this.invFirst = invFirst;
		this.invLast = invLast;
		this.slotCount = slotCount;
		this.sortFirst = sortFirst;
	}

	/** Where the filled map goes. */
	public int filledSlot() {
		return inputs[0];
	}

	/** Where empty maps go. */
	public int[] emptySlots() {
		int[] out = new int[inputs.length - 1];
		System.arraycopy(inputs, 1, out, 0, out.length);
		return out;
	}

	public int[] inputs() {
		return inputs.clone();
	}

	/** Copies one craft can make at most. */
	public int emptiesPerCraft() {
		return inputs.length - 1;
	}

	public boolean isInventory(int slot) {
		return slot >= invFirst && slot <= invLast;
	}

	public boolean isInput(int slot) {
		for (int i : inputs) {
			if (i == slot) return true;
		}
		return false;
	}
}
