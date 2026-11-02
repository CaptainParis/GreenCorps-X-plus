package dev.greencorps.util;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Meteor builds titles from kebab names ({@code esp} -&gt; {@code Esp}).
 * Rewrite display titles so ESP stays all caps. Setting and module name keys are unchanged.
 *
 * <p>Only touches {@link Module#title} / {@link Setting#title} on those exact classes —
 * never subclass fields named {@code title} (e.g. Book Filler's title Setting).
 */
public final class EspTitles {
	private static final Unsafe UNSAFE = unsafe();

	private EspTitles() {
	}

	public static void applyAll() {
		Modules modules = Modules.get();
		if (modules == null) return;
		for (Module module : modules.getAll()) {
			if (module == null || module.category == null) continue;
			if (!"GreenCorps".equals(module.category.name)) continue;
			rewriteStringTitle(Module.class, module, espTitle(module.title));
			for (SettingGroup group : module.settings.groups) {
				for (Setting<?> setting : group) {
					rewriteStringTitle(Setting.class, setting, espTitle(setting.title));
				}
			}
		}
	}

	static String espTitle(String title) {
		if (title == null || title.isEmpty()) return title;
		return title.replace("Esp", "ESP");
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
