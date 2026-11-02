package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.DrawMode;
import meteordevelopment.meteorclient.renderer.Mesh;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShaderMesh;
import meteordevelopment.meteorclient.renderer.Shaders;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.EndGatewayBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3d;
import xaero.hud.minimap.waypoint.WaypointColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ESP / tracers / nametags for End Gateway portals. Dest comes from
 * {@code exit_portal} in the block-entity NBT (no public getter). Box only the
 * gateway block — never the bedrock cage.
 */
public class GatewayESP extends Module {
	public enum LabelAnchor {
		Top,
		Center
	}

	private static final double DIRECTION_LENGTH = 3.0;
	private static final double ARROW_TIP_LENGTH = 0.45;
	private static final double ARROW_BASE_RADIUS = 0.14;
	private static final int RESCAN_IDLE_TICKS = 200;
	private static final int REFRESH_NBT_TICKS = 600;

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgRender = settings.createGroup("Render");

	private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
		.name("color")
		.description("Shared color for ESP, tracers, direction vector, nametag text, and label border.")
		.defaultValue(new SettingColor(180, 80, 255, 255))
		.build()
	);

	private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
		.name("shape-mode")
		.description("How the gateway block box is drawn.")
		.defaultValue(ShapeMode.Both)
		.build()
	);

	private final Setting<Integer> fillOpacity = sgRender.add(new IntSetting.Builder()
		.name("fill-opacity")
		.description("Opacity of the ESP fill.")
		.defaultValue(50)
		.range(0, 255)
		.sliderMax(255)
		.build()
	);

	private final Setting<Boolean> directionVector = sgRender.add(new BoolSetting.Builder()
		.name("direction-vector")
		.description("3-block arrow from the portal toward exit_portal when known.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
		.name("tracers")
		.description("Line from the screen center to each gateway block.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> fadeToCenter = sgRender.add(new BoolSetting.Builder()
		.name("fade-to-center")
		.description("Fade tracer alpha toward the screen center / crosshair.")
		.defaultValue(true)
		.visible(tracers::get)
		.build()
	);

	private final Setting<Double> thickness = sgRender.add(new DoubleSetting.Builder()
		.name("thickness")
		.description("Parallel-line spread. 1 = single line; higher adds distance-scaled parallel lines.")
		.defaultValue(1.0)
		.min(1.0)
		.max(5.0)
		.sliderRange(1.0, 5.0)
		.visible(tracers::get)
		.build()
	);

	private final Setting<Boolean> hideBehind = sgRender.add(new BoolSetting.Builder()
		.name("hide-behind")
		.description("Hide tracers behind blocks (depth test). Off = see-through like Meteor Tracers.")
		.defaultValue(false)
		.visible(tracers::get)
		.build()
	);

	private final Setting<Integer> maxDistance = sgRender.add(new IntSetting.Builder()
		.name("max-distance")
		.description("Maximum player-to-portal distance for tracers.")
		.defaultValue(256)
		.min(0)
		.sliderMax(256)
		.visible(tracers::get)
		.build()
	);

	private final Setting<Integer> minDestDistance = sgRender.add(new IntSetting.Builder()
		.name("min-dest-distance")
		.description("Hide gateways whose exit_portal is closer than this horizontal (XZ) distance. 0 = off. Useful on 2b2t for long-range bridges.")
		.defaultValue(50000)
		.min(0)
		.max(10_000_000)
		.sliderMax(1_000_000)
		.build()
	);

	private final Setting<Boolean> showUnused = sgRender.add(new BoolSetting.Builder()
		.name("show-unused")
		.description("Still show unused / unknown-dest gateways when min-dest-distance is active.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> tooltips = sgRender.add(new BoolSetting.Builder()
		.name("tooltips")
		.description("World nametag with dest xyz or unused.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> tooltipRange = sgRender.add(new IntSetting.Builder()
		.name("tooltip-range")
		.description("Hide nametags beyond this player-to-portal distance.")
		.defaultValue(96)
		.min(16)
		.max(256)
		.sliderRange(32, 192)
		.visible(tooltips::get)
		.build()
	);

	private final Setting<Double> labelScale = sgRender.add(new DoubleSetting.Builder()
		.name("label-scale")
		.description("In-game size of the gateway coord label.")
		.defaultValue(1.0)
		.min(0.5)
		.max(3.0)
		.sliderRange(0.5, 3.0)
		.visible(tooltips::get)
		.build()
	);

	private final Setting<LabelAnchor> labelAnchor = sgRender.add(new EnumSetting.Builder<LabelAnchor>()
		.name("label-anchor")
		.description("Top sits above the block with clearance; Center sits on the portal midpoint.")
		.defaultValue(LabelAnchor.Center)
		.visible(tooltips::get)
		.build()
	);

	private final Setting<Boolean> saveWaypoints = sgGeneral.add(new BoolSetting.Builder()
		.name("save-waypoints")
		.description("Upsert Xaero pins into the Gateways set (one per XZ column). Off by default.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Boolean> clickToCrawl = sgGeneral.add(new BoolSetting.Builder()
		.name("click-to-crawl")
		.description("On punch or use of an End Gateway, hold sneak so you can crawl into the portal. Off by default.")
		.defaultValue(false)
		.build()
	);

	/** Portal block pos → last-known exit (null = unused / unknown). */
	private final Map<Long, BlockPos> gateways = new HashMap<>();
	/** XZ column key → last written waypoint position (lowest Y pin). */
	private final Map<Long, BlockPos> waypointPins = new HashMap<>();

	private final Color lineColor = new Color();
	private final Color sideColor = new Color();
	private final Color tagColor = new Color();
	private final Color borderColor = new Color();
	private final Color fillPad = new Color(0, 0, 0, 160);
	private final Color tracerNear = new Color();
	private final Color tracerFar = new Color();
	private final Color arrowColor = new Color();
	private final Vector3d tagPos = new Vector3d();

	/** Depth-tested tracer lines (hide-behind). */
	private final Mesh depthLines = new ShaderMesh(Shaders.POS_COLOR, DrawMode.Lines, Mesh.Attrib.Vec3, Mesh.Attrib.Color);

	private int pruneTicker;
	private int rescanTicker;
	private int refreshNbtTicker;
	private int waypointTicker;

	/** When non-null, hold sneak near this portal. */
	private BlockPos crawlAssistAt;
	private int crawlGraceTicks;

	public GatewayESP() {
		super(GreenCorps.CATEGORY, "gateway-esp",
			"ESP End Gateway portals, direction vectors, tracers, and optional Gateways Xaero pins.");
	}

	@Override
	public void onActivate() {
		gateways.clear();
		waypointPins.clear();
		pruneTicker = 0;
		rescanTicker = 0;
		refreshNbtTicker = 0;
		waypointTicker = 0;
		clearCrawlAssist();
		if (thickness.get() < 1.0) thickness.set(1.0);
		scanAllLoadedChunks(true);
		if (saveWaypoints.get()) syncWaypoints();
	}

	@Override
	public void onDeactivate() {
		gateways.clear();
		clearCrawlAssist();
	}

	@EventHandler
	private void onChunkData(ChunkDataEvent event) {
		if (mc.world == null || !isEnd()) return;
		scanChunk(event.chunk(), true);
		if (saveWaypoints.get()) syncWaypoints();
	}

	@EventHandler
	private void onStartBreak(StartBreakingBlockEvent event) {
		if (!clickToCrawl.get() || event.blockPos == null) return;
		armCrawlIfGateway(event.blockPos);
	}

	@EventHandler
	private void onInteractBlock(InteractBlockEvent event) {
		if (!clickToCrawl.get() || event.result == null) return;
		BlockHitResult hit = event.result;
		if (hit.getBlockPos() != null) armCrawlIfGateway(hit.getBlockPos());
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		if (mc.world == null || !isEnd()) {
			gateways.clear();
			clearCrawlAssist();
			return;
		}
		int rescanEvery = gateways.isEmpty() ? 80 : RESCAN_IDLE_TICKS;
		if (++rescanTicker >= rescanEvery) {
			rescanTicker = 0;
			scanAllLoadedChunks(false);
		}
		if (++refreshNbtTicker >= REFRESH_NBT_TICKS) {
			refreshNbtTicker = 0;
			scanAllLoadedChunks(true);
		}
		if (++pruneTicker >= 40) {
			pruneTicker = 0;
			pruneUnloaded();
		}
		if (saveWaypoints.get() && ++waypointTicker >= 100) {
			waypointTicker = 0;
			syncWaypoints();
		}
		tickCrawlAssist();
	}

	@EventHandler
	private void onRender3D(Render3DEvent event) {
		if (mc.player == null || mc.world == null || !isEnd()) return;
		SettingColor c = color.get();
		lineColor.set(c);
		sideColor.set(c);
		sideColor.a = fillOpacity.get();
		arrowColor.set(c.r, c.g, c.b, Math.min(220, Math.max(1, c.a)));

		Vec3d from = RenderUtils.center;
		if (from == null) {
			from = mc.player.getEyePos();
		}

		boolean wantTracers = tracers.get();
		boolean depthTracers = wantTracers && hideBehind.get();
		boolean anyDepth = false;
		if (depthTracers) {
			depthLines.depthTest = true;
			depthLines.begin();
		}

		double maxDist = maxDistance.get();
		double maxDistSq = maxDist * maxDist;
		double thick = Math.max(1.0, thickness.get());

		tracerFar.set(c.r, c.g, c.b, Math.max(1, c.a));
		if (fadeToCenter.get()) {
			int nearA = Math.max(1, c.a / 10);
			tracerNear.set(c.r, c.g, c.b, nearA);
		} else {
			tracerNear.set(tracerFar);
		}

		for (Map.Entry<Long, BlockPos> e : gateways.entrySet()) {
			BlockPos portal = BlockPos.fromLong(e.getKey());
			BlockPos dest = e.getValue();
			if (!passesFilter(portal, dest)) continue;
			if (!EntityUtils.isInRenderDistance(portal)) continue;

			event.renderer.box(portal, sideColor, lineColor, shapeMode.get(), 0);

			double x0 = portal.getX() + 0.5;
			double y0 = portal.getY() + 0.5;
			double z0 = portal.getZ() + 0.5;

			if (directionVector.get() && dest != null) {
				drawDirectionVector(event.renderer, x0, y0, z0, dest, arrowColor);
			}

			if (!wantTracers) continue;

			double pdx = mc.player.getX() - x0;
			double pdy = mc.player.getY() - y0;
			double pdz = mc.player.getZ() - z0;
			if (pdx * pdx + pdy * pdy + pdz * pdz > maxDistSq) continue;

			if (depthTracers) {
				drawTracerLines(null, from.x, from.y, from.z, x0, y0, z0, thick);
				anyDepth = true;
			} else {
				drawTracerLines(event.renderer, from.x, from.y, from.z, x0, y0, z0, thick);
			}
		}

		if (depthTracers) {
			depthLines.end();
			if (anyDepth) depthLines.render(event.matrices);
		}
	}

	/** Horizontal XZ filter for short jumps; unused portals controlled by {@link #showUnused}. */
	private boolean passesFilter(BlockPos portal, BlockPos dest) {
		int min = minDestDistance.get();
		if (min <= 0) return true;
		if (dest == null) return showUnused.get();
		long dx = (long) dest.getX() - portal.getX();
		long dz = (long) dest.getZ() - portal.getZ();
		double horiz = Math.sqrt((double) dx * dx + (double) dz * dz);
		return horiz >= min;
	}

	private void drawDirectionVector(Renderer3D renderer, double x0, double y0, double z0,
	                                 BlockPos dest, Color tipColor) {
		double dx = dest.getX() + 0.5 - x0;
		double dy = dest.getY() + 0.5 - y0;
		double dz = dest.getZ() + 0.5 - z0;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6) return;

		double inv = 1.0 / len;
		dx *= inv;
		dy *= inv;
		dz *= inv;

		double tipLen = Math.min(ARROW_TIP_LENGTH, DIRECTION_LENGTH * 0.5);
		double shaftLen = DIRECTION_LENGTH - tipLen;

		double bx = x0 + dx * shaftLen;
		double by = y0 + dy * shaftLen;
		double bz = z0 + dz * shaftLen;
		double tx = x0 + dx * DIRECTION_LENGTH;
		double ty = y0 + dy * DIRECTION_LENGTH;
		double tz = z0 + dz * DIRECTION_LENGTH;

		renderer.line(x0, y0, z0, bx, by, bz, tipColor);

		double ux = 0, uy = 1, uz = 0;
		if (Math.abs(dy) > 0.9) {
			ux = 1;
			uy = 0;
			uz = 0;
		}
		double rx = dy * uz - dz * uy;
		double ry = dz * ux - dx * uz;
		double rz = dx * uy - dy * ux;
		double rLen = Math.sqrt(rx * rx + ry * ry + rz * rz);
		if (rLen < 1e-6) return;
		rx = rx / rLen * ARROW_BASE_RADIUS;
		ry = ry / rLen * ARROW_BASE_RADIUS;
		rz = rz / rLen * ARROW_BASE_RADIUS;
		double fx = dy * rz - dz * ry;
		double fy = dz * rx - dx * rz;
		double fz = dx * ry - dy * rx;

		double[][] base = {
			{bx + rx, by + ry, bz + rz},
			{bx + fx, by + fy, bz + fz},
			{bx - rx, by - ry, bz - rz},
			{bx - fx, by - fy, bz - fz}
		};
		// Wireframe cone — lines only (no filled quads)
		for (int i = 0; i < 4; i++) {
			double[] p = base[i];
			renderer.line(tx, ty, tz, p[0], p[1], p[2], tipColor);
			double[] p2 = base[(i + 1) % 4];
			renderer.line(p[0], p[1], p[2], p2[0], p2[1], p2[2], tipColor);
		}
	}

	/**
	 * Two-color lines from screen center → portal. {@code renderer == null} writes into
	 * {@link #depthLines}. Thickness &gt; 1 adds distance-scaled parallel lines.
	 */
	private void drawTracerLines(Renderer3D renderer, double x1, double y1, double z1,
	                             double x2, double y2, double z2, double thick) {
		tracerLine(renderer, x1, y1, z1, x2, y2, z2);

		if (thick <= 1.0 + 1e-6) return;

		double dx = x2 - x1;
		double dy = y2 - y1;
		double dz = z2 - z1;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6) return;
		dx /= len;
		dy /= len;
		dz /= len;

		Vec3d cam = mc.gameRenderer.getCamera().getPos();
		double mx = (x1 + x2) * 0.5 - cam.x;
		double my = (y1 + y2) * 0.5 - cam.y;
		double mz = (z1 + z2) * 0.5 - cam.z;
		double midDist = Math.sqrt(mx * mx + my * my + mz * mz);
		if (midDist < 8) midDist = 8;
		if (midDist > 64) midDist = 64;

		double sx = dy * mz - dz * my;
		double sy = dz * mx - dx * mz;
		double sz = dx * my - dy * mx;
		double sLen = Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (sLen < 1e-6) {
			sx = -dz;
			sy = 0;
			sz = dx;
			sLen = Math.sqrt(sx * sx + sy * sy + sz * sz);
			if (sLen < 1e-6) return;
		}
		sx /= sLen;
		sy /= sLen;
		sz /= sLen;

		double step = (thick - 1.0) * midDist * 0.002;
		int extras = Math.min(4, (int) Math.floor(thick - 1.0 + 1e-6));
		for (int i = 1; i <= extras; i++) {
			double o = step * i;
			tracerLine(renderer, x1 + sx * o, y1 + sy * o, z1 + sz * o, x2 + sx * o, y2 + sy * o, z2 + sz * o);
			tracerLine(renderer, x1 - sx * o, y1 - sy * o, z1 - sz * o, x2 - sx * o, y2 - sy * o, z2 - sz * o);
		}
	}

	private void tracerLine(Renderer3D renderer, double x1, double y1, double z1,
	                        double x2, double y2, double z2) {
		if (renderer != null) {
			renderer.line(x1, y1, z1, x2, y2, z2, tracerNear, tracerFar);
		} else {
			depthLines.line(
				depthLines.vec3(x1, y1, z1).color(tracerNear).next(),
				depthLines.vec3(x2, y2, z2).color(tracerFar).next()
			);
		}
	}

	@EventHandler
	private void onRender2D(Render2DEvent event) {
		if (!tooltips.get() || mc.player == null || mc.world == null || !isEnd()) return;
		if (mc.options.hudHidden) return;
		double range = tooltipRange.get();
		double rangeSq = range * range;
		SettingColor c = color.get();
		tagColor.set(c.r, c.g, c.b, 255);
		borderColor.set(c.r, c.g, c.b, 255);
		double scale = labelScale.get();
		double yOff = labelAnchor.get() == LabelAnchor.Center ? 0.5 : 1.6;

		for (Map.Entry<Long, BlockPos> e : gateways.entrySet()) {
			BlockPos portal = BlockPos.fromLong(e.getKey());
			BlockPos dest = e.getValue();
			if (!passesFilter(portal, dest)) continue;

			double dx = mc.player.getX() - (portal.getX() + 0.5);
			double dy = mc.player.getY() - (portal.getY() + 0.5);
			double dz = mc.player.getZ() - (portal.getZ() + 0.5);
			if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

			tagPos.set(portal.getX() + 0.5, portal.getY() + yOff, portal.getZ() + 0.5);
			if (!NametagUtils.to2D(tagPos, scale)) continue;

			String text = dest == null
				? "unused"
				: dest.getX() + " " + dest.getY() + " " + dest.getZ();
			renderNametag(text);
		}
	}

	private void renderNametag(String text) {
		TextRenderer tr = TextRenderer.get();
		NametagUtils.begin(tagPos);
		tr.beginBig();
		double tw = tr.getWidth(text);
		double th = tr.getHeight();
		double padX = 3;
		double padY = 2;
		double boxW = tw + padX * 2;
		double boxH = th + padY * 2;
		double boxX = -boxW / 2.0;
		double boxY = -boxH / 2.0;
		double textX = -tw / 2.0;
		double textY = -th / 2.0;

		Renderer2D.COLOR.begin();
		Renderer2D.COLOR.quad(boxX, boxY, boxW, boxH, fillPad);
		double b = 1.0;
		Renderer2D.COLOR.quad(boxX, boxY, boxW, b, borderColor);
		Renderer2D.COLOR.quad(boxX, boxY + boxH - b, boxW, b, borderColor);
		Renderer2D.COLOR.quad(boxX, boxY, b, boxH, borderColor);
		Renderer2D.COLOR.quad(boxX + boxW - b, boxY, b, boxH, borderColor);
		Renderer2D.COLOR.render(null);

		tr.render(text, textX, textY, tagColor, true);
		tr.end();
		NametagUtils.end();
	}

	private void armCrawlIfGateway(BlockPos pos) {
		if (mc.world == null || pos == null) return;
		if (mc.world.getBlockState(pos).getBlock() != Blocks.END_GATEWAY
			&& !gateways.containsKey(pos.asLong())) {
			return;
		}
		crawlAssistAt = pos.toImmutable();
		crawlGraceTicks = 0;
	}

	private void tickCrawlAssist() {
		if (!clickToCrawl.get() || crawlAssistAt == null || mc.player == null) {
			if (crawlAssistAt != null) clearCrawlAssist();
			return;
		}
		double dx = mc.player.getX() - (crawlAssistAt.getX() + 0.5);
		double dy = mc.player.getY() - (crawlAssistAt.getY() + 0.5);
		double dz = mc.player.getZ() - (crawlAssistAt.getZ() + 0.5);
		if (dx * dx + dy * dy + dz * dz > 36.0) {
			clearCrawlAssist();
			return;
		}
		if (++crawlGraceTicks > 200) {
			clearCrawlAssist();
			return;
		}
		mc.options.sneakKey.setPressed(true);
	}

	private void clearCrawlAssist() {
		boolean wasArmed = crawlAssistAt != null;
		crawlAssistAt = null;
		crawlGraceTicks = 0;
		if (wasArmed && mc != null && mc.options != null) {
			mc.options.sneakKey.setPressed(false);
		}
	}

	private void scanAllLoadedChunks(boolean refreshNbt) {
		if (mc.world == null || !isEnd()) return;
		for (WorldChunk chunk : getLoadedChunks()) {
			scanChunk(chunk, refreshNbt);
		}
	}

	/**
	 * @param refreshNbt when false, only {@code readExit} for newly seen portal keys.
	 */
	private void scanChunk(WorldChunk chunk, boolean refreshNbt) {
		if (chunk == null || mc.world == null) return;
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (!(be instanceof EndGatewayBlockEntity gateway)) continue;
			if (gateway.getCachedState().getBlock() != Blocks.END_GATEWAY) continue;
			long key = gateway.getPos().asLong();
			if (!refreshNbt && gateways.containsKey(key)) continue;
			gateways.put(key, readExit(gateway));
		}
	}

	private BlockPos readExit(EndGatewayBlockEntity gateway) {
		try {
			NbtCompound nbt = gateway.createComponentlessNbt(mc.world.getRegistryManager());
			Optional<BlockPos> exit = NbtHelper.toBlockPos(nbt, "exit_portal");
			return exit.orElse(null);
		} catch (Throwable t) {
			return null;
		}
	}

	private void pruneUnloaded() {
		if (mc.world == null) return;
		Iterator<Long> it = gateways.keySet().iterator();
		while (it.hasNext()) {
			BlockPos pos = BlockPos.fromLong(it.next());
			ChunkPos cp = new ChunkPos(pos);
			if (!mc.world.isChunkLoaded(cp.x, cp.z)) {
				it.remove();
				continue;
			}
			if (mc.world.getBlockState(pos).getBlock() != Blocks.END_GATEWAY) {
				it.remove();
			}
		}
	}

	/** Group by exact XZ, stable stack numbers, upsert one pin per column. */
	private void syncWaypoints() {
		if (!XaeroWaypoints.isAvailable() || gateways.isEmpty()) return;

		Map<Long, List<BlockPos>> columns = new HashMap<>();
		for (Map.Entry<Long, BlockPos> e : gateways.entrySet()) {
			BlockPos p = BlockPos.fromLong(e.getKey());
			if (!passesFilter(p, e.getValue())) continue;
			long xz = xzKey(p.getX(), p.getZ());
			columns.computeIfAbsent(xz, k -> new ArrayList<>()).add(p);
		}
		if (columns.isEmpty()) return;

		List<Long> ordered = new ArrayList<>(columns.keySet());
		ordered.sort(Comparator
			.comparingInt((Long k) -> (int) (k >> 32))
			.thenComparingInt(k -> k.intValue()));

		WaypointColor pinColor = XaeroWaypoints.nearestWaypointColor(color.get().r, color.get().g, color.get().b);
		int stackIndex = 0;
		for (Long xz : ordered) {
			List<BlockPos> members = columns.get(xz);
			members.sort(Comparator.comparingInt(BlockPos::getY));
			BlockPos pinAt = members.getFirst();
			boolean stack = members.size() > 1;
			if (stack) stackIndex++;

			BlockPos sharedDest = sharedDest(members);
			String title = gatewayTitle(stack, stackIndex, members.size(), sharedDest);
			BlockPos previous = waypointPins.get(xz);
			BlockPos written = XaeroWaypoints.upsertGateway(previous, pinAt, title, pinColor);
			if (written != null) waypointPins.put(xz, written);
		}
	}

	private BlockPos sharedDest(List<BlockPos> members) {
		BlockPos first = null;
		for (BlockPos portal : members) {
			BlockPos dest = gateways.get(portal.asLong());
			if (dest == null) return null;
			if (first == null) first = dest;
			else if (!first.equals(dest)) return null;
		}
		return first;
	}

	static String gatewayTitle(boolean stack, int stackNumber, int count, BlockPos sharedDest) {
		if (!stack) {
			if (sharedDest == null) return "GW (unused)";
			return "GW (" + sharedDest.getX() + " " + sharedDest.getY() + " " + sharedDest.getZ() + ")";
		}
		String base = "Gateway Stack #" + stackNumber + " (" + count + "x)";
		if (sharedDest == null) return base;
		return base + " (" + sharedDest.getX() + " " + sharedDest.getY() + " " + sharedDest.getZ() + ")";
	}

	private static long xzKey(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	private boolean isEnd() {
		return mc.world != null && mc.world.getRegistryKey() == World.END;
	}

	private List<WorldChunk> getLoadedChunks() {
		List<WorldChunk> out = new ArrayList<>();
		if (mc.world == null || mc.player == null) return out;
		int rd = mc.options.getViewDistance().getValue();
		ChunkPos center = mc.player.getChunkPos();
		for (int x = center.x - rd; x <= center.x + rd; x++) {
			for (int z = center.z - rd; z <= center.z + rd; z++) {
				if (!mc.world.isChunkLoaded(x, z)) continue;
				WorldChunk chunk = mc.world.getChunk(x, z);
				if (chunk != null) out.add(chunk);
			}
		}
		return out;
	}

	@Override
	public String getInfoString() {
		return String.valueOf(gateways.size());
	}
}
