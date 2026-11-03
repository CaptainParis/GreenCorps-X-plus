package dev.greencorps.util;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Module titles Meteor cannot derive from the name ({@code gateway-esp} would show as "Gateway Esp").
 * Only the display title changes; the name, and with it the saved settings, stay the same.
 *
 * <p>Only touches {@link Module#title} on that exact class, never subclass fields named {@code title}
 * (e.g. Book Filler's title Setting).
 */
public final class EspTitles {
	private static final Unsafe UNSAFE = unsafe();
	private static final Map<String, String> TITLES = Map.of("gateway-esp", "Gateways Esp");

	private EspTitles() {
	}

	public static void applyAll() {
		Modules modules = Modules.get();
		if (modules == null) return;
		for (Module module : modules.getAll()) {
			if (module == null || module.category == null) continue;
			if (!"GreenCorps".equals(module.category.name)) continue;
			String title = titleFor(module.name);
			if (title != null) rewriteStringTitle(Module.class, module, title);
		}
	}

	/** Display title for a GreenCorps module name, or null to keep Meteor's. */
	static String titleFor(String name) {
		return name == null ? null : TITLES.get(name);
	}

	private static void rewriteStringTitle(Class<?> owner, Object target, String value) {
		if (target == null || value == null || owner == null) return;
		try {
			Field f = owner.getDeclaredField("title");
			if (f.getType() != String.class) return;
			f.setAccessible(true);
			Object cur = f.get(target);
			if (value.equals(cur)) return;
			try {
				f.set(target, value);
			} catch (IllegalAccessException | IllegalArgumentException e) {
				if (UNSAFE == null) return;
				UNSAFE.putObject(target, UNSAFE.objectFieldOffset(f), value);
			}
		} catch (ReflectiveOperationException e) {
			// Leave Meteor title as-is. Never crash startup.
		}
	}

	private static Unsafe unsafe() {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			return (Unsafe) f.get(null);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
