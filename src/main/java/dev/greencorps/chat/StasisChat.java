package dev.greencorps.chat;

import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client-only chat for Stasis Monitor. Never sends server chat packets.
 */
public final class StasisChat {
	private StasisChat() {}

	public static void info(String msg) {
		ChatUtils.sendMsg(prefix().append(Text.literal(safe(msg)).formatted(Formatting.GRAY)));
	}

	public static void warn(String msg) {
		ChatUtils.sendMsg(prefix().append(Text.literal(safe(msg)).formatted(Formatting.YELLOW)));
	}

	public static void missing(String msg) {
		ChatUtils.sendMsg(prefix().append(Text.literal(safe(msg)).formatted(Formatting.AQUA)));
	}

	public static void installed(String msg) {
		ChatUtils.sendMsg(prefix().append(Text.literal(safe(msg)).formatted(Formatting.GREEN)));
	}

	public static void empty(String msg) {
		ChatUtils.sendMsg(prefix().append(Text.literal(safe(msg)).formatted(Formatting.GOLD)));
	}

	public static void pulled(String msg) {
		ChatUtils.sendMsg(prefix().append(Text.literal(safe(msg)).formatted(Formatting.LIGHT_PURPLE)));
	}

	private static MutableText prefix() {
		return Text.literal("[Stasis] ").formatted(Formatting.GREEN);
	}

	private static String safe(String msg) {
		if (msg == null) return "";
		return msg.replace("%", "pct");
	}
}
