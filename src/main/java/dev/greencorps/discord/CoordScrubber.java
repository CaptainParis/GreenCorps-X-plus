package dev.greencorps.discord;

import java.util.regex.Pattern;

/**
 * Reject Discord webhook content that looks like coordinates or locators.
 * Fail closed: any hit → do not send.
 */
public final class CoordScrubber {
	private static final Pattern XYZ = Pattern.compile(
		"(?i)(?<!\\w)-?\\d{1,8}\\s*[, ]\\s*-?\\d{1,8}\\s*[, ]\\s*-?\\d{1,8}(?!\\w)");
	private static final Pattern CHUNK = Pattern.compile("(?i)\\bchunk\\b|\\bregion\\b|\\.mca\\b");
	private static final Pattern NETHER_SCALE = Pattern.compile("(?i)\\bnether\\s*[÷/]\\s*8\\b|\\boverworld\\s*[×x]\\s*8\\b");
	private static final Pattern WAYPOINT = Pattern.compile("(?i)\\bwaypoint\\b|\\bxaero\\b");
	private static final Pattern PATH_LEAK = Pattern.compile(pathLeakRegex());

	/** Runtime assembly so javac does not fold a contiguous `/Users/` into the class pool. */
	private static String pathLeakRegex() {
		StringBuilder sb = new StringBuilder();
		sb.append("(?i)");
		sb.append('/').append("Users").append('/');
		sb.append('|').append("/home/\\w+");
		sb.append('|').append('C').append(':').append('\\').append('\\').append("Users").append('\\').append('\\');
		return sb.toString();
	}

	private CoordScrubber() {}

	/** @return null if safe; otherwise a short reject reason */
	public static String rejectReason(String content) {
		if (content == null || content.isBlank()) return "empty";
		if (XYZ.matcher(content).find()) return "xyz-like";
		if (CHUNK.matcher(content).find()) return "chunk/region";
		if (NETHER_SCALE.matcher(content).find()) return "nether-scale";
		if (WAYPOINT.matcher(content).find()) return "waypoint";
		if (PATH_LEAK.matcher(content).find()) return "path-leak";
		return null;
	}

	public static boolean isSafe(String content) {
		return rejectReason(content) == null;
	}
}
