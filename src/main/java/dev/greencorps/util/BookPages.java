package dev.greencorps.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits preset book text the way the 1.21.4 book screen lays a page out.
 * Width is injected so tests can run without a client font.
 *
 * <p>Page box matches {@code BookEditScreen}: 114×128 px, 9 px per line (14 lines).
 * Character caps match {@code WritableBookContentComponent} (1024 per page, 100 pages).
 * Signer length matches {@code WrittenBookContentComponent.MAX_TITLE_LENGTH} (32).
 */
public final class BookPages {
	public static final int PAGE_WIDTH = 114;
	public static final int LINE_HEIGHT = 9;
	public static final int PAGE_HEIGHT = 128;
	public static final int LINES_PER_PAGE = PAGE_HEIGHT / LINE_HEIGHT;
	public static final int MAX_PAGE_LENGTH = 1024;
	public static final int MAX_PAGES = 100;
	public static final int CAPACITY = MAX_PAGES * MAX_PAGE_LENGTH;
	public static final int MAX_TITLE_LENGTH = 32;

	@FunctionalInterface
	public interface Width {
		int of(String text);
	}

	public record Span(int start, int end) {
	}

	public record Split(List<String> pages, int used, int available, boolean overflow, int consumed, List<Span> spans) {
	}

	private record Cut(int contentEnd, int next) {
	}

	private BookPages() {
	}

	public static Split split(String raw, Width width) {
		return split(raw, width, false);
	}

	/**
	 * @param smartBreaks when true, keep a blank-line paragraph together if it fits on the
	 * next page, and do not leave a one-line heading alone at the bottom of a page
	 */
	public static Split split(String raw, Width width, boolean smartBreaks) {
		String text = normalize(raw);
		List<String> pages = new ArrayList<>();
		List<Span> spans = new ArrayList<>();
		int index = 0;
		while (index < text.length() && pages.size() < MAX_PAGES) {
			Cut cut = cutPage(text, index, width);
			if (smartBreaks) cut = smartAdjust(text, index, cut, width);
			if (cut.next <= index) {
				int step = Math.min(text.length(), index + 1);
				cut = new Cut(step, step);
			}
			int contentEnd = Math.max(index, Math.min(cut.contentEnd, text.length()));
			pages.add(text.substring(index, contentEnd));
			spans.add(new Span(index, contentEnd));
			index = Math.min(text.length(), Math.max(cut.next, contentEnd));
		}
		if (pages.isEmpty()) {
			pages.add("");
			spans.add(new Span(0, 0));
		}
		boolean overflow = index < text.length();
		int available = overflow ? index : CAPACITY;
		return new Split(List.copyOf(pages), text.length(), available, overflow, index, List.copyOf(spans));
	}

	/** Even page index that starts the spread containing {@code pageIndex}. */
	public static int spreadLeft(int pageIndex) {
		if (pageIndex <= 0) return 0;
		return pageIndex - (pageIndex % 2);
	}

	/** Left page of the last spread. */
	public static int lastSpread(int pageCount) {
		if (pageCount <= 1) return 0;
		return spreadLeft(pageCount - 1);
	}

	/**
	 * Newlines to append after one page body so the next character opens the next page.
	 * The last newline is the break {@link #split} omits.
	 */
	public static String breakAfter(String pageBody) {
		int newlines = 0;
		if (pageBody != null) {
			for (int i = 0; i < pageBody.length(); i++) {
				if (pageBody.charAt(i) == '\n') newlines++;
			}
		}
		int extra = LINES_PER_PAGE - newlines;
		if (extra < 1) extra = 1;
		return "\n".repeat(extra);
	}

	/** 1-based page number clamped into {@code 1..pageCount}. */
	public static int clampPageNumber(int typed, int pageCount) {
		if (pageCount < 1) return 1;
		if (typed < 1) return 1;
		if (typed > pageCount) return pageCount;
		return typed;
	}

	/** Normalized text that did not fit in {@code split}. Empty when nothing was cut off. */
	public static String leftover(Split split, String raw) {
		if (split == null || !split.overflow()) return "";
		String text = normalize(raw);
		int cut = Math.min(text.length(), Math.max(0, split.consumed()));
		return text.substring(cut);
	}

	public static String replaceSpan(String raw, int start, int end, String insert) {
		String text = normalize(raw);
		int from = Math.max(0, Math.min(start, text.length()));
		int to = Math.max(from, Math.min(end, text.length()));
		String mid = normalize(insert);
		return text.substring(0, from) + mid + text.substring(to);
	}

	/** Page index whose span contains {@code index}, or the last page. */
	public static int pageAt(Split split, int index) {
		List<Span> spans = split.spans();
		if (spans.isEmpty()) return 0;
		for (int i = 0; i < spans.size(); i++) {
			Span span = spans.get(i);
			boolean last = i == spans.size() - 1;
			if (index < span.end() || (last && index <= span.end()) || index < span.start()) {
				if (index < span.start() && i > 0) return i - 1;
				return i;
			}
		}
		return spans.size() - 1;
	}

	/** Drop newlines and clamp to the signed-book title length. Keeps surrounding spaces. */
	public static String clampSigner(String signer) {
		if (signer == null || signer.isEmpty()) return "";
		StringBuilder sb = new StringBuilder(Math.min(signer.length(), MAX_TITLE_LENGTH));
		for (int i = 0; i < signer.length() && sb.length() < MAX_TITLE_LENGTH; i++) {
			char c = signer.charAt(i);
			if (c == '\n' || c == '\r') continue;
			sb.append(c);
		}
		return sb.toString();
	}

