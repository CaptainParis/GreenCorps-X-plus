package dev.greencorps.mapdupe;

import java.util.List;
import java.util.Map;

/**
 * A planned copy job: the steps, the inventory they should leave, and what they need. Map jobs carry their
 * {@code goal} (map key → target count) so the rest can be re-planned from the live inventory.
 */
public record CopyPlan(List<Step> steps, InvSim end, int clicks, int emptiesUsed, int copies, String error, Map<Long, Integer> goal) {
	public static CopyPlan failed(String error) {
		return new CopyPlan(List.of(), null, 0, 0, 0, error, null);
	}

	public boolean ok() {
		return error == null;
	}
}
