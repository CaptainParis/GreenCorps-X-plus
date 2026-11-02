package dev.greencorps.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookPagesTest {
	private static final BookPages.Width MONO = text -> text.length() * 6;
	private static final BookPages.Width NO_WRAP = text -> 0;

	@Test
	void shortTextFitsAndReportsFullCapacity() {
		BookPages.Split split = BookPages.split("Hello", MONO);
		assertEquals(1, split.pages().size());
		assertEquals("Hello", split.pages().get(0));
		assertEquals(5, split.used());
		assertEquals(BookPages.CAPACITY, split.available());
		assertFalse(split.overflow());
	}

	@Test
	void keepsExplicitNewlinesOnOnePage() {
		BookPages.Split split = BookPages.split("a\nb\nc", MONO);
		assertEquals(1, split.pages().size());
		assertEquals("a\nb\nc", split.pages().get(0));
		assertFalse(split.overflow());
	}

	@Test
	void splitsAfterFourteenLines() {
		String text = "a\n".repeat(14) + "b";
		BookPages.Split split = BookPages.split(text, MONO);
		assertEquals(2, split.pages().size());
		assertEquals("a\n".repeat(13) + "a", split.pages().get(0));
		assertEquals("b", split.pages().get(1));
		assertEquals(text.length(), split.used());
		assertEquals(BookPages.CAPACITY, split.available());
		assertFalse(split.overflow());
	}

	@Test
	void wrapsOnSpacesAndDropsTheBreakSpacePastTheLastLine() {
		String word = "a".repeat(19);
		String text = (word + " ").repeat(14) + word;
		BookPages.Split split = BookPages.split(text, MONO);
		assertEquals(2, split.pages().size());
		assertEquals((word + " ").repeat(13) + word, split.pages().get(0));
		assertEquals(word, split.pages().get(1));
		assertFalse(split.pages().get(0).endsWith(" "));
		assertFalse(split.overflow());
	}

	@Test
	void hardBreaksATokenWiderThanTheLine() {
		String text = "a".repeat(25);
		BookPages.Split split = BookPages.split(text, MONO);
		assertEquals(1, split.pages().size());
		assertEquals(text, split.pages().get(0));
	}

	@Test
	void capsAPageAt1024Characters() {
		String text = "a".repeat(BookPages.MAX_PAGE_LENGTH + 1);
		BookPages.Split split = BookPages.split(text, NO_WRAP);
		assertEquals(2, split.pages().size());
		assertEquals(BookPages.MAX_PAGE_LENGTH, split.pages().get(0).length());
		assertEquals(1, split.pages().get(1).length());
		assertEquals(text.length(), split.used());
		assertEquals(BookPages.CAPACITY, split.available());
		assertFalse(split.overflow());
	}

	@Test
	void capsAtOneHundredPagesAndShrinksAvailable() {
		int extra = 5;
		String text = "a".repeat(BookPages.CAPACITY + extra);
		BookPages.Split split = BookPages.split(text, NO_WRAP);
		assertEquals(BookPages.MAX_PAGES, split.pages().size());
		assertEquals(BookPages.MAX_PAGE_LENGTH, split.pages().get(0).length());
		assertEquals(BookPages.MAX_PAGE_LENGTH, split.pages().get(BookPages.MAX_PAGES - 1).length());
		assertEquals(text.length(), split.used());
		assertEquals(BookPages.CAPACITY, split.available());
		assertTrue(split.overflow());
		assertTrue(split.used() > split.available());
	}

	@Test
	void normalizesCarriageReturns() {
		BookPages.Split split = BookPages.split("a\r\nb\rc", MONO);
		assertEquals("a\nb\nc", split.pages().get(0));
		assertEquals(5, split.used());
	}

	@Test
	void clampsSignerToTitleLength() {
		assertEquals("", BookPages.clampSigner(null));
		assertEquals("", BookPages.clampSigner(""));
		assertEquals("abcd", BookPages.clampSigner("ab\ncd"));
		assertEquals(BookPages.MAX_TITLE_LENGTH, BookPages.clampSigner("a".repeat(40)).length());
		assertEquals("a".repeat(32), BookPages.clampSigner("a".repeat(40)));
		assertEquals("hi", BookPages.signerTitle("  hi  "));
		assertEquals("", BookPages.signerTitle("   "));
		assertEquals("Title", BookPages.signTitle("  Title  "));
		assertEquals("", BookPages.signTitle("   "));
	}

	@Test
	void smartBreaksOffKeepsTodaysCuts() {
		String intro = "a\n".repeat(4);
		String para = "b\n".repeat(11) + "b";
		String text = intro + "\n" + para;
		BookPages.Split plain = BookPages.split(text, MONO);
		BookPages.Split off = BookPages.split(text, MONO, false);
		assertEquals(plain.pages(), off.pages());
		assertTrue(off.pages().get(0).contains("a"));
		assertTrue(off.pages().get(0).contains("b"));
	}

	@Test
	void smartBreakPushesAParagraphThatFitsOnTheNextPage() {
		String intro = "a\n".repeat(4);
		String para = "b\n".repeat(11) + "b";
		String text = intro + "\n" + para;
		BookPages.Split smart = BookPages.split(text, MONO, true);
		assertFalse(smart.pages().get(0).contains("b"));
		assertTrue(smart.pages().get(1).startsWith("b"));
		assertTrue(smart.pages().get(1).endsWith("b"));
		assertFalse(smart.pages().get(1).contains("a"));
	}

	@Test
	void smartBreakPushesAHeadingLeftAloneAtTheBottom() {
		String text = "a\n".repeat(12) + "\nTitle\n\n" + "b\n".repeat(3) + "b";
		BookPages.Split plain = BookPages.split(text, MONO, false);
		assertTrue(plain.pages().get(0).endsWith("Title"));
		BookPages.Split smart = BookPages.split(text, MONO, true);
		assertFalse(smart.pages().get(0).contains("Title"));
		assertTrue(smart.pages().get(1).startsWith("Title"));
	}

	@Test
	void spansMatchPagesAndOverflowStopsAtCapacity() {
		String text = "a\n".repeat(14) + "b";
		BookPages.Split split = BookPages.split(text, MONO);
		assertEquals(split.pages().get(0), text.substring(split.spans().get(0).start(), split.spans().get(0).end()));
		assertEquals(text.length(), split.consumed());

		String huge = "a".repeat(BookPages.CAPACITY + 5);
		BookPages.Split over = BookPages.split(huge, NO_WRAP);
		assertEquals(BookPages.CAPACITY, over.consumed());
		assertEquals(5, huge.length() - over.consumed());
	}

	@Test
	void pageNumberPastTheEndClampsToTheLastPage() {
		assertEquals(3, BookPages.clampPageNumber(99, 3));
		assertEquals(1, BookPages.clampPageNumber(0, 3));
		assertEquals(2, BookPages.clampPageNumber(2, 3));
	}

	@Test
	void spreadKeepsTwoPagesAndBreakAfterStartsTheNext() {
		assertEquals(0, BookPages.spreadLeft(0));
		assertEquals(0, BookPages.spreadLeft(1));
		assertEquals(4, BookPages.spreadLeft(5));
		assertEquals(4, BookPages.lastSpread(6));
		assertEquals(6, BookPages.lastSpread(7));

		String body = "a\nb";
		String raw = body + BookPages.breakAfter(body) + "Z";
		BookPages.Split split = BookPages.split(raw, NO_WRAP);
		assertEquals(2, split.pages().size());
		assertEquals(body, split.pages().get(0).replaceAll("\n+$", ""));
		assertTrue(split.pages().get(1).startsWith("Z"));
	}

	@Test
	void replaceSpanReflowsAroundTheEdit() {
		String text = "Hello";
		assertEquals("HeXllo", BookPages.replaceSpan(text, 2, 2, "X"));
		assertEquals("lo", BookPages.replaceSpan(text, 0, 3, ""));
	}
}