	/** Title sent when signing. Blank means leave the book unsigned. */
	public static String signerTitle(String signer) {
		return clampSigner(signer).trim();
	}

	public static String clampTitle(String title) {
		return clampSigner(title);
	}

	/** Title string actually written into a signed book. Blank means do not sign. */
	public static String signTitle(String title) {
		return clampTitle(title).trim();
	}

	private static String normalize(String raw) {
		if (raw == null || raw.isEmpty()) return "";
		return raw.replace("\r\n", "\n").replace('\r', '\n');
	}

	/**
	 * Pull a split paragraph onto the next page when the whole paragraph fits there,
	 * then pull a one-line heading off the bottom of the page.
	 */
	private static Cut smartAdjust(String text, int start, Cut cut, Width width) {
		Cut paragraph = pushParagraph(text, start, cut, width);
		if (paragraph.contentEnd > start && paragraph.contentEnd < cut.contentEnd) cut = paragraph;
		Cut heading = pushHeading(text, start, cut);
		if (heading.contentEnd > start && heading.contentEnd < cut.contentEnd) return heading;
		return cut;
	}

	private static Cut pushParagraph(String text, int start, Cut cut, Width width) {
		if (cut.contentEnd <= start || cut.contentEnd > text.length()) return cut;
		int probe = Math.min(text.length() - 1, cut.contentEnd - 1);
		if (probe < start) return cut;
		int paraStart = startOfParagraph(text, probe);
		int paraEnd = endOfParagraph(text, paraStart);
		if (paraStart <= start || cut.contentEnd >= paraEnd) return cut;
		Cut trial = cutPage(text, paraStart, width);
		if (trial.contentEnd < paraEnd && trial.next < paraEnd) return cut;
		return new Cut(paraStart, paraStart);
	}

	private static Cut pushHeading(String text, int start, Cut cut) {
		int contentEnd = Math.min(cut.contentEnd, text.length());
		int lineStart = start;
		int lastNonEmpty = -1;
		for (int i = start; i <= contentEnd; i++) {
			boolean boundary = i == contentEnd || text.charAt(i) == '\n';
			if (!boundary) continue;
			if (i > lineStart) lastNonEmpty = lineStart;
			if (i == contentEnd) break;
			lineStart = i + 1;
		}
		if (lastNonEmpty <= start) return cut;
		int lineEnd = lastNonEmpty;
		while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') lineEnd++;
		if (lineEnd + 1 >= text.length()) return cut;
		if (text.charAt(lineEnd) != '\n' || text.charAt(lineEnd + 1) != '\n') return cut;
		// A heading is its own one-line paragraph, not the last line of a longer one.
		if (startOfParagraph(text, lastNonEmpty) != lastNonEmpty) return cut;
		return new Cut(lastNonEmpty, lastNonEmpty);
	}

	private static int startOfParagraph(String text, int pos) {
		int i = Math.max(0, Math.min(pos, text.length()));
		while (i > 0) {
			if (text.charAt(i - 1) == '\n' && i >= 2 && text.charAt(i - 2) == '\n') return i;
			i--;
		}
		return 0;
	}

	private static int endOfParagraph(String text, int start) {
		for (int i = Math.max(0, start); i < text.length(); i++) {
			if (text.charAt(i) == '\n' && i + 1 < text.length() && text.charAt(i + 1) == '\n') return i;
		}
		return text.length();
	}

	/**
	 * Largest prefix starting at {@code start} that fits one page.
	 * A newline that would open a 15th line is consumed as the page break and omitted.
	 * A space that would overflow the line is the wrap point and is kept on this page
	 * unless that wrap would open a 15th line, in which case the space is skipped.
	 */
	private static Cut cutPage(String text, int start, Width width) {
		int limit = Math.min(text.length(), start + MAX_PAGE_LENGTH);
		int i = start;
		int lineStart = start;
		int lines = 1;
		int lastSpace = -1;

		while (i < limit) {
			char c = text.charAt(i);
			if (c == '\n') {
				if (lines >= LINES_PER_PAGE) return new Cut(i, i + 1);
				lines++;
				i++;
				lineStart = i;
				lastSpace = -1;
				continue;
			}

			if (width.of(text.substring(lineStart, i + 1)) <= PAGE_WIDTH) {
				if (c == ' ') lastSpace = i;
				i++;
				continue;
			}

			if (c == ' ') {
				int next = i + 1;
				if (lines >= LINES_PER_PAGE) return new Cut(i, next);
				lines++;
				lineStart = next;
				i = next;
				lastSpace = -1;
				continue;
			}

			if (lastSpace >= lineStart) {
				int next = lastSpace + 1;
				if (lines >= LINES_PER_PAGE) return new Cut(next, next);
				lines++;
				lineStart = next;
				i = next;
				lastSpace = -1;
				continue;
			}

			if (i == lineStart) {
				i++;
				if (lines >= LINES_PER_PAGE || i >= limit) return new Cut(i, i);
				lines++;
				lineStart = i;
				lastSpace = -1;
				continue;
			}

			if (lines >= LINES_PER_PAGE) return new Cut(i, i);
			lines++;
			lineStart = i;
			lastSpace = -1;
		}
		return new Cut(i, i);
	}
}
