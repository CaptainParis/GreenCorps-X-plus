package dev.greencorps.anticlient;

/**
 * Raw vanilla 1.21.4 damage math used to size a threat. These are estimates:
 * terrain exposure and exact enchants are not known on the client for other players.
 */
public final class Damage {
	public static final double CRYSTAL_POWER = 6.0;
	public static final double CRYSTAL_RADIUS = CRYSTAL_POWER * 2.0;
	public static final double MACE_BASE = 6.0;
	public static final double MACE_MIN_FALL = 1.5;
	/** Totem of Undying leaves 1 HP plus Absorption II. */
	public static final double POST_POP_HP = 1.0 + 8.0;

	private Damage() {}

	/** Explosion damage before armor, exposure 1.0 means open air. */
	public static double crystalRaw(double dist, double exposure, double difficultyMul) {
		if (dist >= CRYSTAL_RADIUS) return 0;
		double impact = (1.0 - dist / CRYSTAL_RADIUS) * exposure;
		double dmg = Math.floor((impact * impact + impact) / 2.0 * 7.0 * CRYSTAL_RADIUS + 1.0);
		return dmg * difficultyMul;
	}

	/** Mace smash bonus for the given fall distance (blocks), density level 0 for none. */
	public static double maceBonus(double fall, int density) {
		if (fall < MACE_MIN_FALL) return 0;
		double bonus = 4.0 * Math.min(fall, 3.0)
			+ 2.0 * Math.min(Math.max(fall - 3.0, 0), 5.0)
			+ 1.0 * Math.max(fall - 8.0, 0);
		return bonus + 0.5 * density * fall;
	}

	public static double maceHit(double fall, int density) {
		return MACE_BASE + maceBonus(fall, density);
	}

	/** Enchantment protection factor (EPF) reduction, capped at 20 like vanilla. */
	public static double afterProtection(double dmg, double epf) {
		double capped = Math.max(0, Math.min(20, epf));
		return dmg * (1.0 - capped / 25.0);
	}

	/** Armor and toughness reduction, vanilla formula. */
	public static double afterArmor(double dmg, double armor, double toughness) {
		double points = Math.min(20.0, Math.max(armor / 5.0, armor - dmg / (2.0 + toughness / 4.0)));
		return dmg * (1.0 - points / 25.0);
	}
}
