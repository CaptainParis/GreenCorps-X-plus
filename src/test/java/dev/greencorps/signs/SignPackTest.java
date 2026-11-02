package dev.greencorps.signs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SignPackTest {
	@Test
	void groupsCopiesAndKeepsTheHeavierLocation() throws IOException {
		byte[] pack = fixture(
			new String[] {"Ada", "Bea"},
			new float[] {0.2f, 0.9f},
			new int[] {2, 1},
			new String[] {"Hello world", "shop open"},
			new int[][] {
				{10, 64, -20, 0, 0, 0, 0},
				{500, 70, 800, 0, 1, days(2016), SignFlags.DATED},
				{1, 63, 2, 1, 0, 0, SignFlags.ECONOMY}
			}
		);
		try (SignPack index = SignPack.open(pack)) {
			SignSearchResult hello = index.search("hello", 10, SignFilter.none());
			assertEquals(1, hello.uniqueGroups());
			assertEquals(2, hello.totalHits());
			SignHit hit = hello.groups().get(0);
			assertEquals("500 70 800", hit.coords());
			assertEquals("Bea", hit.author());
			assertEquals(2, hit.occurrences());
			assertEquals(2, hit.distinctAuthors());
			assertNotNull(hit.dateLabel());

			SignSearchResult shop = index.search("shop flag:economy", 10, SignFilter.none());
			assertEquals(1, shop.totalHits());
			assertEquals("1 63 2", shop.groups().get(0).coords());

			SignSearchResult year = index.search("hello year:2016", 10, SignFilter.none());
			assertEquals(1, year.totalHits());
			assertEquals("500 70 800", year.groups().get(0).coords());

			assertTrue(index.search("", 10, SignFilter.none()).groups().isEmpty());
			assertTrue(index.search("missing", 10, SignFilter.none()).groups().isEmpty());
		}
	}

	@Test
	void authorQueryAndOffensiveFilter() throws IOException {
		byte[] pack = fixture(
			new String[] {"Ada"},
			new float[] {1f},
			new int[] {1},
			new String[] {"kept line", "bad line"},
			new int[][] {
				{3, 64, 4, 0, 0, 0, 0},
				{9, 64, 9, 1, 0, 0, SignFlags.OFFENSIVE}
			}
		);
		try (SignPack index = SignPack.open(pack)) {
			SignSearchResult byAuthor = index.search("author:ada", 10, SignFilter.none());
			assertEquals(1, byAuthor.totalHits());
			assertEquals("kept line", byAuthor.groups().get(0).text());

			SignSearchResult hidden = index.search("line", 10, SignFilter.none());
			assertEquals(1, hidden.totalHits());
			assertEquals("kept line", hidden.groups().get(0).text());
		}
	}

	@Test
	void parsesQuotedExcludeAndYear() {
		SignQuery q = SignQuery.parse("stash \"free shulkers\" -grief author:pyro flag:economy year:2016..2019");
		assertEquals(java.util.List.of("stash", "free shulkers"), q.include());
		assertEquals(java.util.List.of("grief"), q.exclude());
		assertEquals("pyro", q.authorContains());
		assertEquals(SignFlags.ECONOMY, q.requireFlags());
		assertTrue(q.minDateDays() < q.maxDateDays());
		assertFalse(q.isEmpty());
		assertTrue(SignQuery.parse("").isEmpty());
		assertTrue(SignQuery.parse("year:nope").hasSyntaxError());
	}

	private static int days(int year) {
		return (year - 2000) * 365 + 40;
	}

	private static byte[] fixture(
		String[] authors,
		float[] weights,
		int[] counts,
		String[] texts,
		int[][] signs
	) {
		int size = 24;
		for (String author : authors) size += 2 + utf8(author).length + 8;
		for (String text : texts) size += 2 + utf8(text).length;
		size += 16;
		size += signs.length * 20;
		ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(SignPack.MAGIC);
		buf.putInt(1);
		buf.putInt(authors.length);
		buf.putInt(texts.length);
		buf.putInt(signs.length);
		buf.putInt(1);
		for (int i = 0; i < authors.length; i++) {
			byte[] raw = utf8(authors[i]);
			buf.putShort((short) raw.length);
			buf.put(raw);
			buf.putFloat(weights[i]);
			buf.putInt(counts[i]);
		}
		for (String text : texts) {
			byte[] raw = utf8(text);
			buf.putShort((short) raw.length);
			buf.put(raw);
		}
		int data = buf.position() + 16;
		buf.putInt(0);
		buf.putInt(0);
		buf.putInt(data);
		buf.putInt(signs.length);
		for (int[] s : signs) {
			buf.putInt(s[0]);
			buf.putShort((short) s[1]);
			buf.putInt(s[2]);
			buf.putInt(s[3]);
			buf.putShort((short) s[4]);
			buf.putShort((short) s[5]);
			buf.putShort((short) s[6]);
		}
		return buf.array();
	}

	private static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}
}
