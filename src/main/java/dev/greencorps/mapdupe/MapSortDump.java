package dev.greencorps.mapdupe;

import dev.greencorps.GreenCorps;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Map Duper+ <i>debug-log</i>: every Sort Maps / Bundle Sort writes the tiles it saw (name + 128×128 colours), the
 * palette and the layout it chose to {@code config/greencorps/map-sort/<time>.txt.gz}. A sort that came out wrong
 * becomes a unit test: copy the file to {@code src/test/resources/mapsort/} and add {@code expect} lines.
 * <p>
 * Format (gzip text, one record per line): {@code palette <256 hex>}, {@code tile <key> <name b64|-> <colors b64|->},
 * {@code result|expect <title b64> <width> <height> <cells: key or _, comma-separated>}.
 */
public final class MapSortDump {
	private static final Base64.Encoder B64 = Base64.getEncoder();
	private static final Base64.Decoder UN64 = Base64.getDecoder();

	/** A dump read back: tiles, palette, and the {@code expect} groups if the file has any. */
	public record Fixture(List<MapartLayout.Tile> tiles, int[] palette, List<MapartLayout.Group> expected) {}

	private MapSortDump() {}

	public static void write(List<MapartLayout.Tile> tiles, List<MapartLayout.Group> groups, MapartLayout.Palette palette) {
		try {
			Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("greencorps").resolve("map-sort");
			Files.createDirectories(dir);
			Path file = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt.gz");
			try (OutputStream out = Files.newOutputStream(file)) {
				write(out, tiles, groups, palette);
			}
			GreenCorps.LOG.info("[Map Duper+] sort dump: {}", file);
		} catch (IOException e) {
			GreenCorps.LOG.warn("[Map Duper+] sort dump failed: {}", e.toString());
		}
	}

	static void write(OutputStream raw, List<MapartLayout.Tile> tiles, List<MapartLayout.Group> groups,
		MapartLayout.Palette palette) throws IOException {
		try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(raw), StandardCharsets.UTF_8))) {
			w.write("# GreenCorps map sort dump v1\n");
			StringBuilder pal = new StringBuilder("palette");
			for (int i = 0; i < 256; i++) pal.append(i == 0 ? ' ' : ',').append(Integer.toHexString(palette.rgb(i) & 0xFFFFFF));
			w.write(pal.append('\n').toString());
			for (MapartLayout.Tile t : tiles) {
				w.write("tile " + t.key() + " " + b64(t.name()) + " " + (t.hasColors() ? B64.encodeToString(t.colors()) : "-") + "\n");
			}
			for (MapartLayout.Group g : groups) w.write(groupLine("result", g) + "\n");
		}
	}

	static String groupLine(String kind, MapartLayout.Group g) {
		StringBuilder cells = new StringBuilder();
		for (int i = 0; i < g.cells().size(); i++) {
			if (i > 0) cells.append(',');
			cells.append(g.cells().get(i) == null ? "_" : g.cells().get(i).toString());
		}
		return kind + " " + b64(g.title()) + " " + g.width() + " " + g.height() + " " + cells;
	}

	public static Fixture read(InputStream raw) throws IOException {
		List<MapartLayout.Tile> tiles = new ArrayList<>();
		List<MapartLayout.Group> expected = new ArrayList<>();
		int[] palette = new int[256];
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				String[] f = line.split(" ");
				switch (f[0]) {
					case "palette" -> {
						String[] hex = f[1].split(",");
						for (int i = 0; i < 256 && i < hex.length; i++) palette[i] = Integer.parseInt(hex[i], 16);
					}
					case "tile" -> tiles.add(new MapartLayout.Tile(Long.parseLong(f[1]), un64(f[2]),
						f[3].equals("-") ? null : UN64.decode(f[3])));
					case "expect" -> {
						List<Long> cells = new ArrayList<>();
						for (String c : f[4].split(",")) cells.add(c.equals("_") ? null : Long.parseLong(c));
						expected.add(new MapartLayout.Group(un64(f[1]), cells, Integer.parseInt(f[2]), Integer.parseInt(f[3]), "expect"));
					}
					default -> { }
				}
			}
		}
		return new Fixture(tiles, palette, expected);
	}

	private static String b64(String s) {
		return s == null ? "-" : B64.encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String un64(String s) {
		return s.equals("-") ? null : new String(UN64.decode(s), StandardCharsets.UTF_8);
	}
}
