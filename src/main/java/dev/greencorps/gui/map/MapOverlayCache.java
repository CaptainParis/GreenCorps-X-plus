package dev.greencorps.gui.map;

import java.util.ArrayList;
import java.util.List;

/** Cached projected home rectangles for a static map view. */
public final class MapOverlayCache {
	public record Rect(String homeId, int x0, int z0, int x1, int z1) {
		public int left() { return Math.min(x0, x1); }
		public int right() { return Math.max(x0, x1); }
		public int top() { return Math.min(z0, z1); }
		public int bottom() { return Math.max(z0, z1); }
		public boolean contains(int mx, int my) {
			return mx >= left() && mx <= right() && my >= top() && my <= bottom();
		}
	}

	public record UnnaturalQuad(int x0, int y0, int x1, int y1, int color) {}

	private double cameraX = Double.NaN;
	private double cameraZ = Double.NaN;
	private double pxPerBlockGui = Double.NaN;
	private int screenW = -1;
	private int screenH = -1;
	private int homeGen = -1;
	private String dimId = "";
	private String hoveredId = "";
	private final List<Rect> rects = new ArrayList<>();
	private final List<UnnaturalQuad> unnaturalQuads = new ArrayList<>();

	public boolean matches(double camX, double camZ, double px, int w, int h, int gen, String dim, String hover) {
		return camX == cameraX && camZ == cameraZ && px == pxPerBlockGui
			&& w == screenW && h == screenH && gen == homeGen
			&& dimId.equals(dim == null ? "" : dim)
			&& hoveredId.equals(hover == null ? "" : hover);
	}

	public void store(double camX, double camZ, double px, int w, int h, int gen, String dim, String hover,
					  List<Rect> newRects, List<UnnaturalQuad> newQuads) {
		cameraX = camX;
		cameraZ = camZ;
		pxPerBlockGui = px;
		screenW = w;
		screenH = h;
		homeGen = gen;
		dimId = dim == null ? "" : dim;
		hoveredId = hover == null ? "" : hover;
		rects.clear();
		rects.addAll(newRects);
		unnaturalQuads.clear();
		unnaturalQuads.addAll(newQuads);
	}

	public List<Rect> rects() { return rects; }
	public List<UnnaturalQuad> unnaturalQuads() { return unnaturalQuads; }

	public void invalidate() {
		cameraX = Double.NaN;
		homeGen = -1;
		rects.clear();
		unnaturalQuads.clear();
	}
}
