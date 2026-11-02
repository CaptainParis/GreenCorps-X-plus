package dev.greencorps.signs;

/**
 * One deduped sign text and the location of its representative copy.
 */
public record SignHit(
	String text,
	int x,
	int y,
	int z,
	String author,
	int occurrences,
	int distinctAuthors,
	int dateDays
) {
	public SignHit {
		text = text == null ? "" : text;
	}

	/** Clipboard form: {@code x y z}. */
	public String coords() {
		return x + " " + y + " " + z;
	}

	/** Rough "Mon YYYY" label, or null when the sign has no date. */
	public String dateLabel() {
		if (dateDays <= 0) return null;
		int year = 2000 + dateDays / 365;
		int rem = dateDays % 365;
		String[] months = {
			"Jan", "Feb", "Mar", "Apr", "May", "Jun",
			"Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
		};
		int[] mdays = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
		int mon = 0;
		for (int i = 0; i < 12; i++) {
			if (rem < mdays[i]) {
				mon = i;
				break;
			}
			rem -= mdays[i];
			mon = i;
		}
		return months[Math.min(11, mon)] + " " + year;
	}
}
