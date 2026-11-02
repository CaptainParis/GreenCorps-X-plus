package dev.greencorps.book;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BookTemplatesTest {
	@Test
	void repeatNameGetsAShortTimeSuffix() {
		LocalTime time = LocalTime.of(19, 57, 4);
		assertEquals("Notes", BookTemplates.uniqueName(Set.of(), "Notes", time));
		assertEquals("Notes 1957", BookTemplates.uniqueName(Set.of("Notes"), "Notes", time));
		assertEquals("Notes 195704", BookTemplates.uniqueName(Set.of("Notes", "Notes 1957"), "Notes", time));
	}

	@Test
	void copyNameSkipsTakenCopies() {
		assertEquals("Notes copy", BookTemplates.copyName(Set.of("Notes"), "Notes"));
		assertEquals("Notes copy 2", BookTemplates.copyName(Set.of("Notes", "Notes copy"), "Notes"));
	}

	@Test
	void deleteActiveSelectsNextAndEmptyListMakesUntitled(@TempDir Path dir) {
		Path file = dir.resolve("book-templates.json");
		BookTemplates store = BookTemplates.load(file, "", "", "one");
		String first = store.active().id;
		store.addHeld("Second", "Second", "", "two");
		String second = store.active().id;
		assertNotEquals(first, second);

		BookTemplates.Template after = store.delete(second);
		assertEquals(first, after.id);
		assertEquals("one", after.text);

		store.delete(first);
		assertEquals("Untitled", store.active().name);
		assertEquals("", store.active().text);

		BookTemplates again = BookTemplates.load(file, "ignored", "ignored", "ignored");
		assertEquals("Untitled", again.active().name);
	}

	@Test
	void pinnedRowsStayOnTop(@TempDir Path dir) {
		BookTemplates store = BookTemplates.load(dir.resolve("book-templates.json"), "", "", "");
		String first = store.active().id;
		store.active().name = "A";
		store.addHeld("B", "", "", "");
		store.setPinned(first, true);
		assertEquals(first, store.displayOrder().get(0).id);
		assertTrue(store.displayOrder().get(0).pinned);
	}
}
