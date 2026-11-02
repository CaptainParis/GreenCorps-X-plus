package dev.greencorps.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.greencorps.GreenCorps;
import dev.greencorps.util.MapPreviewNames;
import dev.greencorps.util.MapPreviewStore;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Draws cached mapart in inventory and container slots (including nested tooltips).
 * Learn swaps only into empty hotbar (order-safe) and defers container close until restore.
 */
public class MapSlotPreview extends Module {
	public enum LearnMode {
		Off("Off"),
		Hotbar("Hotbar"),
		HotbarAndOffhand("Hotbar+Offhand");

		private final String title;

		LearnMode(String title) {
			this.title = title;
		}

		@Override
		public String toString() {
			return title;
		}
	}

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgConflicts = settings.createGroup("Conflicts");

	private final Setting<Boolean> overlayBorder = sgGeneral.add(new BoolSetting.Builder()
		.name("overlay-border")
		.description("Draw slot preview at 18×18 centered on the item rect (over slot chrome). Off = exact 16×16.")
		.defaultValue(true)
		.build()
	);

	private final Setting<LearnMode> learn = sgGeneral.add(new EnumSetting.Builder<LearnMode>()
		.name("learn")
		.description("Fetch missing map pixels via empty-hotbar SWAP (order-safe) or select-flash. Offhand only if empty.")
		.defaultValue(LearnMode.HotbarAndOffhand)
		.build()
	);

	private final Setting<Boolean> overlappingPreviews = sgConflicts.add(new BoolSetting.Builder()
		.name("overlapping-previews")
		.description("Turn these off for best results: Meteor Better Tooltips → Maps; Rusher ExtraTooltips → Maps → Slot. Map hover lives on Tooltip+.")
		.defaultValue(true)
		.visible(this::hasPreviewConflicts)
		.build()
	);

	private final MapPreviewStore store = MapPreviewStore.get();

	private final Queue<LearnJob> queue = new ArrayDeque<>();
	private final Set<String> queuedKeys = new LinkedHashSet<>();
	private final Set<String> failedOnce = new LinkedHashSet<>();
	/** Maps swapped into empty hotbar, awaiting select/restore to origin slotId. */
	private final List<LearnJob> staged = new ArrayList<>();
	private final Set<Integer> reservedHotbar = new HashSet<>();

	private LearnPhase phase = LearnPhase.IDLE;
	private LearnJob active;
	private int savedSelectedSlot = -1;
	private int holdTicks;
	private int waitVisibleTicks;
	private boolean warnedInventoryEssentials;
	private final Map<String, Boolean> pausedPrior = new LinkedHashMap<>();
	private String lastServerKey = "";
	private final Map<Integer, ItemStack> lastWatchedStacks = new HashMap<>();
	private ItemStack lastCursor = ItemStack.EMPTY;
	private boolean watchedChangedThisTick;
	private boolean pixelsReadyForActive;
	private boolean clickedThisTick;
	private long rusherConflictCacheMs;
	private boolean rusherConflictCached;
	private boolean pendingClose;
	private boolean allowClose;
	private boolean stopStaging;

	private enum LearnPhase {
		IDLE,
		STAGE_SWAP,
		STAGE_WAIT,
		FLASH_SELECT,
		FLASH_HOLD,
		FLASH_RESTORE,
		SWAP_OUT,
		WAIT_OUT,
		HOLD,
		SWAP_BACK,
		WAIT_BACK
	}

	private enum LearnPath {
		HOTBAR_FLASH,
		HOTBAR_SWAP,
		OFFHAND
	}

	private static final class LearnJob {
		final String key;
		final int mapId;
		final String name;
		LearnPath path;
		int originSlotId = -1;
		int hotbarIndex = -1;
		boolean didHotbarSwap;
		boolean didOffhandSwap;

		LearnJob(String key, int mapId, String name, LearnPath path) {
			this.key = key;
			this.mapId = mapId;
			this.name = name;
			this.path = path;
		}
	}

	public MapSlotPreview() {
		super(GreenCorps.CATEGORY, "map-slot-preview",
			"Shows mapart in inventory and container slots. Saves pieces by pixel content under config/greencorps/map-previews.");
	}

	public static MapSlotPreview get() {
		return Modules.get().get(MapSlotPreview.class);
	}

	/**
	 * @return true to cancel the screen close (we still have maps out of place).
	 */
	public boolean onContainerCloseAttempt() {
		if (!isActive() || allowClose) return false;
		if (!hasOutstandingSwaps()) return false;
		pendingClose = true;
		stopStaging = true;
		queue.clear();
		queuedKeys.clear();
		requestImmediateRestore();
		return true;
	}

