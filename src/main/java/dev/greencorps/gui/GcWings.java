package dev.greencorps.gui;

import net.minecraft.client.gui.screen.ingame.HandledScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Registry of GreenCorps container wings. The mixin asks each factory for an active wing.
 */
public final class GcWings {
	private static final List<Function<HandledScreen<?>, GcScreenWing>> FACTORIES = new ArrayList<>();
	private static final WeakHashMap<HandledScreen<?>, GcScreenWing> ATTACHED = new WeakHashMap<>();

	private GcWings() {
	}

	public static void register(Function<HandledScreen<?>, GcScreenWing> factory) {
		FACTORIES.add(factory);
	}

	/** Returns the first active wing that applies to this screen, or null. */
	public static GcScreenWing createFor(HandledScreen<?> screen) {
		for (Function<HandledScreen<?>, GcScreenWing> factory : FACTORIES) {
			GcScreenWing wing = factory.apply(screen);
			if (wing == null) continue;
			if (!wing.isModuleActive()) continue;
			if (!wing.appliesTo(screen)) continue;
			return wing;
		}
		return null;
	}

	public static void setAttached(HandledScreen<?> screen, GcScreenWing wing) {
		if (screen == null) return;
		if (wing == null) ATTACHED.remove(screen);
		else ATTACHED.put(screen, wing);
	}

	public static GcScreenWing attached(HandledScreen<?> screen) {
		return screen == null ? null : ATTACHED.get(screen);
	}
}
