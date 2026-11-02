package dev.greencorps.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EspTitlesTest {
	@Test
	void rewritesEspWordToAllCaps() {
		assertEquals("ESP", EspTitles.espTitle("Esp"));
		assertEquals("Gateway ESP", EspTitles.espTitle("Gateway Esp"));
		assertEquals("Breeze Farm ESP", EspTitles.espTitle("Breeze Farm Esp"));
		assertEquals("ESP Seat Only", EspTitles.espTitle("Esp Seat Only"));
		assertEquals("ESP Others", EspTitles.espTitle("Esp Others"));
		assertEquals("Solid Pocket", EspTitles.espTitle("Solid Pocket"));
	}
}
