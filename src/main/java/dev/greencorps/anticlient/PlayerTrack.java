package dev.greencorps.anticlient;

/**
 * Rolling history of one other player: motion, remembered equipment, and the
 * airborne arc (peak, fall distance). Pure data so it can be unit tested.
 */
public final class PlayerTrack {
	private static final int CAP = 24;
	private static final double GRAVITY = 0.08;
	private static final double VERTICAL_DRAG = 0.98;
	private static final double AIR_HORIZONTAL_DRAG = 0.91;
	private static final double DESCENT_EPS = -0.03;
	private static final int FAR_PAST = -100_000;

	public final int id;
	public String name = "";

	private final Observation[] ring = new Observation[CAP];
	private int head;
	private int count;

	private int lastMaceTick = FAR_PAST;
	private int lastCrystalTick = FAR_PAST;
	private int lastWindTick = FAR_PAST;
	private int chestSwapTick = FAR_PAST;
	private ChestKind prevChest = ChestKind.NONE;

	private boolean airborne;
	private int airborneSince = FAR_PAST;
	private double peakY;
	private double airbornePeakY;
	private int lastSeenTick;

	public PlayerTrack(int id) {
		this.id = id;
	}

	public void update(Observation o) {
		lastSeenTick = o.tick();
		ring[head] = o;
		head = (head + 1) % CAP;
		if (count < CAP) count++;

		if (o.mainHand() == HeldKind.MACE || o.offHand() == HeldKind.MACE) lastMaceTick = o.tick();
		if (o.mainHand() == HeldKind.CRYSTAL || o.offHand() == HeldKind.CRYSTAL) lastCrystalTick = o.tick();
		if (o.mainHand() == HeldKind.WIND_CHARGE || o.offHand() == HeldKind.WIND_CHARGE) lastWindTick = o.tick();

		boolean swapped = (prevChest == ChestKind.ELYTRA && o.chest() == ChestKind.ARMOR)
			|| (prevChest == ChestKind.ARMOR && o.chest() == ChestKind.ELYTRA);
		if (swapped) chestSwapTick = o.tick();
		prevChest = o.chest();

		if (o.onGround()) {
			airborne = false;
			airborneSince = FAR_PAST;
			peakY = o.y();
		} else if (!airborne) {
			airborne = true;
			airborneSince = o.tick();
			peakY = o.y();
		} else if (o.y() > peakY) {
			peakY = o.y();
		}
		airbornePeakY = peakY;
	}

	public Observation latest() {
		return count == 0 ? null : ring[(head - 1 + CAP) % CAP];
	}

	private Observation back(int n) {
		if (n >= count) return null;
		return ring[(head - 1 - n + CAP * 2) % CAP];
	}

	public int lastSeenTick() {
		return lastSeenTick;
	}

	public int samples() {
		return count;
	}

	public boolean airborne() {
		return airborne;
	}

	public int airborneTicks(int nowTick) {
		return airborne ? nowTick - airborneSince : 0;
	}

	public double peakY() {
		return airbornePeakY;
	}

	/** Velocity in blocks per tick, averaged over the last two steps. */
	public double[] velocity() {
		Observation a = back(0);
		Observation b = back(1);
		if (a == null || b == null || a.tick() == b.tick()) return new double[] {0, 0, 0};
		double dt = a.tick() - b.tick();
		double vx = (a.x() - b.x()) / dt;
		double vy = (a.y() - b.y()) / dt;
		double vz = (a.z() - b.z()) / dt;
		Observation c = back(2);
		if (c != null && b.tick() != c.tick()) {
			double dt2 = b.tick() - c.tick();
			vx = (vx + (b.x() - c.x()) / dt2) / 2.0;
			vy = (vy + (b.y() - c.y()) / dt2) / 2.0;
			vz = (vz + (b.z() - c.z()) / dt2) / 2.0;
		}
		return new double[] {vx, vy, vz};
	}

	public boolean descending() {
		return airborne && velocity()[1] < DESCENT_EPS;
	}

	/** Blocks fallen from the peak of the current airborne arc, 0 when grounded. */
	public double fallDistance() {
		Observation o = latest();
		if (o == null || !airborne) return 0;
		return Math.max(0, airbornePeakY - o.y());
	}

	public boolean maceRecent(int nowTick, int memory) {
		return nowTick - lastMaceTick <= memory;
	}

	public boolean maceInHand() {
		Observation o = latest();
		return o != null && (o.mainHand() == HeldKind.MACE || o.offHand() == HeldKind.MACE);
	}

	public boolean crystalInHand() {
		Observation o = latest();
		return o != null && (o.mainHand() == HeldKind.CRYSTAL || o.offHand() == HeldKind.CRYSTAL);
	}

	public boolean windRecent(int nowTick, int memory) {
		return nowTick - lastWindTick <= memory;
	}

	public boolean crystalRecent(int nowTick, int memory) {
		return nowTick - lastCrystalTick <= memory;
	}

	public boolean chestSwapRecent(int nowTick, int memory) {
		return nowTick - chestSwapTick <= memory;
	}

	/**
	 * Ballistic projection of the body position {@code ticks} ahead, using vanilla
	 * gravity and air drag. Stops at the ground. Index 0 is the current position.
	 * Elytra flight is not modeled; callers must treat gliding tracks as low confidence.
	 */
	public double[][] project(int ticks) {
		double[][] out = new double[ticks + 1][3];
		Observation o = latest();
		if (o == null) return out;
		double[] v = velocity();
		double x = o.x(), y = o.y(), z = o.z();
		double vx = v[0], vy = v[1], vz = v[2];
		boolean grounded = o.onGround();
		for (int k = 0; k <= ticks; k++) {
			out[k][0] = x;
			out[k][1] = y;
			out[k][2] = z;
			if (grounded) continue;
			x += vx;
			y += vy;
			z += vz;
			vy = (vy - GRAVITY) * VERTICAL_DRAG;
			vx *= AIR_HORIZONTAL_DRAG;
			vz *= AIR_HORIZONTAL_DRAG;
			if (y <= o.groundY()) {
				y = o.groundY();
				grounded = true;
			}
		}
		return out;
	}

	/** Ticks until the projected arc touches ground, or -1 when beyond the horizon. */
	public int ticksToGround(int horizon) {
		Observation o = latest();
		if (o == null || o.onGround()) return 0;
		double[][] p = project(horizon);
		for (int k = 0; k <= horizon; k++) {
			if (p[k][1] <= o.groundY() + 1e-6) return k;
		}
		return -1;
	}
}
