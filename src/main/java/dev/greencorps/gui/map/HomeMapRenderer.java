package dev.greencorps.gui.map;

import dev.greencorps.backup.Home;
import dev.greencorps.backup.StoreHealth;
import dev.greencorps.backup.UnnaturalScore;
import dev.greencorps.modules.BaseBackup;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Draws home square outlines + hover unnatural tints on the Xaero world map. */
public final class HomeMapRenderer {
	private static final long CARD_TTL_MS = 2_000L;
	private static final MapOverlayCache OVERLAY = new MapOverlayCache();

	private static Home hovered;
	private static GcMapView lastView;

	private record CardPayload(double coverage, int snapshots, long storeBytes, StoreHealth health,
							   int degraded, boolean rehearsalPass, long rehearsalAt, long atMs) {}

	private static final Map<String, CardPayload> CARD_CACHE = new HashMap<>();
	private static int homeGen;

	private HomeMapRenderer() {}

	public static void invalidateOverlay() {
		OVERLAY.invalidate();
		homeGen++;
	}

	public static void renderMapLayers(Screen screen, DrawContext graphics, int mouseX, int mouseY) {
		renderMapLayers(screen, graphics, mouseX, mouseY, null);
	}

	public static void renderMapLayers(Screen screen, DrawContext graphics, int mouseX, int mouseY, GcMapView view) {
		BaseBackup mod = BaseBackup.get();
		if (mod == null || !mod.isActive() || mod.homeStore() == null) return;
		if (view == null) view = GcMapView.from(screen);
		if (view == null) return;
		lastView = view;

		UnnaturalCache.beginFrame();

		MatrixStack matrices = graphics.getMatrices();
		matrices.push();
		matrices.peek().getPositionMatrix().identity();

		try {
			String dim = view.dimensionId;
			List<Home> homes = new ArrayList<>();
			for (Home h : mod.homeStore().homes()) {
				if (h.dimension == null || h.dimension.equals(dim)) homes.add(h);
			}

			if (!OVERLAY.matches(view.cameraX, view.cameraZ, view.pxPerBlockGui,
				view.screenW, view.screenH, homeGen, dim, "")) {
				List<MapOverlayCache.Rect> rects = projectRects(view, homes);
				OVERLAY.store(view.cameraX, view.cameraZ, view.pxPerBlockGui,
					view.screenW, view.screenH, homeGen, dim, "", rects, List.of());
			}

			hovered = pickHovered(OVERLAY.rects(), homes, mouseX, mouseY);

			int outline = GcDraw.withAlpha(0xFF28B450, (float) mod.mapCircleOpacity());
			for (MapOverlayCache.Rect r : OVERLAY.rects()) {
				GcDraw.rectOutline(graphics, r.x0(), r.z0(), r.x1(), r.z1(), outline, 2);
			}

			// Unnatural: rebuild from cache each hover frame (≤8 new scores / frame).
			if (hovered != null && mod.drawUnnaturalFor(hovered) && view.pxPerBlockGui * 16 >= 2) {
				for (MapOverlayCache.UnnaturalQuad q : buildUnnaturalQuads(view, mod, hovered)) {
					GcDraw.fillRect(graphics, q.x0(), q.y0(), q.x1(), q.y1(), q.color());
				}
			}
		} finally {
			matrices.pop();
		}
	}

	public static void renderCard(Screen screen, DrawContext graphics, int mouseX, int mouseY) {
		renderCard(screen, graphics, mouseX, mouseY, null);
	}

	public static void renderCard(Screen screen, DrawContext graphics, int mouseX, int mouseY, GcMapView view) {
		if (hovered == null) return;
		BaseBackup mod = BaseBackup.get();
		if (mod == null || mod.engine() == null) return;
		if (view == null) view = lastView != null ? lastView : GcMapView.from(screen);
		if (view == null) return;

		CardPayload card = cardFor(mod, hovered);
		int ax = (int) Math.round(view.screenX(hovered.centerBlockX()));
		int ay = (int) Math.round(view.screenZ(hovered.centerBlockZ()));
		GcHomeCard.draw(graphics, MinecraftClient.getInstance().textRenderer, hovered,
			card.coverage(), card.snapshots(), card.storeBytes(),
			card.health(), card.degraded(),
			card.rehearsalPass(), card.rehearsalAt(),
			ax, ay, view.screenW, view.screenH);
	}

