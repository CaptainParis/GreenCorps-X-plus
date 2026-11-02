package dev.greencorps.mapdupe;

/** One planned action for {@code CopyRunner}. */
public sealed interface Step {
	int LEFT = 0;
	int RIGHT = 1;

	/**
	 * A PICKUP click on a screen slot. {@code before} / {@code cursor} are what the plan expects in that slot and
	 * on the cursor right before it; the runner refuses to click when the live screen shows something else.
	 */
	record Click(int slot, int button, SimStack before, SimStack cursor) implements Step {
		public Click(int slot, int button) {
			this(slot, button, null, null);
		}
	}

	/** Bundle select packet (no click). {@code index} is the client-side index. */
	record SelectBundle(int slot, int index, int size) implements Step {}

	/** Wait until the server shows this craft result, with the expected map in the filled-map slot. */
	record AwaitResult(int count, long mapKey) implements Step {}

	/** End of a unit (one map chunk / one bundle stack): once the server settles, the inventory must match. */
	record Checkpoint(InvSim expected, String unit) implements Step {}
}
