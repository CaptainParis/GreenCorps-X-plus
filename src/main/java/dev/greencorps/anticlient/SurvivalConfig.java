package dev.greencorps.anticlient;

public final class SurvivalConfig {
	/** Ticks we need to survive to break the engagement (leave, kill, or regroup). */
	public int neutralizeTicks = 60;
	public int okMarginTicks = 40;
	public int defaultRefillTicks = 8;
	public int maceIntervalTicks = 30;
	public int crystalIntervalTicks = 4;
	public double safetyFactor = 1.0;
	public double lowHp = 6.0;
}
