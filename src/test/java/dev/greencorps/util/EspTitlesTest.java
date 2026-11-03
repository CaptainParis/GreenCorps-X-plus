package dev.greencorps.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EspTitlesTest {
	@Test
	void onlyGatewayGetsItsOwnTitle() {
		assertEquals("Gateways Esp", EspTitles.titleFor("gateway-esp"));
		assertNull(EspTitles.titleFor("stasis-chambers"));
		assertNull(EspTitles.titleFor(null));
	}
}
