package dev.greencorps.anticlient;

import java.util.Map;

/** Tiny flat-JSON builder so telemetry has no dependencies and stays one line per event. */
public final class JsonLine {
	private final StringBuilder sb = new StringBuilder(192);

	public JsonLine(String type, int tick) {
		sb.append("{\"type\":");
		quote(type);
		sb.append(",\"tick\":").append(tick);
	}

	public JsonLine str(String key, String value) {
		sb.append(',');
		quote(key);
		sb.append(':');
		quote(value == null ? "" : value);
		return this;
	}

	public JsonLine num(String key, double value) {
		sb.append(',');
		quote(key);
		sb.append(':').append(fmt(value));
		return this;
	}

	public JsonLine num(String key, long value) {
		sb.append(',');
		quote(key);
		sb.append(':').append(value);
		return this;
	}

	public JsonLine bool(String key, boolean value) {
		sb.append(',');
		quote(key);
		sb.append(':').append(value);
		return this;
	}

	public JsonLine ints(String key, Map<String, Integer> map) {
		sb.append(',');
		quote(key);
		sb.append(":{");
		boolean first = true;
		for (Map.Entry<String, Integer> e : map.entrySet()) {
			if (!first) sb.append(',');
			first = false;
			quote(e.getKey());
			sb.append(':').append(e.getValue());
		}
		sb.append('}');
		return this;
	}

	public JsonLine strs(String key, Iterable<String> values) {
		sb.append(',');
		quote(key);
		sb.append(":[");
		boolean first = true;
		for (String v : values) {
			if (!first) sb.append(',');
			first = false;
			quote(v);
		}
		sb.append(']');
		return this;
	}

	/** The line without its closing brace, for the flight recorder. */
	public String body() {
		return sb.toString();
	}

	public String build() {
		return sb + "}";
	}

	static String fmt(double v) {
		if (!Double.isFinite(v)) return "null";
		if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
		double r = Math.round(v * 1000.0) / 1000.0;
		if (r == Math.rint(r)) return Long.toString((long) r);
		return Double.toString(r);
	}

	private void quote(String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			switch (ch) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
					else sb.append(ch);
				}
			}
		}
		sb.append('"');
	}
}
