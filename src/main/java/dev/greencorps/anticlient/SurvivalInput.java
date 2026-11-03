package dev.greencorps.anticlient;

/** Everything the hopelessness test needs, gathered once per tick. */
public record SurvivalInput(
	double hp,
	double absorption,
	boolean offhandTotem,
	int inventoryTotems,
	boolean autoTotemOn,
	int refillTicks,
	int rttTicks,
	ThreatLevel level,
	Archetype archetype,
	int etaTicks,
	double burstDamage
) {}
