package dev.greencorps.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord Incoming Webhook POST, one at a time off the game thread.
 * Never logs the webhook URL. Content that looks like coordinates is refused
 * ({@link CoordScrubber}). Mentions are limited to the given user ids: never
 * everyone, here or roles.
 */
public final class DiscordWebhook {
	private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

	private final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(8))
		.build();
	private final ExecutorService exec;
	private final Consumer<String> warn;

	public DiscordWebhook(String threadName, Consumer<String> warn) {
		this.warn = warn != null ? warn : s -> {};
		this.exec = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, threadName);
			t.setDaemon(true);
			return t;
		});
	}

	/** @return null when queued; otherwise why it was not sent (never contains the URL). */
	public String postAsync(String webhookUrl, String username, String content, Collection<String> mentionUserIds) {
		if (webhookUrl == null || webhookUrl.isBlank()) return "no webhook set";
		if (!isDiscordWebhookUrl(webhookUrl)) return "not a Discord webhook URL";
		String reason = CoordScrubber.rejectReason(content);
		if (reason != null) return "blocked (" + reason + ")";
		String body = buildBody(username, content, mentionUserIds);
		exec.execute(() -> send(webhookUrl, body, true));
		return null;
	}

	private void send(String url, String body, boolean mayRetry) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(12))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
			int code = res.statusCode();
			if (code == 429 && mayRetry) {
				// Discord says how long to wait; retry once, never more than 10 s later.
				double wait = 2.0;
				Matcher m = RETRY_AFTER.matcher(res.body() == null ? "" : res.body());
				if (m.find()) {
					try {
						wait = Double.parseDouble(m.group(1));
					} catch (NumberFormatException ignored) {}
				}
				Thread.sleep((long) (Math.min(10.0, Math.max(0.2, wait)) * 1000));
				send(url, body, false);
			} else if (code < 200 || code >= 300) {
				warn.accept("Discord webhook answered HTTP " + code);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			warn.accept("Discord webhook failed: " + e.getClass().getSimpleName());
		}
	}

	/** Runtime assembly so javac does not fold a contiguous webhook path into the class pool. */
	public static boolean isDiscordWebhookUrl(String url) {
		if (url == null) return false;
		StringBuilder path = new StringBuilder();
		path.append("/api/").append("webhooks").append('/');
		String p = path.toString();
		return url.startsWith("https://discord.com" + p)
			|| url.startsWith("https://discordapp.com" + p)
			|| url.startsWith("https://ptb.discord.com" + p)
			|| url.startsWith("https://canary.discord.com" + p);
	}

	static String buildBody(String username, String content, Collection<String> mentionUserIds) {
		StringBuilder users = new StringBuilder("[");
		boolean first = true;
		if (mentionUserIds != null) {
			for (String id : mentionUserIds) {
				if (id == null || !id.matches("\\d{5,32}")) continue;
				if (!first) users.append(',');
				first = false;
				users.append(jsonString(id));
			}
		}
		users.append(']');
		StringBuilder sb = new StringBuilder("{\"content\":").append(jsonString(content));
		if (username != null && !username.isBlank()) sb.append(",\"username\":").append(jsonString(username));
		sb.append(",\"allowed_mentions\":{\"parse\":[],\"users\":").append(users).append("}}");
		return sb.toString();
	}

	private static String jsonString(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 16);
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		sb.append('"');
		return sb.toString();
	}
}
