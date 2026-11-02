package dev.greencorps.mapdupe;

import java.util.List;

/**
 * Immutable stand-in for an item stack in the Map Duper+ simulation. {@link #key} is the stackability identity
 * (item + components): two stacks merge only when their keys match.
 */
public final class SimStack {
	public enum Kind { NONE, FILLED_MAP, EMPTY_MAP, BUNDLE, OTHER }

	public static final int MAX = 64;
	public static final SimStack NONE = new SimStack(Kind.NONE, 0L, 0, 0, List.of(), -1, false);

	public final Kind kind;
	public final long key;
	public final int count;
	public final int max;
	/** Bundle contents, index 0 = front (vanilla order). Empty for everything else. */
	public final List<SimStack> contents;
	/** Bundle selected index (-1 = none). */
	public final int selected;
	/** Left out of Copy All by the skip filters. */
	public final boolean skip;

	public SimStack(Kind kind, long key, int count, int max, List<SimStack> contents, int selected, boolean skip) {
		this.kind = kind;
		this.key = key;
		this.count = count;
		this.max = max;
		this.contents = List.copyOf(contents);
		this.selected = selected;
		this.skip = skip;
	}

	public static SimStack filledMap(long key, int count) {
		return new SimStack(Kind.FILLED_MAP, key, count, MAX, List.of(), -1, false);
	}

	public static SimStack emptyMap(long key, int count) {
		return new SimStack(Kind.EMPTY_MAP, key, count, MAX, List.of(), -1, false);
	}

	public static SimStack bundle(long key, List<SimStack> contents) {
		return new SimStack(Kind.BUNDLE, key, 1, 1, contents, -1, false);
	}

	public static SimStack other(long key, int count, int max) {
		return new SimStack(Kind.OTHER, key, count, max, List.of(), -1, false);
	}

	public boolean isNone() {
		return kind == Kind.NONE || count <= 0;
	}

	public SimStack withCount(int n) {
		if (n <= 0) return NONE;
		return new SimStack(kind, key, n, max, contents, selected, skip);
	}

	public SimStack withContents(List<SimStack> c, int sel) {
		return new SimStack(kind, key, count, max, c, sel, skip);
	}

	public SimStack skipped(boolean value) {
		return new SimStack(kind, key, count, max, contents, selected, value);
	}

	public boolean stacksWith(SimStack other) {
		return !isNone() && !other.isNone() && kind == other.kind && key == other.key && max > 1;
	}

	/** Maps in a bundle: each map is 1/64 of a bundle. */
	public int bundleMaps() {
		int n = 0;
		for (SimStack s : contents) n += s.count;
		return n;
	}

	public boolean isMapBundle() {
		if (kind != Kind.BUNDLE || contents.isEmpty()) return false;
		for (SimStack s : contents) {
			if (s.kind != Kind.FILLED_MAP) return false;
		}
		return true;
	}

	public boolean isEmptyBundle() {
		return kind == Kind.BUNDLE && contents.isEmpty();
	}

	/** Same item, count and (for bundles) contents; skip flags and bundle selection are ignored. */
	public boolean sameAs(SimStack o) {
		if (isNone() || o.isNone()) return isNone() && o.isNone();
		return kind == o.kind && key == o.key && count == o.count && contents.toString().equals(o.contents.toString());
	}

	@Override
	public String toString() {
		return isNone() ? "-" : kind + "#" + Long.toHexString(key) + "x" + count + (contents.isEmpty() ? "" : contents.toString());
	}
}
