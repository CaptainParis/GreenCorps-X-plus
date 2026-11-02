package dev.greencorps.discord;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.greencorps.GreenCorps;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Discord Pings webhook URL and the Discord user id to mention, kept out of
 * Meteor's settings (and modules.nbt) so they never show on screen.
 * File: {@code config/greencorps/discord-pings.json}, owner-only where the OS
 * supports POSIX permissions. Same shape as Companion's TokenStore.
 */
public final class PingStore {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("greencorps").resolve("discord-pings.json");

	private String webhook = "";
	private String mentionId = "";
	private boolean loaded;

	public synchronized String webhook() {
		load();
		return webhook;
	}

	public synchronized String mentionId() {
		load();
		return mentionId;
	}

	public synchronized void setWebhook(String value) {
		load();
		webhook = value == null ? "" : value.trim();
		save();
	}

	public synchronized void setMentionId(String value) {
		load();
		mentionId = value == null ? "" : value.trim();
		save();
	}

	/** Last four characters only, for telling values apart on screen. */
	public static String mask(String v) {
		if (v == null || v.isBlank()) return "not set";
		String s = v.trim();
		return "••••••••" + (s.length() > 4 ? s.substring(s.length() - 4) : "");
	}

	@Override
	public String toString() {
		return "PingStore[" + mask(webhook) + ", mention " + mask(mentionId) + "]";
	}

	private void load() {
		if (loaded) return;
		loaded = true;
		if (!Files.isRegularFile(FILE)) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
			if (o.has("webhook")) webhook = o.get("webhook").getAsString().trim();
			if (o.has("mentionId")) mentionId = o.get("mentionId").getAsString().trim();
		} catch (IOException | RuntimeException e) {
			GreenCorps.LOG.warn("Discord Pings: settings file unreadable ({})", e.getClass().getSimpleName());
		}
	}

	private void save() {
		JsonObject o = new JsonObject();
		o.addProperty("webhook", webhook);
		o.addProperty("mentionId", mentionId);
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
			ownerOnly(tmp);
			try {
				Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
			}
			ownerOnly(FILE);
		} catch (IOException e) {
			GreenCorps.LOG.warn("Discord Pings: settings file not saved ({})", e.getClass().getSimpleName());
		}
	}

	private static void ownerOnly(Path p) {
		try {
			Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Windows: the file stays in the user profile, which is per-user already.
		}
	}
}
