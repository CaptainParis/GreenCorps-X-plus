package dev.greencorps.signs;

/**
 * Chip state merged with the query. Zero {@code maxAuthorGlobalCount} means the spam cap is off.
 */
public record SignFilter(
	int requireFlags,
	int excludeFlags,
	boolean hideOffensive,
	int maxAuthorGlobalCount,
	String authorContains,
	int minDateDays,
	int maxDateDays
) {
	public static SignFilter none() {
		return new SignFilter(0, 0, true, 0, "", Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	public SignFilter merge(SignQuery q) {
		String author = authorContains == null || authorContains.isEmpty()
			? q.authorContains()
			: authorContains;
		return new SignFilter(
			requireFlags | q.requireFlags(),
			excludeFlags | q.excludeFlags(),
			hideOffensive,
			maxAuthorGlobalCount,
			author == null ? "" : author,
			Math.max(minDateDays, q.minDateDays()),
			Math.min(maxDateDays, q.maxDateDays())
		);
	}

	public boolean needsAuthor() {
		return maxAuthorGlobalCount > 0 || (authorContains != null && !authorContains.isEmpty());
	}

	public boolean needsDate() {
		return minDateDays != Integer.MIN_VALUE || maxDateDays != Integer.MAX_VALUE;
	}
}
