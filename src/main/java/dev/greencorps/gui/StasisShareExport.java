package dev.greencorps.gui;

import dev.greencorps.chat.StasisChat;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisChamberSlot;
import dev.greencorps.stasis.StasisDisplay;
import dev.greencorps.stasis.StasisHome;
import dev.greencorps.stasis.StasisPearl;
import dev.greencorps.stasis.StasisStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Renders an unfolded home summary PNG (capacity + chamber table, no coordinates)
 * and copies it to the OS clipboard without AWT Clipboard (osascript / PowerShell / wl-copy / xclip).
 */
public final class StasisShareExport {
	private static final DateTimeFormatter STAMP =
		DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);
	private static final Color BG = new Color(28, 30, 36);
	private static final Color FG = new Color(230, 230, 235);
	private static final Color MUTED = new Color(150, 155, 165);
	private static final Color LINE = new Color(55, 58, 68);
	private static final Color LOADED = new Color(100, 210, 150);
	private static final Color EMPTY = new Color(150, 155, 165);
	private static final Color BROKEN = new Color(200, 90, 90);
	private static final Color ASSUMED_BLUE = new Color(90, 160, 255);
	private static final Color VERIFIED_GREEN = new Color(80, 200, 120);

	private StasisShareExport() {}

	public static void shareNearest(StasisChambers mod) {
		if (mod == null || mod.store() == null) {
			StasisChat.warn("Share failed: store unavailable.");
			return;
		}
		StasisHome home = mod.nearestHome();
		if (home == null) {
			List<StasisHome> homes = mod.store().homes();
			if (homes.isEmpty()) {
				StasisChat.warn("No stasis homes to share.");
				return;
			}
			home = homes.get(0);
		}
		shareHome(mod, home);
	}

	/** Clipboard text summary for one home (no coordinates). */
	public static void copyTextSummary(StasisChambers mod, StasisHome home) {
		if (mod == null || mod.store() == null || home == null) {
			StasisChat.warn("Share failed: store unavailable.");
			return;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.keyboard == null) {
			StasisChat.warn("Share failed: clipboard unavailable.");
			return;
		}
		StasisStore.HomeCapacity cap = mod.capacityFor(home);
		String title = StasisDisplay.sanitizeTitle(home.name == null ? "Home" : home.name);
		String text = title + "\n" + cap.quotasLine() + "\n" + cap.capacityAlertLine();
		mc.keyboard.setClipboard(text);
		StasisChat.info("Summary text copied to clipboard.");
	}

	public static void shareHome(StasisChambers mod, StasisHome home) {
		Thread worker = new Thread(() -> {
			try {
				Path file = renderAndSave(mod, home);
				boolean copied = false;
				for (ClipCommand cmd : clipboardCommands(file)) {
					if (run(cmd, file)) {
						copied = true;
						break;
					}
				}
				boolean ok = copied;
				MinecraftClient.getInstance().execute(() -> {
					if (ok) StasisChat.info("Summary image copied to clipboard.");
					else StasisChat.warn("Saved PNG but clipboard copy failed (see config/greencorps/share/).");
				});
			} catch (Throwable t) {
				MinecraftClient.getInstance().execute(() ->
					StasisChat.warn("Share failed: " + t.getClass().getSimpleName()));
			}
		}, "greencorps-stasis-share");
		worker.setDaemon(true);
		worker.start();
	}

	private static Path renderAndSave(StasisChambers mod, StasisHome home) throws Exception {
		System.setProperty("java.awt.headless", "true");
		StasisStore.HomeCapacity cap = mod.capacityFor(home);
		List<StasisChamberSlot> slots = new ArrayList<>();
		int brokenCount = 0;
		for (StasisChamberSlot s : mod.store().slotsForHome(home.id)) {
			if (Boolean.FALSE.equals(s.geometryOk)) {
				brokenCount++;
				continue;
			}
			slots.add(s);
		}
		slots.sort(Comparator
			.comparingInt((StasisChamberSlot s) -> s.number <= 0 ? Integer.MAX_VALUE : s.number)
			.thenComparingInt(s -> s.x)
			.thenComparingInt(s -> s.z));

		List<Row> rows = new ArrayList<>();
		for (StasisChamberSlot s : slots) {
			OwnerParts owner = slotOwnerParts(mod, s);
			rows.add(new Row(
				s.number > 0 ? "#" + s.number : "-",
				slotStatus(mod, s),
				owner.ign(),
				owner.assumed(),
				s.kindLabel(),
				age(firstSeen(mod, s)),
				age(lastSeen(mod, s))
			));
		}

		String title = StasisDisplay.sanitizeTitle(home.name == null ? "Home" : home.name)
			+ "  " + cap.titleSuffix();
		String summary = cap.summaryLine();
		if (brokenCount > 0) summary = summary + " | Broken " + brokenCount + " hidden";
		String version = FabricLoader.getInstance()
			.getModContainer("greencorps")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("?");
		String footer = "GreenCorps Stasis v" + version;

		Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);
		Font bodyFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
		Font smallFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

		int pad = 20;
		int baseRowH = 22;
		int headerH = 26;
		int w = 1000;
		// # State Owner Type First Last
		int[] colX = {pad, pad + 50, pad + 160, pad + 520, pad + 620, pad + 760};
		int ownerColW = colX[3] - colX[2] - 8;

		BufferedImage measure = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		Graphics2D gm = measure.createGraphics();
		gm.setFont(bodyFont);
		FontMetrics fm = gm.getFontMetrics();
		int totalRowsH = 0;
		List<Integer> rowHeights = new ArrayList<>();
		for (Row row : rows) {
			List<String> lines = wrapOwner(row.ign(), row.assumed(), fm, ownerColW);
			int rh = Math.max(baseRowH, lines.size() * (fm.getHeight() + 2) + 4);
			rowHeights.add(rh);
			totalRowsH += rh;
		}
		if (rows.isEmpty()) totalRowsH = baseRowH;
		gm.dispose();

		int h = pad + 28 + 22 + 16 + headerH + totalRowsH + 28 + pad;

		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(BG);
		g.fillRect(0, 0, w, h);

		int y = pad;
		g.setFont(titleFont);
		g.setColor(FG);
		g.drawString(title, pad, y + g.getFontMetrics().getAscent());
		y += 28;

		g.setFont(bodyFont);
		g.setColor(MUTED);
		g.drawString(summary, pad, y + g.getFontMetrics().getAscent());
		y += 22 + 10;

		String[] cols = {"#", "State", "Owner", "Type", "First Seen", "Last Seen"};
		fm = g.getFontMetrics();
		g.setColor(MUTED);
		for (int i = 0; i < cols.length; i++) {
			g.drawString(cols[i], colX[i], y + fm.getAscent());
		}
		y += headerH;
		g.setColor(LINE);
		g.drawLine(pad, y - 6, w - pad, y - 6);

		if (rows.isEmpty()) {
			g.setColor(MUTED);
			g.drawString("No chamber slots yet", pad, y + fm.getAscent());
			y += baseRowH;
		} else {
			for (int ri = 0; ri < rows.size(); ri++) {
				Row row = rows.get(ri);
				int rh = rowHeights.get(ri);
				Color sc = stateColor(row.state());
				g.setColor(sc);
				g.drawString(row.num(), colX[0], y + fm.getAscent());
				int dot = 8;
				int dy = y + (fm.getAscent() - dot) / 2 + 2;
				g.fillOval(colX[1], dy, dot, dot);
				g.drawString(row.state(), colX[1] + dot + 6, y + fm.getAscent());

				List<String> ownerLines = wrapOwner(row.ign(), row.assumed(), fm, ownerColW);
				int ly = y;
				for (String line : ownerLines) {
					drawOwnerLine(g, fm, colX[2], ly, line);
					ly += fm.getHeight() + 2;
				}

				g.setColor(FG);
				g.drawString(row.type(), colX[3], y + fm.getAscent());
				g.drawString(row.first(), colX[4], y + fm.getAscent());
				g.drawString(row.last(), colX[5], y + fm.getAscent());
				y += rh;
			}
		}

		y = h - pad - 8;
		g.setColor(LINE);
		g.drawLine(pad, y - 14, w - pad, y - 14);
		g.setFont(smallFont);
		g.setColor(MUTED);
		int tw = g.getFontMetrics().stringWidth(footer);
		g.drawString(footer, (w - tw) / 2, y);
		g.dispose();

		Path dir = FabricLoader.getInstance().getConfigDir().resolve("greencorps").resolve("share");
		Files.createDirectories(dir);
		String safeName = (home.name == null ? "home" : home.name)
			.replaceAll("[^A-Za-z0-9_\\-]", "_");
		if (safeName.length() > 32) safeName = safeName.substring(0, 32);
		Path file = dir.resolve("stasis_" + safeName + "_" + LocalDateTime.now().format(STAMP) + ".png");
		ImageIO.write(img, "png", file.toFile());
		return file;
	}

	/** Owner line may end with \u0001assumed or \u0002verified marker for colored suffix. */
	private static void drawOwnerLine(Graphics2D g, FontMetrics fm, int x, int y, String line) {
		int markerA = line.indexOf('\u0001');
		int markerV = line.indexOf('\u0002');
		int marker = markerA >= 0 ? markerA : markerV;
		if (marker < 0) {
			g.setColor(FG);
			g.drawString(line, x, y + fm.getAscent());
			return;
		}
		String ignPart = line.substring(0, marker);
		g.setColor(FG);
		g.drawString(ignPart, x, y + fm.getAscent());
		int iw = fm.stringWidth(ignPart);
		boolean assumed = markerA >= 0;
		g.setColor(assumed ? ASSUMED_BLUE : VERIFIED_GREEN);
		g.drawString(assumed ? " (assumed)" : " (verified)", x + iw, y + fm.getAscent());
	}

	private static List<String> wrapOwner(String ign, boolean assumed, FontMetrics fm, int maxW) {
		List<String> out = new ArrayList<>();
		if (ign == null || ign.isBlank() || "-".equals(ign)) {
			out.add("-");
			return out;
		}
		String suffix = assumed ? " (assumed)" : " (verified)";
		char mark = assumed ? '\u0001' : '\u0002';
		if (fm.stringWidth(ign) + fm.stringWidth(suffix) <= maxW) {
			out.add(ign + mark);
			return out;
		}
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < ign.length(); i++) {
			cur.append(ign.charAt(i));
			if (fm.stringWidth(cur.toString()) > maxW && cur.length() > 1) {
				char last = cur.charAt(cur.length() - 1);
				cur.setLength(cur.length() - 1);
				out.add(cur.toString());
				cur = new StringBuilder().append(last);
			}
		}
		if (!cur.isEmpty()) out.add(cur.toString() + mark);
		else if (!out.isEmpty()) {
			out.set(out.size() - 1, out.get(out.size() - 1) + mark);
		}
		return out;
	}

	private record Row(String num, String state, String ign, boolean assumed,
					   String type, String first, String last) {}

	private record OwnerParts(String ign, boolean assumed) {}

	private record ClipCommand(List<String> args, boolean stdinFromFile) {}

	private static List<ClipCommand> clipboardCommands(Path file) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String path = file.toAbsolutePath().toString();
		if (os.contains("mac")) {
			String script = "set the clipboard to (read (POSIX file \"" + path
				+ "\") as \u00ABclass PNGf\u00BB)";
			return List.of(new ClipCommand(List.of("osascript", "-e", script), false));
		}
		if (os.contains("win")) {
			return List.of(new ClipCommand(List.of("powershell", "-NoProfile", "-Command",
				"Add-Type -AssemblyName System.Windows.Forms,System.Drawing; "
					+ "[System.Windows.Forms.Clipboard]::SetImage("
					+ "[System.Drawing.Image]::FromFile('" + path.replace("'", "''") + "'))"),
				false));
		}
		return List.of(
			new ClipCommand(List.of("wl-copy", "--type", "image/png"), true),
			new ClipCommand(List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-i", path), false)
		);
	}

	private static boolean run(ClipCommand cmd, Path file) {
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd.args()).redirectErrorStream(true);
			if (cmd.stdinFromFile()) pb.redirectInput(file.toFile());
			Process p = pb.start();
			if (!p.waitFor(8, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return false;
			}
			return p.exitValue() == 0;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static Color stateColor(String status) {
		return switch (status) {
			case "Loaded" -> LOADED;
			case "Broken" -> BROKEN;
			default -> EMPTY;
		};
	}

	private static String slotStatus(StasisChambers mod, StasisChamberSlot s) {
		StasisPearl bound = s.pearlId != null ? mod.store().findPearlById(s.pearlId).orElse(null) : null;
		if (bound != null && bound.loaded) return "Loaded";
		if (Boolean.FALSE.equals(s.geometryOk)) return "Broken";
		MinecraftClient mc = MinecraftClient.getInstance();
		boolean chunkLoaded = mc != null && mc.world != null
			&& mc.world.getChunkManager().getWorldChunk(s.x >> 4, s.z >> 4) != null;
		if (!chunkLoaded) return "Unknown";
		return "Empty";
	}

	private static OwnerParts slotOwnerParts(StasisChambers mod, StasisChamberSlot s) {
		if (s.pearlId == null) return new OwnerParts("-", false);
		StasisPearl p = mod.store().findPearlById(s.pearlId).orElse(null);
		if (p == null) return new OwnerParts("-", false);
		String ign;
		if (p.ownerIgn != null && !p.ownerIgn.isBlank()) ign = p.ownerIgn;
		else if (p.ownerUuid != null && p.ownerUuid.length() >= 8) ign = p.ownerUuid.substring(0, 8);
		else return new OwnerParts("unknown", false);
		return new OwnerParts(ign, p.isAssumedOwner(mod.sessionStartMs()));
	}

	private static long firstSeen(StasisChambers mod, StasisChamberSlot s) {
		if (s.pearlId != null) {
			StasisPearl p = mod.store().findPearlById(s.pearlId).orElse(null);
			if (p != null && p.firstSeenMs > 0) return p.firstSeenMs;
		}
		return s.firstSeenMs;
	}

	private static long lastSeen(StasisChambers mod, StasisChamberSlot s) {
		if (s.pearlId != null) {
			StasisPearl p = mod.store().findPearlById(s.pearlId).orElse(null);
			if (p != null && p.lastSeenMs > 0) return p.lastSeenMs;
		}
		return s.lastSeenMs;
	}

	private static String age(long epochMs) {
		if (epochMs <= 0) return "-";
		long ago = System.currentTimeMillis() - epochMs;
		if (ago < 1000) return "now";
		if (ago < 60_000) return (ago / 1000) + "s";
		if (ago < 3_600_000) return (ago / 60_000) + "m";
		if (ago < 86_400_000) return (ago / 3_600_000) + "h";
		return (ago / 86_400_000) + "d";
	}
}
