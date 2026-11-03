package dev.greencorps.anticlient;

/** One airborne dive being measured: where and when we predicted it would land. */
public final class DiveRecord {
	public final int startTick;
	public final int predictedLandTick;
	public final double predictedX;
	public final double predictedZ;
	public final double fallAtStart;
	public final boolean mace;

	public DiveRecord(int startTick, int predictedLandTick, double predictedX, double predictedZ,
					  double fallAtStart, boolean mace) {
		this.startTick = startTick;
		this.predictedLandTick = predictedLandTick;
		this.predictedX = predictedX;
		this.predictedZ = predictedZ;
		this.fallAtStart = fallAtStart;
		this.mace = mace;
	}
}
