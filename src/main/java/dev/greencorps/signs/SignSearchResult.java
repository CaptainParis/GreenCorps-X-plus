package dev.greencorps.signs;

import java.util.List;

/** One page of deduped sign groups. {@code error} is a short status line when the query could not run. */
public record SignSearchResult(int uniqueGroups, int totalHits, List<SignHit> groups, String error) {
	public SignSearchResult {
		groups = groups == null ? List.of() : List.copyOf(groups);
	}

	public static SignSearchResult empty() {
		return new SignSearchResult(0, 0, List.of(), null);
	}
}
