package dev.greencorps.stasis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Named account (main/alt) for per-home pearl quotas and notify gating.
 * Stored in chambers.json — not Meteor settings.
 */
public class StasisPlayerProfile {
	public String id;
	public String label = "Main";
	public List<String> igns = new ArrayList<>();
	public List<String> uuids = new ArrayList<>();
	/** Seed required pearls when creating a new home (0 = no quota for this account by default). */
	public int defaultPearls = 1;
	/** When true, missing/leave alerts fire if this account is short on a home. */
	public boolean notify = true;

	public StasisPlayerProfile() {}

	public StasisPlayerProfile(String label, int defaultPearls, boolean notify) {
		this.id = UUID.randomUUID().toString();
		this.label = label == null || label.isBlank() ? "Main" : label.trim();
		this.defaultPearls = Math.max(0, defaultPearls);
		this.notify = notify;
	}

	public boolean matchesUuid(String uuid) {
		if (uuid == null || uuid.isBlank() || uuids == null) return false;
		for (String u : uuids) {
			if (u != null && uuid.equalsIgnoreCase(u.trim())) return true;
		}
		return false;
	}

	public boolean matchesIgn(String ign) {
		if (ign == null || ign.isBlank() || igns == null) return false;
		String needle = ign.trim();
		for (String i : igns) {
			if (i != null && needle.equalsIgnoreCase(i.trim())) return true;
		}
		return false;
	}

	/** UUID first, then IGN. */
	public boolean matches(String uuid, String ign) {
		if (matchesUuid(uuid)) return true;
		return matchesIgn(ign);
	}

	public boolean matchesPearl(StasisPearl pearl) {
		if (pearl == null) return false;
		return matches(pearl.ownerUuid, pearl.ownerIgn);
	}

	public String primaryIgn() {
		if (igns == null || igns.isEmpty()) return null;
		for (String i : igns) {
			if (i != null && !i.isBlank()) return i.trim();
		}
		return null;
	}

	public String ignsJoined() {
		if (igns == null || igns.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		for (String i : igns) {
			if (i == null || i.isBlank()) continue;
			if (!sb.isEmpty()) sb.append(", ");
			sb.append(i.trim());
		}
		return sb.toString();
	}

	public void setIgnsFromCsv(String csv) {
		igns = new ArrayList<>();
		if (csv == null || csv.isBlank()) return;
		for (String part : csv.split(",")) {
			String t = part.trim();
			if (!t.isEmpty()) igns.add(t);
		}
	}

	public void ensureId() {
		if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
	}

	public String displayLabel() {
		return label == null || label.isBlank() ? "?" : label.trim();
	}

	@Override
	public String toString() {
		return displayLabel().toLowerCase(Locale.ROOT);
	}
}
