package dev.greencorps.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookTest {
	@Test
	void pingMessagesPassTheScrubber() {
		for (String msg : List.of(
			"💛 **Totem popped** · 3 left · HP 4 · <@123456789012345678>",
			"🛡️ **Totem popped** · **no totems left** · HP 2",
			"💀 **Died** · \"just1n_t1me was slain by Steve using [Diamond Sword]\" · <@123456789012345678>",
			"💥 **Heavy damage** · −10 HP in 1s · HP 6/20 · <@123456789012345678>",
			"🚀 **Rockets low** · 48 in inventory · 96 incl. shulkers & ender chest",
			"🪽 **Elytra low** · pool 18% · worn 12% · 1 usable spare")) {
			assertNull(CoordScrubber.rejectReason(msg), msg);
		}
		assertNotNull(CoordScrubber.rejectReason("💀 **Died** · \"fell at 1200 64 -3400\""));
	}

	@Test
	void bodyOnlyAllowsTheGivenUser() {
		String body = DiscordWebhook.buildBody("GreenCorps · just1n_t1me", "hi @everyone", List.of("123456789012345678", "nope"));
		assertTrue(body.contains("\"allowed_mentions\":{\"parse\":[],\"users\":[\"123456789012345678\"]}"), body);
		assertTrue(body.contains("\"username\":\"GreenCorps · just1n_t1me\""), body);
		assertFalse(body.contains("nope"), body);
	}

	@Test
	void onlyDiscordWebhookHosts() {
		assertTrue(DiscordWebhook.isDiscordWebhookUrl("https://discord.com/api/webhooks/1/abc"));
		assertFalse(DiscordWebhook.isDiscordWebhookUrl("http://discord.com/api/webhooks/1/abc"));
		assertFalse(DiscordWebhook.isDiscordWebhookUrl("https://discord.com.evil.io/api/webhooks/1/abc"));
		assertFalse(DiscordWebhook.isDiscordWebhookUrl(null));
	}
}
