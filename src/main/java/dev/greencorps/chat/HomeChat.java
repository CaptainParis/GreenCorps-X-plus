package dev.greencorps.chat;

import dev.greencorps.backup.Home;
import dev.greencorps.backup.SnapshotManifest;
import dev.greencorps.backup.StoreHealth;
import dev.greencorps.backup.StoreStats;
import dev.greencorps.commands.TimeSelector;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client-only chat notifications. HoverEvent + SUGGEST_COMMAND only — never RUN_COMMAND
 * (avoids leaking Meteor commands onto 2b2t chat).
 */
public final class HomeChat {
	public enum Verbosity { OFF, MINIMAL, FULL }

	private HomeChat() {}

	public static void enter(Home home, Verbosity v, double coverage, int snapshots, long lastCommit, StoreHealth health) {
		if (v == Verbosity.OFF) return;
		MutableText t = prefix().append(Text.literal("Entered ").formatted(Formatting.GRAY))
			.append(Text.literal(home.name).formatted(Formatting.GREEN));
		if (v == Verbosity.FULL) {
			t.append(Text.literal(" — cov " + (int) coverage + "pct, snaps " + snapshots
				+ ", last " + age(lastCommit) + ", " + health.label(0)).formatted(Formatting.DARK_GRAY));
		}
		t.append(suggest(" [info]", ".home info " + home.name, "Show home info"));
		ChatUtils.sendMsg(t);
	}

	public static void leave(Home home, Verbosity v) {
		if (v == Verbosity.OFF) return;
		MutableText t = prefix().append(Text.literal("Left ").formatted(Formatting.GRAY))
			.append(Text.literal(home.name).formatted(Formatting.YELLOW));
		ChatUtils.sendMsg(t);
	}

	public static void commit(Home home, SnapshotManifest m, long storeBytes) {
		MutableText t = prefix()
			.append(Text.literal("Home " + home.name + ": ").formatted(Formatting.GRAY))
			.append(Text.literal(m.capturedChunks + " chunks, " + m.changedChunks + " changed, +"
				+ StoreStats.formatBytes(m.bytesAdded) + " (store " + StoreStats.formatBytes(storeBytes) + ")")
				.formatted(Formatting.GREEN));
		ChatUtils.sendMsg(t);
	}

	/** Progress while spread-capturing (idle FPS unaffected). */
	public static void updating(String homeName, int done, int total, int etaSeconds, boolean starting) {
		if (starting) {
			info("Updating " + homeName + "…");
			return;
		}
		if (total <= 0) return;
		// Throttle: only every 8 chunks or last — caller already throttles; keep message short.
		String eta = etaSeconds > 0 ? " (~" + etaSeconds + "s)" : "";
		info("Capturing " + homeName + " " + done + "/" + total + eta);
	}

	public static void health(StoreHealth h, int degraded, boolean rehearsalPass, long rehearsalAge) {
		MutableText t = prefix()
			.append(Text.literal("Health: " + h.label(degraded)).formatted(
				h == StoreHealth.HEALTHY ? Formatting.GREEN : Formatting.RED));
		if (rehearsalAge > 0) {
			t.append(Text.literal(" | rehearsal: " + (rehearsalPass ? "PASS" : "FAIL")
				+ ", " + age(rehearsalAge)).formatted(Formatting.DARK_GRAY));
		}
		ChatUtils.sendMsg(t);
	}

	public static void info(String msg) {
		ChatUtils.sendMsg(prefix().append(Text.literal(msg).formatted(Formatting.GRAY)));
	}

	public static void warn(String msg) {
		ChatUtils.sendMsg(prefix().append(Text.literal(msg.replace("%", "pct")).formatted(Formatting.YELLOW)));
	}

	private static MutableText prefix() {
		return Text.literal("[BaseBackup] ").formatted(Formatting.DARK_GREEN);
	}

	private static MutableText suggest(String label, String command, String hover) {
		return Text.literal(label).setStyle(Style.EMPTY
			.withColor(Formatting.AQUA)
			.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
			.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
	}

	private static String age(long epochMs) {
		if (epochMs <= 0) return "never";
		long ago = System.currentTimeMillis() - epochMs;
		if (ago < 60_000) return (ago / 1000) + "s ago";
		if (ago < 3_600_000) return (ago / 60_000) + "m ago";
		if (ago < 86_400_000) return (ago / 3_600_000) + "h ago";
		return TimeSelector.format(epochMs);
	}
}
