package dev.greencorps.util;

/**
 * Walks every stack of a bundle once without changing its order. A bundle inserts at the front, so taking the
 * last stack and putting it back {@code size} times rotates the contents through one full turn.
 */
public final class BundleCycle {
	private final int size;
	private int round;

	public BundleCycle(int size) {
		this.size = Math.max(0, size);
	}

	public int size() {
		return size;
	}

	/** Rounds done so far. */
	public int round() {
		return round;
	}

	/** Index to take out this round (client order); always the last stack. */
	public int takeIndex() {
		return size - 1;
	}

	/** Index the stack taken this round had before the cycle started. */
	public int originalIndex() {
		return size - 1 - round;
	}

	public void roundDone() {
		if (round < size) round++;
	}

	public boolean done() {
		return round >= size;
	}
}
