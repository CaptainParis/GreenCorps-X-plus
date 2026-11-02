package dev.greencorps.modules;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayTitleTest {
	@Test
	void singleAndStackTitles() {
		assertEquals("GW (unused)", GatewayESP.gatewayTitle(false, 0, 1, null));
		assertEquals(
			"GW (100 70 -20)",
			GatewayESP.gatewayTitle(false, 0, 1, new BlockPos(100, 70, -20))
		);
		assertEquals("Gateway Stack #3 (4x)", GatewayESP.gatewayTitle(true, 3, 4, null));
		assertEquals(
			"Gateway Stack #3 (4x) (100 70 -20)",
			GatewayESP.gatewayTitle(true, 3, 4, new BlockPos(100, 70, -20))
		);
	}
}
