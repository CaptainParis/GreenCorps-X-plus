package dev.greencorps.anticlient;

/** Tunables for {@link ThreatModel}. Defaults are the first guess; telemetry exists to correct them. */
public final class ThreatConfig {
	public double minFall = 3.0;
	public double reach = 3.6;
	public int horizonTicks = 30;
	public int commitWindowTicks = 14;
	public int equipmentMemoryTicks = 60;
	public int chestSirenTicks = 40;
	public int watchScore = 30;
	public int armedScore = 55;
	public int commitScore = 75;
	public double crystalNear = 12.0;
	public double crystalCommitDamage = 4.0;
	public double difficultyMul = 1.5;
	public double assumedArmor = 20.0;
	public double assumedToughness = 12.0;
	public double assumedEpf = 16.0;
	public int assumedDensity = 0;
	public int maxCrystalsCounted = 3;
}