	/** Screen was removed anyway (server / allowClose). Best-effort restore if handler still valid. */
	public void onContainerForceClosed() {
		if (allowClose) {
			clearLearnState();
			return;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null && mc.player != null && hasOutstandingSwaps()) {
			bestEffortRestoreNow(mc);
		}
		clearLearnState();
		pendingClose = false;
		stopStaging = false;
	}

	public boolean hasOutstandingSwaps() {
		if (!staged.isEmpty()) return true;
		if (active == null) return false;
		return active.didHotbarSwap || active.didOffhandSwap
			|| phase == LearnPhase.STAGE_SWAP || phase == LearnPhase.STAGE_WAIT
			|| phase == LearnPhase.FLASH_SELECT || phase == LearnPhase.FLASH_HOLD
			|| phase == LearnPhase.FLASH_RESTORE
			|| phase == LearnPhase.SWAP_OUT || phase == LearnPhase.WAIT_OUT
			|| phase == LearnPhase.HOLD || phase == LearnPhase.SWAP_BACK || phase == LearnPhase.WAIT_BACK;
	}

	private void requestImmediateRestore() {
		if (phase == LearnPhase.FLASH_HOLD || phase == LearnPhase.STAGE_WAIT || phase == LearnPhase.HOLD) {
			if (active != null && active.didHotbarSwap) phase = LearnPhase.FLASH_RESTORE;
			else if (active != null && active.didOffhandSwap) phase = LearnPhase.SWAP_BACK;
		} else if (phase == LearnPhase.STAGE_SWAP || phase == LearnPhase.FLASH_SELECT) {
			if (active != null && active.didHotbarSwap) phase = LearnPhase.FLASH_RESTORE;
		}
	}

	/** Vanilla {@code DrawContext.drawItem} draws flat item models at +150 relative to the caller. */
	private static final float ITEM_LAYER_Z = 150f;

	/** Called from DrawContext mixin. Returns true if a preview was drawn. */
	public static boolean tryDrawPreview(DrawContext context, ItemStack stack, int x, int y) {
		MapSlotPreview mod = get();
		if (mod == null || !mod.isActive()) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return false;
		if (!stack.isOf(Items.FILLED_MAP)) return false;

		Identifier tex = mod.resolveTexture(stack);
		if (tex == null) return false;

		// Same depth as vanilla's flat item sprite (drawItem translates +150 for non-3D models), so the
		// stack count / durability bar at +200 stays on top and tooltips (z 400) are never covered.
		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(0, 0, ITEM_LAYER_Z);
		if (mod.overlayBorder.get()) {
			context.drawTexture(RenderLayer::getGuiTextured, tex, x - 1, y - 1, 0f, 0f, 18, 18, 128, 128, 128, 128);
		} else {
			context.drawTexture(RenderLayer::getGuiTextured, tex, x, y, 0f, 0f, 16, 16, 128, 128, 128, 128);
		}
		matrices.pop();
		return true;
	}

	private Identifier resolveTexture(ItemStack stack) {
		MinecraftClient mc = MinecraftClient.getInstance();
		String name = MapPreviewNames.keyOf(stack);
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		Integer mapId = mid == null ? null : mid.id();
		MapState state = (mc != null && mc.world != null) ? FilledMapItem.getMapState(stack, mc.world) : null;

		if (state != null && state.colors != null && !MapPreviewStore.isBlank(state.colors)) {
			store.put(state.colors, mapId, name);
			if (mapId != null) {
				Identifier t = store.textureForMapId(mapId);
				if (t != null) return t;
			}
			return store.textureForUniqueName(name);
		}

		if (mapId != null) {
			Identifier t = store.textureForMapId(mapId);
			if (t != null) return t;
		}
		return store.textureForUniqueName(name);
	}

	/** Reflection-safe: shareable jars may omit MapDuperPlus. */
	private static boolean isMapDuperCloning() {
		try {
			Module m = Modules.get().get("map-duper-plus");
			if (m == null || !m.isActive()) return false;
			Object r = m.getClass().getMethod("isCloning").invoke(m);
			return Boolean.TRUE.equals(r);
		} catch (Throwable t) {
			return false;
		}
	}

	private boolean hasPreviewConflicts() {
		return isMeteorMapsOn() || isRusherMapsSlotOn();
	}

