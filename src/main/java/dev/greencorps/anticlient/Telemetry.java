package dev.greencorps.anticlient;

import java.util.ArrayDeque;

/**
 * JSONL event stream plus a flight recorder: a ring of the last few seconds of
 * per-tick rows that is only written out when something interesting happens
 * (commit, hit, pop, safety log, death, manual dump). Positions in events are
 * always relative to the local player; absolute coordinates are never recorded.
 */
public final class Telemetry {
	private final LineSink sink;
	private final ArrayDeque<String> ring = new ArrayDeque<>();
	private final int ringCap;
	private String scenario = "none";
	private long events;

	public Telemetry(LineSink sink, int ringCapTicks) {
		this.sink = sink;
		this.ringCap = Math.max(10, ringCapTicks);
	}

	public void scenario(String id) {
		this.scenario = id == null || id.isBlank() ? "none" : id.trim();
	}

	public String scenario() {
		return scenario;
	}

	public long events() {
		return events;
	}

	public JsonLine event(String type, int tick) {
		return new JsonLine(type, tick).str("sc", scenario);
	}

	public void emit(JsonLine line) {
		sink.write(line.build());
		events++;
	}

	/** Buffers a per-tick row in the ring only. */
	public void record(JsonLine line) {
		ring.addLast(line.body());
		while (ring.size() > ringCap) ring.pollFirst();
	}

	/** Writes the ring out tagged with why, then clears it. Returns rows written. */
	public int dump(String reason, int tick) {
		int n = ring.size();
		emit(event("flight_dump", tick).str("reason", reason).num("rows", n));
		String tag = ",\"fr\":\"" + reason.replace("\"", "'") + "\"}";
		for (String body : ring) sink.write(body + tag);
		ring.clear();
		return n;
	}

	public void flush() {
		sink.flush();
	}

	public void close() {
		sink.flush();
		sink.close();
	}
}
