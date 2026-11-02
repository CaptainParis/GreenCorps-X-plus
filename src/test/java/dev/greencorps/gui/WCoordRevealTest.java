package dev.greencorps.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WCoordRevealTest {
	@Test
	void masksDigitsKeepsSignAndSpaces() {
		assertEquals("-#### ## ####", WCoordReveal.maskDigits("-7137 63 7123"));
		assertEquals("##### # #####", WCoordReveal.maskDigits("12345 6 78901"));
		assertEquals("# # #", WCoordReveal.maskDigits("0 0 0"));
		assertEquals("", WCoordReveal.maskDigits(""));
	}
}