	private boolean isMeteorMapsOn() {
		try {
			Module bt = Modules.get().get("better-tooltips");
			if (bt == null || !bt.isActive()) return false;
			Setting<?> maps = bt.settings.get("maps");
			return maps != null && Boolean.TRUE.equals(maps.get());
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isRusherMapsSlotOn() {
		long now = System.currentTimeMillis();
		if (now - rusherConflictCacheMs < 1000L) return rusherConflictCached;
		rusherConflictCacheMs = now;
		rusherConflictCached = readRusherMapsSlotFromDisk();
		return rusherConflictCached;
	}

	private static boolean readRusherMapsSlotFromDisk() {
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc == null) return false;
			Path file = mc.runDirectory.toPath().resolve("rusherhack/config/modules/construct.json");
			if (!Files.isRegularFile(file)) return false;
			String raw = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement root = JsonParser.parseString(raw);
			if (!root.isJsonArray()) return false;
			for (JsonElement el : root.getAsJsonArray()) {
				if (!el.isJsonObject()) continue;
				JsonObject mod = el.getAsJsonObject();
				if (!"ExtraTooltips".equals(mod.has("name") ? mod.get("name").getAsString() : "")) continue;
				if (mod.has("toggled") && !mod.get("toggled").getAsBoolean()) return false;
				if (!mod.has("settings") || !mod.get("settings").isJsonArray()) return false;
				for (JsonElement se : mod.getAsJsonArray("settings")) {
					if (!se.isJsonObject()) continue;
					JsonObject s = se.getAsJsonObject();
					if (!"maps".equals(s.has("name") ? s.get("name").getAsString() : "")) continue;
					if (!s.has("value") || !s.get("value").getAsBoolean()) return false;
					if (!s.has("subSettings") || !s.get("subSettings").isJsonArray()) return false;
					for (JsonElement sub : s.getAsJsonArray("subSettings")) {
						if (!sub.isJsonObject()) continue;
						JsonObject ss = sub.getAsJsonObject();
						if ("slot".equals(ss.has("name") ? ss.get("name").getAsString() : "")
							&& ss.has("value") && ss.get("value").getAsBoolean()) {
							return true;
						}
					}
					return false;
				}
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	@Override
	public void onActivate() {
		warnInventoryEssentialsOnce();
		refreshServerKey();
		store.clearSession();
		clearLearnState();
	}

	@Override
	public void onDeactivate() {
		abortLearn();
		restoreConflicts();
		clearLearnState();
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		clickedThisTick = false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.player == null || mc.world == null) return;

		refreshServerKey();
		ingestLiveColors(mc);
		markWatchedChanges(mc);

		if (pendingClose && !hasOutstandingSwaps()) {
			performDeferredClose(mc);
			snapshotWatched(mc);
			return;
		}

		LearnMode mode = learn.get();
		if (mode == LearnMode.Off) {
			if (phase != LearnPhase.IDLE || !staged.isEmpty()) abortLearn();
			restoreConflicts();
			queue.clear();
			queuedKeys.clear();
			snapshotWatched(mc);
			return;
		}

		if (isMapDuperCloning()) {
			if (phase != LearnPhase.IDLE || !staged.isEmpty()) abortLearn();
			snapshotWatched(mc);
			return;
		}

		if (!stopStaging && !pendingClose) {
			enqueueMissingMaps(mc, mode);
		}
		tickLearn(mc, mode);
		snapshotWatched(mc);
	}

	@EventHandler
	private void onPacket(PacketEvent.Receive event) {
		if (!(event.packet instanceof MapUpdateS2CPacket pkt)) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) return;
		MapIdComponent mapIdComp = pkt.mapId();
		int id = mapIdComp.id();
		mc.execute(() -> {
			if (mc.world == null) return;
			MapState state = mc.world.getMapState(mapIdComp);
			if (state == null || state.colors == null || MapPreviewStore.isBlank(state.colors)) return;
			String name = findNameForMapId(mc, id);
			store.put(state.colors, id, name);
			if (active != null && active.mapId == id) {
				pixelsReadyForActive = true;
			}
		});
	}

	private void performDeferredClose(MinecraftClient mc) {
		if (mc.player == null) {
			pendingClose = false;
			stopStaging = false;
			return;
		}
		allowClose = true;
		pendingClose = false;
		stopStaging = false;
		try {
			mc.player.closeHandledScreen();
		} finally {
			allowClose = false;
		}
	}

	private void refreshServerKey() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		String key;
		if (mc.isInSingleplayer() && mc.getServer() != null) {
			key = mc.getServer().getSaveProperties().getLevelName();
		} else {
			ServerInfo info = mc.getCurrentServerEntry();
			key = info != null ? info.address : "unknown";
		}
		if (!key.equals(lastServerKey)) {
			lastServerKey = key;
			store.onServerKey(key);
			failedOnce.clear();
		}
	}

	private void ingestLiveColors(MinecraftClient mc) {
		if (!(mc.currentScreen instanceof HandledScreen<?>)) {
			scanStacks(mc, mc.player.getInventory().main);
			scanStacks(mc, List.of(mc.player.getOffHandStack()));
			return;
		}
		ScreenHandler handler = mc.player.currentScreenHandler;
		for (Slot slot : handler.slots) {
			absorbStack(mc, slot.getStack());
		}
		absorbStack(mc, handler.getCursorStack());
	}

