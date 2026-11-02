package dev.greencorps.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WBookTextTest {
	@Test
	void setBeforeThemeDoesNotThrow() {
		WBookText box = new WBookText("You are already\non the page.");
		box.set("You are already\non the page.\n\nnext", 16);
		assertEquals(16, box.cursor());
		assertEquals("You are already\non the page.\n\nnext", box.get());
	}
}