	private static CardPayload cardFor(BaseBackup mod, Home h) {
		long now = System.currentTimeMillis();
		CardPayload cached = CARD_CACHE.get(h.id);
		if (cached != null && now - cached.atMs <= CARD_TTL_MS) return cached;
		CardPayload fresh = new CardPayload(
			mod.coverage(h),
			mod.snapshotCount(h),
			mod.engine().estimateStoreSize(),
			mod.engine().health(),
			mod.engine().degradedSections(),
			mod.engine().lastRehearsalPass(),
			mod.engine().lastRehearsalAt(),
			now
		);
		CARD_CACHE.put(h.id, fresh);
		return fresh;
	}

	private static List<MapOverlayCache.Rect> projectRects(GcMapView view, List<Home> homes) {
		List<MapOverlayCache.Rect> out = new ArrayList<>(homes.size());
		for (Home h : homes) {
			double wx0 = h.minBlockX();
			double wz0 = h.minBlockZ();
			double wx1 = h.maxBlockX();
			double wz1 = h.maxBlockZ();
			// Viewport cull in world space (cheap reject before project).
			double margin = 16;
			double worldLeft = view.screenToWorldX(-margin);
			double worldRight = view.screenToWorldX(view.screenW + margin);
			double worldTop = view.screenToWorldZ(-margin);
			double worldBottom = view.screenToWorldZ(view.screenH + margin);
			double minWX = Math.min(worldLeft, worldRight);
			double maxWX = Math.max(worldLeft, worldRight);
			double minWZ = Math.min(worldTop, worldBottom);
			double maxWZ = Math.max(worldTop, worldBottom);
			if (wx1 < minWX || wx0 > maxWX || wz1 < minWZ || wz0 > maxWZ) continue;

			int x0 = (int) Math.round(view.screenX(wx0));
			int z0 = (int) Math.round(view.screenZ(wz0));
			int x1 = (int) Math.round(view.screenX(wx1));
			int z1 = (int) Math.round(view.screenZ(wz1));
			out.add(new MapOverlayCache.Rect(h.id, x0, z0, x1, z1));
		}
		return out;
	}

	private static Home pickHovered(List<MapOverlayCache.Rect> rects, List<Home> homes, int mouseX, int mouseY) {
		Home best = null;
		double bestDist = Double.MAX_VALUE;
		Map<String, Home> byId = new HashMap<>();
		for (Home h : homes) byId.put(h.id, h);
		for (MapOverlayCache.Rect r : rects) {
			if (!r.contains(mouseX, mouseY)) continue;
			Home h = byId.get(r.homeId());
			if (h == null) continue;
			double cx = (r.left() + r.right()) / 2.0;
			double cy = (r.top() + r.bottom()) / 2.0;
			double dist = Math.hypot(mouseX - cx, mouseY - cy);
			if (dist < bestDist) {
				bestDist = dist;
				best = h;
			}
		}
		return best;
	}

	private static List<MapOverlayCache.UnnaturalQuad> buildUnnaturalQuads(GcMapView view, BaseBackup mod, Home h) {
		int base = mod.unnaturalColor().getPacked();
		String dim = h.dimension != null ? h.dimension : view.dimensionId;
		// Row-major run-length merge of same-color adjacent tiles.
		List<MapOverlayCache.UnnaturalQuad> quads = new ArrayList<>();
		for (int dz = -h.radiusChunks; dz <= h.radiusChunks; dz++) {
			int runStart = Integer.MIN_VALUE;
			int runColor = 0;
			int runCz = h.centerChunkZ + dz;
			for (int dx = -h.radiusChunks; dx <= h.radiusChunks + 1; dx++) {
				boolean end = dx > h.radiusChunks;
				int color = 0;
				if (!end) {
					int cx = h.centerChunkX + dx;
					int hits = UnnaturalCache.getOrEnqueue(dim, cx, runCz);
					if (hits > 0) color = UnnaturalScore.boostAlpha(base, hits);
				}
				if (color != 0 && color == runColor && runStart != Integer.MIN_VALUE) {
					continue; // extend run
				}
				if (runStart != Integer.MIN_VALUE && runColor != 0) {
					int cx0 = runStart;
					int cx1 = h.centerChunkX + dx; // exclusive end
					int x0 = (int) Math.round(view.screenX(cx0 * 16));
					int z0 = (int) Math.round(view.screenZ(runCz * 16));
					int x1 = (int) Math.round(view.screenX(cx1 * 16));
					int z1 = (int) Math.round(view.screenZ((runCz + 1) * 16));
					quads.add(new MapOverlayCache.UnnaturalQuad(
						Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), runColor));
				}
				if (end || color == 0) {
					runStart = Integer.MIN_VALUE;
					runColor = 0;
				} else {
					runStart = h.centerChunkX + dx;
					runColor = color;
				}
			}
		}
		return quads;
	}

	public static Home hovered() {
		return hovered;
	}
}