	private void scanStacks(MinecraftClient mc, Iterable<ItemStack> stacks) {
		for (ItemStack stack : stacks) absorbStack(mc, stack);
	}

	private void absorbStack(MinecraftClient mc, ItemStack stack) {
		if (stack == null || !stack.isOf(Items.FILLED_MAP)) return;
		MapState state = FilledMapItem.getMapState(stack, mc.world);
		if (state == null || state.colors == null || MapPreviewStore.isBlank(state.colors)) return;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		store.put(state.colors, mid == null ? null : mid.id(), MapPreviewNames.keyOf(stack));
	}

	private void enqueueMissingMaps(MinecraftClient mc, LearnMode mode) {
		ScreenHandler handler = mc.player.currentScreenHandler;
		boolean allowContainer = mode == LearnMode.HotbarAndOffhand && isOpenStorageScreen(mc);

		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			ItemStack stack = slot.getStack();
			if (!stack.isOf(Items.FILLED_MAP)) continue;

			boolean playerSlot = isPlayerMainOrHotbar(slot, mc);
			boolean containerSlot = allowContainer && isStorageContainerSlot(slot, mc);
			if (!playerSlot && !containerSlot) continue;

			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid == null) continue;
			int mapId = mid.id();
			String name = MapPreviewNames.keyOf(stack);
			String key = "i:" + mapId;

			if (store.hasPreviewFor(mapId, name)) continue;
			if (queuedKeys.contains(key)) continue;
			if (failedOnce.contains(key)) continue;
			if (active != null && key.equals(active.key)) continue;
			if (isStagedKey(key)) continue;
			if (isSelectedHotbarMap(mc, mapId) || isOffhandMap(mc, mapId)) continue;

			LearnPath path;
			int invIndex = slot.getIndex();
			if (playerSlot && invIndex >= 0 && invIndex < 9) {
				path = LearnPath.HOTBAR_FLASH;
			} else if (playerSlot || containerSlot) {
				path = LearnPath.HOTBAR_SWAP;
			} else {
				continue;
			}

