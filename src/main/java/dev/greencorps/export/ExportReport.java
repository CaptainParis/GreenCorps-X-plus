package dev.greencorps.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Written beside an exported world listing substitutions / degradations. */
public final class ExportReport {
	private final List<String> lines = new ArrayList<>();

	public void note(String line) {
		lines.add(line);
	}

	public void substituted(int cx, int cz, int sectionY, long fromEpoch, String reason) {
		lines.add("SUBSTITUTED chunk=" + cx + "," + cz + " sectionY=" + sectionY
			+ " fromEpoch=" + fromEpoch + " reason=" + reason);
	}

	public void airFilled(int cx, int cz, int sectionY) {
		lines.add("AIR_FILL chunk=" + cx + "," + cz + " sectionY=" + sectionY);
	}

	public boolean hasIssues() {
		return !lines.isEmpty();
	}

	public void write(Path worldDir) throws IOException {
		List<String> out = new ArrayList<>();
		out.add("GreenCorps Time Machine export report");
		out.add("issues=" + lines.size());
		out.addAll(lines);
		Files.write(worldDir.resolve("greencorps-export-report.txt"), out);
	}
}
