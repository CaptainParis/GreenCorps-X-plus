package dev.greencorps.signs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Memory-mapped sign pack. Author and text tables stay resident. Search uses a lazy postings
 * list of record offsets and does not load every region into objects.
 *
 * <p>Little-endian layout: magic {@code GSGN}, version 1, then author table, text table,
 * region directory, and 20-byte sign records.
 */
public final class SignPack implements AutoCloseable {
	static final int MAGIC = 0x4E47_5347;
	private static final int SIGN_REC_SIZE = 20;

	private final FileChannel channel;
	private final ByteBuffer buffer;
	private final Author[] authors;
	private final String[] texts;
	private final int regionCount;
	private final int[] dataOffsets;
	private final int[] regionSignCounts;
	private volatile int[] postingStarts;
	private volatile int[] postingOffsets;

	private SignPack(
		FileChannel channel,
		ByteBuffer buffer,
		Author[] authors,
		String[] texts,
		int regionCount,
		int[] dataOffsets,
		int[] regionSignCounts
	) {
		this.channel = channel;
		this.buffer = buffer;
		this.authors = authors;
		this.texts = texts;
		this.regionCount = regionCount;
		this.dataOffsets = dataOffsets;
		this.regionSignCounts = regionSignCounts;
	}

	public static SignPack open(Path path) throws IOException {
		if (path == null || !Files.isRegularFile(path)) {
			throw new IOException("Sign pack is missing");
		}
		FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
		long size = channel.size();
		if (size > Integer.MAX_VALUE) {
			channel.close();
			throw new IOException("Sign pack is too large to map");
		}
		MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
		mapped.order(ByteOrder.LITTLE_ENDIAN);
		try {
			return parse(channel, mapped);
		} catch (IOException e) {
			channel.close();
			throw e;
		}
	}

	/** Heap buffer for tests. The buffer is read from the start and not retained by the caller. */
	public static SignPack open(byte[] bytes) throws IOException {
		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		return parse(null, buf);
	}

	public int textCount() {
		return texts.length;
	}

	/**
	 * Full-pack text search. Call off the client thread. A query with no word and no
	 * {@code author:} is refused so a chip click cannot walk every sign.
	 */
	public synchronized SignSearchResult search(String query, int maxGroups, SignFilter filter) {
		SignQuery parsed = SignQuery.parse(query);
		if (parsed.hasSyntaxError()) {
			return new SignSearchResult(0, 0, List.of(), "Could not read that year.");
		}
		if (parsed.isEmpty()) {
			return new SignSearchResult(0, 0, List.of(), null);
		}
		SignFilter f = (filter == null ? SignFilter.none() : filter).merge(parsed);
		BitSet match = matchTexts(parsed.include(), parsed.exclude(), parsed.authorContains().isEmpty());
		if (match.isEmpty()) {
			return new SignSearchResult(0, 0, List.of(), null);
		}
		ensurePostings();
		int cap = Math.max(1, Math.min(500, maxGroups));
		return gather(match, cap, f);
	}

	private static SignPack parse(FileChannel channel, ByteBuffer buf) throws IOException {
		if (buf.remaining() < 24 && buf.limit() < 24) {
			throw new IOException("Invalid sign pack header");
		}
		int magic = buf.getInt(0);
		int version = buf.getInt(4);
		int authorCount = buf.getInt(8);
		int stringCount = buf.getInt(12);
		buf.getInt(16);
		int regionCount = buf.getInt(20);
		if (magic != MAGIC || version != 1 || authorCount < 0 || stringCount < 0 || regionCount < 0) {
			throw new IOException("Invalid sign pack header");
		}
		int pos = 24;
		Author[] authors = new Author[authorCount];
		for (int i = 0; i < authorCount; i++) {
			String name = readString(buf, pos);
			pos += 2 + utf8Length(buf, pos);
			float weight = buf.getFloat(pos);
			pos += 4;
			int globalCount = buf.getInt(pos);
			pos += 4;
			authors[i] = new Author(name, weight, globalCount);
		}
		String[] texts = new String[stringCount];
		for (int i = 0; i < stringCount; i++) {
			texts[i] = readString(buf, pos);
			pos += 2 + utf8Length(buf, pos);
		}
		int[] offsets = new int[regionCount];
		int[] counts = new int[regionCount];
		for (int i = 0; i < regionCount; i++) {
			offsets[i] = buf.getInt(pos + 8);
			counts[i] = buf.getInt(pos + 12);
			pos += 16;
			if (counts[i] < 0) throw new IOException("Invalid sign pack region");
		}
		return new SignPack(channel, buf, authors, texts, regionCount, offsets, counts);
	}

