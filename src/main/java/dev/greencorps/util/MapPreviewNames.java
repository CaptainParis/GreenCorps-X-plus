package dev.greencorps.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Custom-name keys for Map Slot Preview (2b2t map ids are not stable). */
public final class MapPreviewNames {
	private MapPreviewNames() {
	}

	/**
	 * Normalized custom name, or null if the stack is not a named filled map.
	 * Default "Filled Map" / missing custom name is not a key.
	 */
	public static String keyOf(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.isOf(Items.FILLED_MAP)) return null;
		Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
		if (custom == null) return null;
		String raw = custom.getString();
		String norm = normalize(raw);
		if (norm.isEmpty()) return null;
		// Vanilla default label is not a useful disk key.
		if (norm.equalsIgnoreCase("Filled Map")) return null;
		return norm;
	}

	public static String normalize(String raw) {
		if (raw == null) return "";
		StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '\u200B' || c == '\u200C' || c == '\u200D'
				|| c == '\u202A' || c == '\u202B' || c == '\u202C' || c == '\u202D' || c == '\u202E'
				|| c == '\uFEFF') {
				continue;
			}
			sb.append(c);
		}
		return sb.toString().trim();
	}

	public static String fileHash(String normalizedName) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] dig = md.digest(normalizedName.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(dig, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(normalizedName.hashCode());
		}
	}
}