			LearnJob job = new LearnJob(key, mapId, name, path);
			job.originSlotId = i;
			queue.offer(job);
			queuedKeys.add(key);
		}
	}

	private boolean isStagedKey(String key) {
		for (LearnJob j : staged) {
			if (j.key.equals(key)) return true;
		}
		return false;
	}

	private void tickLearn(MinecraftClient mc, LearnMode mode) {
		if (mc.interactionManager == null || mc.getNetworkHandler() == null) return;
		if (mc.player.isUsingItem() || mc.player.handSwinging) return;

		ScreenHandler handler = mc.player.currentScreenHandler;
		if (!handler.getCursorStack().isEmpty()) return;

		boolean chain = true;
		while (chain) {
			chain = false;
			switch (phase) {
				case IDLE -> {
					if (pendingClose) {
						if (!staged.isEmpty()) {
							active = staged.remove(0);
							reservedHotbar.remove(active.hotbarIndex);
							savedSelectedSlot = mc.player.getInventory().selectedSlot;
							phase = LearnPhase.FLASH_RESTORE;
							chain = true;
							break;
						}
						restoreConflicts();
						return;
					}

					// Prefer flashing already-staged maps, then stage more, then flash-native / offhand.
					if (!staged.isEmpty() && findEmptyHotbar(mc) < 0) {
						startFlashStaged(mc);
						chain = true;
						break;
					}
					if (!stopStaging && tryStartStage(mc, handler, mode)) {
						chain = true;
						break;
					}
					if (!staged.isEmpty()) {
						startFlashStaged(mc);
						chain = true;
						break;
					}

					LearnJob next = pollNextJob();
					if (next == null) {
						restoreConflicts();
						return;
					}
					if (store.hasPreviewFor(next.mapId, next.name)) {
						chain = true;
						break;
					}
					int slotId = findOriginSlot(handler, mc, next);
					if (slotId < 0) {
						chain = true;
						break;
					}
					if (jobSlotBusy(handler, slotId)) {
						queue.offer(next);
						queuedKeys.add(next.key);
						return;
					}
					active = next;
					active.originSlotId = slotId;
					if (next.path == LearnPath.HOTBAR_FLASH) {
						Slot slot = handler.getSlot(slotId);
						if (isPlayerHotbar(slot, mc)) {
							active.hotbarIndex = slot.getIndex();
							savedSelectedSlot = mc.player.getInventory().selectedSlot;
							phase = LearnPhase.FLASH_SELECT;
						} else {
							finishActive(false);
							chain = true;
						}
					} else if (next.path == LearnPath.OFFHAND && mode == LearnMode.HotbarAndOffhand
						&& mc.player.getOffHandStack().isEmpty()) {
						pauseConflicts();
						active.didOffhandSwap = true;
						phase = LearnPhase.SWAP_OUT;
					} else {
						finishActive(false);
						chain = true;
					}
				}
				case STAGE_SWAP -> {
					if (active == null || active.hotbarIndex < 0) {
						phase = LearnPhase.IDLE;
						chain = true;
						break;
					}
					if (jobSlotBusy(handler, active.originSlotId) || clickedThisTick) return;
					if (!mc.player.getInventory().getStack(active.hotbarIndex).isEmpty()) {
						reservedHotbar.remove(active.hotbarIndex);
						finishActive(false);
						chain = true;
						break;
					}
					clickSwapHotbar(handler, active.originSlotId, active.hotbarIndex);
					active.didHotbarSwap = true;
					phase = LearnPhase.STAGE_WAIT;
					waitVisibleTicks = 0;
				}
				case STAGE_WAIT -> {
					waitVisibleTicks++;
					if (isHotbarMatching(mc, active)) {
						staged.add(active);
						active = null;
						phase = LearnPhase.IDLE;
						chain = !pendingClose;
					} else if (waitVisibleTicks > 10) {
						reservedHotbar.remove(active.hotbarIndex);
						finishActive(false);
						chain = true;
					}
				}
				case FLASH_SELECT -> {
					if (active == null || active.hotbarIndex < 0) {
						phase = LearnPhase.IDLE;
						chain = true;
						break;
					}
					setSelectedSlot(mc, active.hotbarIndex);
					phase = LearnPhase.FLASH_HOLD;
					holdTicks = 0;
					pixelsReadyForActive = false;
				}
				case FLASH_HOLD -> {
					holdTicks++;
					ItemStack held = mc.player.getMainHandStack();
					MapState state = FilledMapItem.getMapState(held, mc.world);
					boolean got = state != null && state.colors != null && !MapPreviewStore.isBlank(state.colors);
					if (got) {
						MapIdComponent mid = held.get(DataComponentTypes.MAP_ID);
						store.put(state.colors, mid == null ? active.mapId : mid.id(), MapPreviewNames.keyOf(held));
					}
					if (pixelsReadyForActive || got || pendingClose) {
						pixelsReadyForActive = false;
						phase = LearnPhase.FLASH_RESTORE;
					} else if (holdTicks >= 40) {
						phase = LearnPhase.FLASH_RESTORE;
					}
				}
				case FLASH_RESTORE -> {
					if (active != null && active.didHotbarSwap && active.hotbarIndex >= 0 && !clickedThisTick) {
						if (isHotbarMatching(mc, active)) {
							clickSwapHotbar(handler, active.originSlotId, active.hotbarIndex);
						}
						reservedHotbar.remove(active.hotbarIndex);
					}
					if (savedSelectedSlot >= 0) setSelectedSlot(mc, savedSelectedSlot);
					finishActive(true);
					chain = hasOutstandingSwaps() || (!queue.isEmpty() && !pendingClose);
				}
				case SWAP_OUT -> {
					if (active == null) {
						phase = LearnPhase.IDLE;
						chain = true;
						break;
					}
					if (!mc.player.getOffHandStack().isEmpty()) {
						finishActive(false);
						chain = true;
						break;
					}
					if (jobSlotBusy(handler, active.originSlotId) || clickedThisTick) return;
					clickSwapOffhand(handler, active.originSlotId);
					active.didOffhandSwap = true;
					phase = LearnPhase.WAIT_OUT;
					waitVisibleTicks = 0;
				}
				case WAIT_OUT -> {
					waitVisibleTicks++;
					if (isOffhandMatching(mc, active)) {
						phase = LearnPhase.HOLD;
						holdTicks = 0;
						pixelsReadyForActive = false;
					} else if (waitVisibleTicks > 10) {
						finishActive(false);
						chain = true;
					}
				}
				case HOLD -> {
					holdTicks++;
					ItemStack off = mc.player.getOffHandStack();
					MapState state = FilledMapItem.getMapState(off, mc.world);
					boolean got = state != null && state.colors != null && !MapPreviewStore.isBlank(state.colors);
					if (got) {
						MapIdComponent mid = off.get(DataComponentTypes.MAP_ID);
						store.put(state.colors, mid == null ? active.mapId : mid.id(), MapPreviewNames.keyOf(off));
					}
					if (pixelsReadyForActive || got || pendingClose) {
						pixelsReadyForActive = false;
						phase = LearnPhase.SWAP_BACK;
					} else if (holdTicks >= 40) {
						phase = LearnPhase.SWAP_BACK;
					}
				}
				case SWAP_BACK -> {
					if (active == null) {
						phase = LearnPhase.IDLE;
						chain = true;
						break;
					}
					if (!canRestoreOffhand(mc, handler)) {
						finishActive(false);
						chain = true;
						break;
					}
					if (clickedThisTick) return;
					clickSwapOffhand(handler, active.originSlotId);
					phase = LearnPhase.WAIT_BACK;
					waitVisibleTicks = 0;
				}
				case WAIT_BACK -> {
					waitVisibleTicks++;
					if (restored(mc, handler) || waitVisibleTicks > 10) {
						finishActive(true);
						chain = hasOutstandingSwaps() || (!queue.isEmpty() && !pendingClose);
					}
				}
			}
		}
	}

	private LearnJob pollNextJob() {
		LearnJob next = queue.poll();
		if (next == null) return null;
		queuedKeys.remove(next.key);
		return next;
	}

	private boolean tryStartStage(MinecraftClient mc, ScreenHandler handler, LearnMode mode) {
		int hb = findEmptyHotbar(mc);
		if (hb < 0) {
			// No empty hotbar: maybe empty offhand for one job
			if (mode == LearnMode.HotbarAndOffhand && mc.player.getOffHandStack().isEmpty()) {
				LearnJob off = pollSwapJob();
				if (off == null) return false;
				int slotId = findOriginSlot(handler, mc, off);
				if (slotId < 0 || jobSlotBusy(handler, slotId)) {
					queue.offer(off);
					queuedKeys.add(off.key);
					return false;
				}
				active = off;
				active.path = LearnPath.OFFHAND;
				active.originSlotId = slotId;
				pauseConflicts();
				phase = LearnPhase.SWAP_OUT;
				return true;
			}
			return false;
		}

		LearnJob job = pollSwapJob();
		if (job == null) return false;
		int slotId = findOriginSlot(handler, mc, job);
		if (slotId < 0 || jobSlotBusy(handler, slotId)) {
			queue.offer(job);
			queuedKeys.add(job.key);
			return false;
		}
		active = job;
		active.path = LearnPath.HOTBAR_SWAP;
		active.originSlotId = slotId;
		active.hotbarIndex = hb;
		reservedHotbar.add(hb);
		pauseConflicts();
		phase = LearnPhase.STAGE_SWAP;
		return true;
	}

	private LearnJob pollSwapJob() {
		Iterator<LearnJob> it = queue.iterator();
		while (it.hasNext()) {
			LearnJob j = it.next();
			if (j.path == LearnPath.HOTBAR_SWAP) {
				it.remove();
				queuedKeys.remove(j.key);
				return j;
			}
		}
		return null;
	}

	private void startFlashStaged(MinecraftClient mc) {
		if (staged.isEmpty()) return;
		active = staged.remove(0);
		reservedHotbar.remove(active.hotbarIndex);
		savedSelectedSlot = mc.player.getInventory().selectedSlot;
		phase = LearnPhase.FLASH_SELECT;
	}

	/** Empty hotbar only — never displace occupied slots (would dump items into storage). */
	private int findEmptyHotbar(MinecraftClient mc) {
		PlayerInventory inv = mc.player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (reservedHotbar.contains(i)) continue;
			if (inv.getStack(i).isEmpty()) return i;
		}
		return -1;
	}

	private void setSelectedSlot(MinecraftClient mc, int slot) {
		if (slot < 0 || slot > 8) return;
		mc.player.getInventory().selectedSlot = slot;
		mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		clickedThisTick = true;
	}

	private void finishActive(boolean mayRestoreModules) {
		if (active != null) {
			reservedHotbar.remove(active.hotbarIndex);
		}
		active = null;
		savedSelectedSlot = -1;
		phase = LearnPhase.IDLE;
		holdTicks = 0;
		waitVisibleTicks = 0;
		pixelsReadyForActive = false;
		if (mayRestoreModules && !hasOutstandingSwaps() && queue.isEmpty()) restoreConflicts();
	}

	private void abortLearn() {
		MinecraftClient mc = MinecraftClient.getInstance();
		bestEffortRestoreNow(mc);
		clearLearnState();
	}

	private void bestEffortRestoreNow(MinecraftClient mc) {
		if (mc == null || mc.player == null || mc.interactionManager == null) return;
		ScreenHandler handler = mc.player.currentScreenHandler;
		List<LearnJob> toRestore = new ArrayList<>(staged);
		staged.clear();
		if (active != null && (active.didHotbarSwap || active.didOffhandSwap)) {
			toRestore.add(0, active);
		}
		active = null;
		for (LearnJob job : toRestore) {
			if (job.didHotbarSwap && job.hotbarIndex >= 0 && isHotbarMatching(mc, job)) {
				clickSwapHotbar(handler, job.originSlotId, job.hotbarIndex);
			} else if (job.didOffhandSwap && isOffhandMatching(mc, job)) {
				ItemStack atOrigin = handler.getSlot(job.originSlotId).getStack();
				if (atOrigin.isEmpty()) {
					clickSwapOffhand(handler, job.originSlotId);
				}
			}
		}
		if (savedSelectedSlot >= 0 && mc.getNetworkHandler() != null) {
			setSelectedSlot(mc, savedSelectedSlot);
		}
	}

	private void clearLearnState() {
		phase = LearnPhase.IDLE;
		active = null;
		queue.clear();
		queuedKeys.clear();
		staged.clear();
		reservedHotbar.clear();
		savedSelectedSlot = -1;
		holdTicks = 0;
		waitVisibleTicks = 0;
		pixelsReadyForActive = false;
		stopStaging = false;
	}

	private void clickSwapOffhand(ScreenHandler handler, int slotId) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.interactionManager == null || mc.player == null) return;
		mc.interactionManager.clickSlot(handler.syncId, slotId, 40, SlotActionType.SWAP, mc.player);
		clickedThisTick = true;
	}

	private void clickSwapHotbar(ScreenHandler handler, int slotId, int hotbarIndex) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.interactionManager == null || mc.player == null) return;
		if (hotbarIndex < 0 || hotbarIndex > 8) return;
		mc.interactionManager.clickSlot(handler.syncId, slotId, hotbarIndex, SlotActionType.SWAP, mc.player);
		clickedThisTick = true;
	}

	private boolean canRestoreOffhand(MinecraftClient mc, ScreenHandler handler) {
		if (active == null) return false;
		if (active.originSlotId < 0 || active.originSlotId >= handler.slots.size()) return false;
		ItemStack off = mc.player.getOffHandStack();
		if (!off.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = off.get(DataComponentTypes.MAP_ID);
		if (mid == null || mid.id() != active.mapId) return false;
		return handler.getSlot(active.originSlotId).getStack().isEmpty();
	}

	private boolean restored(MinecraftClient mc, ScreenHandler handler) {
		if (active == null) return true;
		ItemStack inv = handler.getSlot(active.originSlotId).getStack();
		if (!inv.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = inv.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == active.mapId;
	}

	private boolean isOffhandMatching(MinecraftClient mc, LearnJob job) {
		ItemStack off = mc.player.getOffHandStack();
		if (!off.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = off.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == job.mapId;
	}

	private boolean isHotbarMatching(MinecraftClient mc, LearnJob job) {
		if (job.hotbarIndex < 0 || job.hotbarIndex > 8) return false;
		ItemStack stack = mc.player.getInventory().getStack(job.hotbarIndex);
		if (!stack.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == job.mapId;
	}

	private int findOriginSlot(ScreenHandler handler, MinecraftClient mc, LearnJob job) {
		if (job.originSlotId >= 0 && job.originSlotId < handler.slots.size()) {
			ItemStack stack = handler.getSlot(job.originSlotId).getStack();
			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid != null && mid.id() == job.mapId) return job.originSlotId;
		}
		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			ItemStack stack = slot.getStack();
			if (!stack.isOf(Items.FILLED_MAP)) continue;
			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid == null || mid.id() != job.mapId) continue;
			if (job.path == LearnPath.HOTBAR_FLASH && !isPlayerHotbar(slot, mc)) continue;
			if (job.path == LearnPath.HOTBAR_SWAP || job.path == LearnPath.OFFHAND) {
				boolean ok = isPlayerMainOrHotbar(slot, mc) || isStorageContainerSlot(slot, mc);
				if (!ok) continue;
			}
			return i;
		}
		return -1;
	}

	private boolean jobSlotBusy(ScreenHandler handler, int slotId) {
		if (!handler.getCursorStack().isEmpty()) return true;
		if (watchedChangedThisTick) return true;
		ItemStack now = handler.getSlot(slotId).getStack();
		ItemStack prev = lastWatchedStacks.get(slotId);
		return prev != null && !ItemStack.areItemsAndComponentsEqual(prev, now);
	}

	private void markWatchedChanges(MinecraftClient mc) {
		watchedChangedThisTick = false;
		ScreenHandler handler = mc.player.currentScreenHandler;
		ItemStack cursor = handler.getCursorStack();
		if (!ItemStack.areItemsAndComponentsEqual(lastCursor, cursor)) {
			watchedChangedThisTick = true;
			return;
		}
		for (int slotId : watchedSlotIds(handler, mc)) {
			ItemStack stack = handler.getSlot(slotId).getStack();
			ItemStack prev = lastWatchedStacks.get(slotId);
			if (prev != null && !ItemStack.areItemsAndComponentsEqual(prev, stack)) {
				watchedChangedThisTick = true;
				return;
			}
		}
	}

	private void snapshotWatched(MinecraftClient mc) {
		ScreenHandler handler = mc.player.currentScreenHandler;
		lastCursor = handler.getCursorStack().copy();
		Map<Integer, ItemStack> next = new HashMap<>();
		for (int slotId : watchedSlotIds(handler, mc)) {
			next.put(slotId, handler.getSlot(slotId).getStack().copy());
		}
		lastWatchedStacks.clear();
		lastWatchedStacks.putAll(next);
	}

	private Set<Integer> watchedSlotIds(ScreenHandler handler, MinecraftClient mc) {
		Set<Integer> ids = new LinkedHashSet<>();
		for (int i = 0; i < handler.slots.size(); i++) {
			if (isOffhandSlot(handler.getSlot(i), mc)) ids.add(i);
		}
		if (active != null && active.originSlotId >= 0 && active.originSlotId < handler.slots.size()) {
			ids.add(active.originSlotId);
		}
		for (LearnJob j : staged) {
			if (j.originSlotId >= 0 && j.originSlotId < handler.slots.size()) ids.add(j.originSlotId);
		}
		return ids;
	}

	private static boolean isOpenStorageScreen(MinecraftClient mc) {
		return mc.currentScreen instanceof ShulkerBoxScreen
			|| mc.currentScreen instanceof GenericContainerScreen;
	}

	private static boolean isStorageContainerSlot(Slot slot, MinecraftClient mc) {
		if (slot.inventory instanceof PlayerInventory) return false;
		if (mc.currentScreen instanceof ShulkerBoxScreen) return true;
		if (mc.currentScreen instanceof GenericContainerScreen) return true;
		return false;
	}

	private static boolean isPlayerMainOrHotbar(Slot slot, MinecraftClient mc) {
		if (slot.inventory != mc.player.getInventory()) return false;
		int idx = slot.getIndex();
		return idx >= 0 && idx < 36;
	}

	private static boolean isPlayerHotbar(Slot slot, MinecraftClient mc) {
		if (slot.inventory != mc.player.getInventory()) return false;
		int idx = slot.getIndex();
		return idx >= 0 && idx < 9;
	}

	private static boolean isOffhandSlot(Slot slot, MinecraftClient mc) {
		return slot.inventory == mc.player.getInventory() && slot.getIndex() == 40;
	}

	private static boolean isSelectedHotbarMap(MinecraftClient mc, int mapId) {
		ItemStack stack = mc.player.getMainHandStack();
		if (!stack.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == mapId;
	}

	private static boolean isOffhandMap(MinecraftClient mc, int mapId) {
		ItemStack stack = mc.player.getOffHandStack();
		if (!stack.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == mapId;
	}

	private static String findNameForMapId(MinecraftClient mc, int mapId) {
		if (mc.player == null) return null;
		ScreenHandler handler = mc.player.currentScreenHandler;
		for (Slot slot : handler.slots) {
			ItemStack stack = slot.getStack();
			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid != null && mid.id() == mapId) {
				String n = MapPreviewNames.keyOf(stack);
				if (n != null) return n;
			}
		}
		for (int i = 0; i < mc.player.getInventory().size(); i++) {
			ItemStack stack = mc.player.getInventory().getStack(i);
			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid != null && mid.id() == mapId) {
				String n = MapPreviewNames.keyOf(stack);
				if (n != null) return n;
			}
		}
		return null;
	}

	private void pauseConflicts() {
		if (!pausedPrior.isEmpty()) return;
		pauseIfActive("meteordevelopment.meteorclient.systems.modules.player.AutoReplenish");
		pauseIfActive("meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks");
		pauseIfActive("bep.hax.modules.Replenish");
		pauseIfActive("bep.hax.modules.MapDuplicator");
	}

	private void pauseIfActive(String className) {
		Module mod = findByClassName(className);
		if (mod == null) return;
		pausedPrior.put(className, mod.isActive());
		if (mod.isActive()) mod.toggle();
	}

	private void restoreConflicts() {
		for (Map.Entry<String, Boolean> e : pausedPrior.entrySet()) {
			Module mod = findByClassName(e.getKey());
			if (mod == null) continue;
			if (e.getValue() && !mod.isActive()) mod.toggle();
			if (!e.getValue() && mod.isActive()) mod.toggle();
		}
		pausedPrior.clear();
	}

	private static Module findByClassName(String className) {
		try {
			for (Module mod : Modules.get().getAll()) {
				if (mod.getClass().getName().equals(className)) return mod;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private void warnInventoryEssentialsOnce() {
		if (warnedInventoryEssentials) return;
		warnedInventoryEssentials = true;
		try {
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("inventoryessentials")) {
				warning("inventoryessentials is installed and cannot be paused — learn waits one tick if the cursor or active map slot is busy.");
			}
		} catch (Throwable ignored) {
		}
	}
}