	private static int utf8Length(ByteBuffer buf, int pos) throws IOException {
		if (pos + 2 > buf.limit()) throw new IOException("Truncated sign pack");
		return buf.getShort(pos) & 0xFFFF;
	}

	private static String readString(ByteBuffer buf, int pos) throws IOException {
		int len = utf8Length(buf, pos);
		int start = pos + 2;
		if (start + len > buf.limit()) throw new IOException("Truncated sign pack");
		byte[] bytes = new byte[len];
		buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(start).get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private BitSet matchTexts(List<String> include, List<String> exclude, boolean requireInclude) {
		BitSet bits = new BitSet(texts.length);
		if (include.isEmpty() && requireInclude) return bits;
		for (int i = 0; i < texts.length; i++) {
			String t = texts[i];
			if (t == null) continue;
			String lower = t.toLowerCase(Locale.ROOT);
			boolean ok = true;
			for (int k = 0; k < include.size(); k++) {
				if (!lower.contains(include.get(k))) {
					ok = false;
					break;
				}
			}
			if (!ok) continue;
			for (int k = 0; k < exclude.size(); k++) {
				if (lower.contains(exclude.get(k))) {
					ok = false;
					break;
				}
			}
			if (ok) bits.set(i);
		}
		return bits;
	}

	private void ensurePostings() {
		if (postingOffsets != null) return;
		int[] counts = new int[texts.length];
		ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		for (int r = 0; r < regionCount; r++) {
			int off = dataOffsets[r];
			int n = regionSignCounts[r];
			for (int s = 0; s < n; s++) {
				int rec = off + s * SIGN_REC_SIZE;
				if (rec + SIGN_REC_SIZE > view.limit()) continue;
				int textIdx = view.getInt(rec + 10);
				if (textIdx >= 0 && textIdx < texts.length) counts[textIdx]++;
			}
		}
		int[] starts = new int[texts.length + 1];
		for (int i = 0; i < texts.length; i++) {
			starts[i + 1] = starts[i] + counts[i];
		}
		int[] offsets = new int[starts[texts.length]];
		int[] write = new int[texts.length];
		System.arraycopy(starts, 0, write, 0, texts.length);
		for (int r = 0; r < regionCount; r++) {
			int off = dataOffsets[r];
			int n = regionSignCounts[r];
			for (int s = 0; s < n; s++) {
				int rec = off + s * SIGN_REC_SIZE;
				if (rec + SIGN_REC_SIZE > view.limit()) continue;
				int textIdx = view.getInt(rec + 10);
				if (textIdx >= 0 && textIdx < texts.length) {
					offsets[write[textIdx]++] = rec;
				}
			}
		}
		postingStarts = starts;
		postingOffsets = offsets;
	}

	private SignSearchResult gather(BitSet match, int maxGroups, SignFilter filter) {
		int[] starts = postingStarts;
		int[] offsets = postingOffsets;
		if (starts == null || offsets == null) {
			return new SignSearchResult(0, 0, List.of(), null);
		}
		Map<String, Acc> groups = new HashMap<>();
		int totalHits = 0;
		ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		for (int textIdx = match.nextSetBit(0); textIdx >= 0; textIdx = match.nextSetBit(textIdx + 1)) {
			String raw = texts[textIdx];
			String key = normalize(raw);
			if (key.isEmpty()) continue;
			int from = starts[textIdx];
			int to = starts[textIdx + 1];
			for (int i = from; i < to; i++) {
				int rec = offsets[i];
				if (rec + SIGN_REC_SIZE > view.limit()) continue;
				int flags = view.getShort(rec + 18) & 0xFFFF;
				if (filter.hideOffensive() && SignFlags.has(flags, SignFlags.OFFENSIVE)) continue;
				if (filter.requireFlags() != 0 && (flags & filter.requireFlags()) != filter.requireFlags()) continue;
				if (filter.excludeFlags() != 0 && (flags & filter.excludeFlags()) != 0) continue;
				int authorIdx = view.getShort(rec + 14) & 0xFFFF;
				if (filter.needsAuthor() && !authorOk(filter, authorIdx)) continue;
				int dateDays = view.getShort(rec + 16);
				if (filter.needsDate()) {
					if (dateDays <= 0 || dateDays < filter.minDateDays() || dateDays > filter.maxDateDays()) {
						continue;
					}
				}
				totalHits++;
				Acc acc = groups.get(key);
				if (acc == null) {
					acc = new Acc();
					acc.display = raw == null ? "" : raw;
					groups.put(key, acc);
				}
				acc.occurrences++;
				if (authorIdx != 0xFFFF) acc.authorIdxs.add(authorIdx);
				float weight = weightOf(authorIdx);
				if (weight > acc.bestWeight) {
					acc.bestWeight = weight;
					acc.bestOffset = rec;
					if (raw != null && !raw.isEmpty()) acc.display = raw;
				}
			}
		}
		List<Acc> rankedAcc = new ArrayList<>(groups.values());
		rankedAcc.sort(Comparator.comparingDouble(
			(Acc a) -> a.occurrences * Math.max(0.01f, a.bestWeight)).reversed());
		int unique = rankedAcc.size();
		if (rankedAcc.size() > maxGroups) rankedAcc = rankedAcc.subList(0, maxGroups);
		List<SignHit> ranked = new ArrayList<>(rankedAcc.size());
		for (Acc acc : rankedAcc) ranked.add(hitAt(acc));
		return new SignSearchResult(unique, totalHits, ranked, null);
	}

	private SignHit hitAt(Acc acc) {
		int x = 0;
		int y = 0;
		int z = 0;
		String author = null;
		int dateDays = -1;
		if (acc.bestOffset >= 0) {
			ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			int rec = acc.bestOffset;
			x = view.getInt(rec);
			y = view.getShort(rec + 4);
			z = view.getInt(rec + 6);
			int authorIdx = view.getShort(rec + 14) & 0xFFFF;
			dateDays = view.getShort(rec + 16);
			if (authorIdx != 0xFFFF && authorIdx < authors.length) author = authors[authorIdx].name;
		}
		return new SignHit(acc.display, x, y, z, author, acc.occurrences, acc.authorIdxs.size(), dateDays);
	}

	private boolean authorOk(SignFilter filter, int authorIdx) {
		boolean wantName = filter.authorContains() != null && !filter.authorContains().isEmpty();
		if (authorIdx == 0xFFFF || authorIdx >= authors.length) return !wantName;
		Author a = authors[authorIdx];
		if (filter.maxAuthorGlobalCount() > 0 && a.globalCount > filter.maxAuthorGlobalCount()) return false;
		if (wantName) {
			return a.name != null && a.name.toLowerCase(Locale.ROOT).contains(filter.authorContains());
		}
		return true;
	}

	private float weightOf(int authorIdx) {
		if (authorIdx == 0xFFFF || authorIdx >= authors.length) return 0.25f;
		return authors[authorIdx].weight;
	}

	private static String normalize(String text) {
		if (text == null || text.isEmpty()) return "";
		StringBuilder sb = new StringBuilder(text.length());
		boolean pendingSpace = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				pendingSpace = sb.length() > 0;
				continue;
			}
			if (pendingSpace) {
				sb.append(' ');
				pendingSpace = false;
			}
			sb.append(Character.toLowerCase(c));
		}
		return sb.toString();
	}

	@Override
	public void close() {
		postingStarts = null;
		postingOffsets = null;
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static final class Author {
		final String name;
		final float weight;
		final int globalCount;

		Author(String name, float weight, int globalCount) {
			this.name = name;
			this.weight = weight;
			this.globalCount = globalCount;
		}
	}

	private static final class Acc {
		int occurrences;
		final HashSet<Integer> authorIdxs = new HashSet<>();
		float bestWeight = -1f;
		int bestOffset = -1;
		String display = "";
	}
}
