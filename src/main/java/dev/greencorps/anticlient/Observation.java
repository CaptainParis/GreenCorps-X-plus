package dev.greencorps.anticlient;

/** One client-tick look at another player. Absolute world coordinates; never written to disk. */
public record Observation(
	int tick,
	double x, double y, double z,
	boolean onGround,
	double groundY,
	boolean gliding,
	HeldKind mainHand,
	HeldKind offHand,
	ChestKind chest,
	int pingMs
) {}
