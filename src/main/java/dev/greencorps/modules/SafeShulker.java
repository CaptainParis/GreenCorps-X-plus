package dev.greencorps.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import dev.greencorps.GreenCorps;
import dev.greencorps.gui.SafeShulkerScreen;
import dev.greencorps.util.ClickBudget;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.BetterTooltips;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
* Safe Shulker
* <p>
* 1. Middle-click (or the open bind) a shulker in your inventory -> a fake, client-side shulker screen opens
*    showing its contents. Nothing is placed and nothing is sent to the server.
* 2. Move items in/out like a normal shulker. Every click is recorded and simulated locally.
* 3. When you close it, the module places the real shulker nearby, opens it, replays your clicks, closes it
*    and (optionally) mines it back up.
* <p>
* The fake screen works on a detached copy of your inventory with the shulker's own slot already empty, so the
* simulation matches what the real screen will look like once the shulker is placed. Your real inventory is
* never touched until the replay. Replayed clicks go through {@link ClickBudget} (2b2t kicks for &gt;80 clicks
* per 80 ticks).
*/
public class SafeShulker extends Module {
	public enum Mode { Preview, Direct }
	public enum PlaceAt { AboveHead, Nearby }

	private enum Stage { IDLE, GHOST, PLACE, WAIT_PLACE, OPEN, WAIT_OPEN, REPLAY, MINE }

	private record GhostClick(int slot, int button, SlotActionType action) {}

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgPlace = settings.createGroup("Place");
	private final SettingGroup sgReplay = settings.createGroup("Replay");
	private final SettingGroup sgMine = settings.createGroup("Mine");

	// General
	private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
		.name("mode")
		.description("Preview: pick items in a fake screen first, then it's placed/replayed/broken. Direct: places it over your head and opens it for real, breaks it the moment you close it.")
		.defaultValue(Mode.Preview)
		.build()
	);

	private final Setting<Boolean> middleClick = sgGeneral.add(new BoolSetting.Builder()
		.name("middle-click")
		.description("Middle-click a shulker in any inventory screen to open it.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Keybind> openBind = sgGeneral.add(new KeybindSetting.Builder()
		.name("open-bind")
		.description("Alternate key to open the hovered shulker.")
		.defaultValue(Keybind.none())
		.build()
	);

	private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
		.name("rotate")
		.description("Rotate server-side when placing, opening and mining the shulker.")
		.defaultValue(true)
		.build()
	);

	// Place
	private final Setting<PlaceAt> placeAt = sgPlace.add(new EnumSetting.Builder<PlaceAt>()
		.name("place-at")
		.description("AboveHead: right over you so it drops straight back into your inventory. Nearby: any free spot around you.")
		.defaultValue(PlaceAt.AboveHead)
		.build()
	);

	private final Setting<Boolean> fallbackNearby = sgPlace.add(new BoolSetting.Builder()
		.name("fallback-nearby")
		.description("If the spot above your head is blocked, use a nearby spot instead.")
		.defaultValue(true)
		.visible(() -> placeAt.get() == PlaceAt.AboveHead)
		.build()
	);

	private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
		.name("place-range")
		.description("How far to search for a spot to place the shulker.")
		.defaultValue(4.0)
		.range(1.0, 6.0)
		.sliderRange(1.0, 6.0)
		.build()
	);

	private final Setting<Boolean> airPlace = sgPlace.add(new BoolSetting.Builder()
		.name("air-place")
		.description("Place in mid-air above your head when there is no block to place against. Anticheats such as Grim flag this, so it is off by default.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Boolean> stopMotion = sgPlace.add(new BoolSetting.Builder()
		.name("stop-motion")
		.description("Zero your velocity while the shulker is being placed / used.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Integer> openDelay = sgPlace.add(new IntSetting.Builder()
		.name("open-delay")
		.description("Ticks to wait after the shulker appears before opening it.")
		.defaultValue(1)
		.range(0, 20)
		.sliderRange(0, 20)
		.build()
	);

	private final Setting<Integer> timeout = sgPlace.add(new IntSetting.Builder()
		.name("timeout")
		.description("Ticks to wait for the server (placing / opening) before giving up.")
		.defaultValue(40)
		.range(5, 200)
		.sliderRange(5, 100)
		.build()
	);

	// Replay
	private final Setting<Integer> clicksPerTick = sgReplay.add(new IntSetting.Builder()
		.name("clicks-per-tick")
		.description("Most recorded clicks to replay in one tick (the shared click budget can slow this down).")
		.defaultValue(4)
		.range(1, 20)
		.sliderRange(1, 10)
		.build()
	);

	private final Setting<Boolean> autoClose = sgReplay.add(new BoolSetting.Builder()
		.name("auto-close")
		.description("Close the real shulker after replaying your clicks.")
		.defaultValue(true)
		.visible(() -> mode.get() == Mode.Preview)
		.build()
	);

	// Mine
	private final Setting<Boolean> mine = sgMine.add(new BoolSetting.Builder()
		.name("mine")
		.description("Break the shulker as soon as it's closed so it drops back to you. Works with Meteor's Speed Mine / Packet Mine.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> autoSwapTool = sgMine.add(new BoolSetting.Builder()
		.name("auto-swap-tool")
		.description("Swap to the fastest hotbar tool while mining, then swap back.")
		.defaultValue(true)
		.visible(mine::get)
		.build()
	);

	private final Setting<Integer> mineTimeout = sgMine.add(new IntSetting.Builder()
		.name("mine-timeout")
		.description("Ticks to keep trying to mine before giving up.")
		.defaultValue(100)
		.range(10, 400)
		.sliderRange(10, 200)
		.visible(mine::get)
		.build()
	);

	// State
	private final List<GhostClick> clicks = new ArrayList<>();
	private Stage stage = Stage.IDLE;
	private ItemStack ghostStack = ItemStack.EMPTY;
	private ShulkerBoxScreenHandler ghostMenu;
	private int shulkerInvSlot = -1;

	private BlockPos placePos;
	private BlockHitResult placeHit;
	private Direction openFace = Direction.UP;
	private boolean overhead;

	private int replayIndex;
	private int timer;
	private boolean mineSwapped;

	public SafeShulker() {
		super(GreenCorps.CATEGORY, "safe-shulker", "Middle-click a shulker in your inventory to use it over your head, then it's broken so it drops right back to you.");
	}

	@Override
	public void onActivate() {
		BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);
		if (middleClick.get() && tooltips != null && tooltips.middleClickOpen()) {
			warning("Meteor Better Tooltips 'middle-click-open' also uses middle-click; turn one of them off.");
		}
	}

	@Override
	public void onDeactivate() {
		stage = Stage.IDLE; // so onGhostClosed() is a no-op
		if (mc.currentScreen instanceof SafeShulkerScreen) mc.setScreen(null);
		reset();
	}

	@Override
	public String getInfoString() {
		return stage == Stage.IDLE ? null : stage.name().toLowerCase();
	}

	// ------------------------------------------------------------------
	// Entry points (called from HandledScreenMixin)
	// ------------------------------------------------------------------

	public boolean tryOpenFromMouse(Slot slot, int button) {
		if (!canStart()) return false;
		if (!middleClick.get() || button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return false;
		return openGhost(slot);
	}

	public boolean tryOpenFromKey(Slot slot, int keyCode, int modifiers) {
		if (!canStart()) return false;
		Keybind bind = openBind.get();
		if (!bind.isSet() || !bind.matches(true, keyCode, modifiers)) return false;
		return openGhost(slot);
	}

	private boolean canStart() {
		return isActive() && mc.player != null && mc.world != null && stage == Stage.IDLE;
	}

	private boolean openGhost(Slot slot) {
		if (slot == null || !(slot.inventory instanceof PlayerInventory)) return false;

		int invIndex = slot.getIndex();
		if (invIndex < 0 || invIndex > 35) return false; // main inventory + hotbar only

		ItemStack stack = slot.getStack();
		if (!isShulker(stack)) return false;
		if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return false;

		if (mode.get() == Mode.Direct) {
			// Close the inventory and go straight to placing it over your head
			mc.player.closeHandledScreen();
			clicks.clear();
			shulkerInvSlot = invIndex;
			ghostStack = stack.copy();
			stage = Stage.PLACE;
			timer = 0;
			return true;
		}

		// Close whatever container is open (chest, etc.) so the server isn't confused
		if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();

		clicks.clear();
		replayIndex = 0;
		shulkerInvSlot = invIndex;
		ghostStack = stack.copy();

		SimpleInventory ghostInv = loadShulkerContents(stack);
		PlayerInventory ghostPlayer = detachedInventory(invIndex);

		ghostMenu = new ShulkerBoxScreenHandler(0, ghostPlayer, ghostInv);
		stage = Stage.GHOST;
		mc.setScreen(new SafeShulkerScreen(this, ghostMenu, ghostPlayer, stack.getName()));
		return true;
	}

	public void recordClick(int slotId, int button, SlotActionType action) {
		if (stage == Stage.GHOST) clicks.add(new GhostClick(slotId, button, action));
	}

	public void onGhostClosed() {
		if (stage != Stage.GHOST) return;
		ghostMenu.setCursorStack(ItemStack.EMPTY);

		if (clicks.isEmpty()) {
			reset(); // just peeked, nothing to do
			return;
		}

		stage = Stage.PLACE;
		timer = 0;
	}

	// ------------------------------------------------------------------
	// State machine
	// ------------------------------------------------------------------

	@EventHandler
	private void onGameLeft(GameLeftEvent event) {
		reset();
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (mc.player == null || mc.world == null) {
			reset();
			return;
		}

		if (stopMotion.get() && stage.ordinal() >= Stage.PLACE.ordinal() && stage != Stage.MINE) {
			mc.player.setVelocity(Vec3d.ZERO);
		}

		switch (stage) {
			case PLACE -> tickPlace();
			case WAIT_PLACE -> tickWaitPlace();
			case OPEN -> tickOpen();
			case WAIT_OPEN -> tickWaitOpen();
			case REPLAY -> tickReplay();
			case MINE -> tickMine();
			default -> {}
		}
	}

	private void tickPlace() {
		// Make sure the shulker is still where we think it is
		int slot = findShulkerSlot();
		if (slot == -1) {
			error("Shulker is no longer in your inventory.");
			reset();
			return;
		}
		shulkerInvSlot = slot;
		// A shulker in the main inventory is swapped into the hotbar and back (2 clicks); wait for budget.
		if (shulkerInvSlot > 8 && !ClickBudget.canSend(2)) return;

		if (!findPlacePos()) {
			error("No place spot nearby.");
			reset();
			return;
		}

		Vec3d hitVec = placeHit.getPos();
		if (rotate.get()) {
			Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 50, this::doPlace);
		} else {
			doPlace();
		}

		stage = Stage.WAIT_PLACE;
		timer = 0;
	}

	private void doPlace() {
		if (mc.player == null || placeHit == null) return;

		PlayerInventory inv = mc.player.getInventory();
		boolean fromMain = shulkerInvSlot > 8;
		int hotbar = fromMain ? inv.selectedSlot : shulkerInvSlot;

		if (fromMain) {
			// Swap the shulker into the selected hotbar slot (player inventory handler: main slots 9-35 map 1:1)
			mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, shulkerInvSlot, hotbar, SlotActionType.SWAP, mc.player);
		} else {
			InvUtils.swap(hotbar, true);
		}

		mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
		mc.player.swingHand(Hand.MAIN_HAND);

		if (fromMain) {
			// Shulker is gone now; this puts the original hotbar item back where it was
			mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, shulkerInvSlot, hotbar, SlotActionType.SWAP, mc.player);
		} else {
			InvUtils.swapBack();
		}
	}

	private void tickWaitPlace() {
		timer++;
		if (mc.world.getBlockState(placePos).getBlock() instanceof ShulkerBoxBlock) {
			if (timer >= openDelay.get()) {
				stage = Stage.OPEN;
				timer = 0;
			}
		} else if (timer > timeout.get()) {
			error("Failed to place the shulker.");
			reset();
		}
	}

	private void tickOpen() {
		if (!(mc.world.getBlockState(placePos).getBlock() instanceof ShulkerBoxBlock)) {
			error("Shulker disappeared before it could be opened.");
			reset();
			return;
		}

		Vec3d hitVec = Vec3d.ofCenter(placePos).add(Vec3d.of(openFace.getVector()).multiply(0.5));
		BlockHitResult hit = new BlockHitResult(hitVec, openFace, placePos, false);

		Runnable open = () -> {
			if (mc.player == null) return;
			mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
			mc.player.swingHand(Hand.MAIN_HAND);
		};

		if (rotate.get()) Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 50, open);
		else open.run();

		stage = Stage.WAIT_OPEN;
		timer = 0;
	}

	private void tickWaitOpen() {
		if (isRealShulkerOpen()) {
			timer = 0;
			if (mode.get() == Mode.Direct) {
				// You take what you need; it gets broken as soon as you close it
				if (mine.get()) {
					stage = Stage.MINE;
					mineSwapped = false;
				} else {
					reset();
				}
			} else {
				stage = Stage.REPLAY;
				replayIndex = 0;
			}
			return;
		}

		if (++timer > timeout.get()) {
			error("Shulker did not open (is something blocking the lid? are you sneaking?).");
			reset();
		}
	}

	private void tickReplay() {
		if (!isRealShulkerOpen()) {
			error("Shulker closed before all clicks were replayed (%d/%d).", replayIndex, clicks.size());
			reset();
			return;
		}

		int syncId = mc.player.currentScreenHandler.syncId;
		for (int i = 0; i < clicksPerTick.get() && replayIndex < clicks.size() && ClickBudget.canSend(1); i++, replayIndex++) {
			GhostClick c = clicks.get(replayIndex);
			mc.interactionManager.clickSlot(syncId, c.slot(), c.button(), c.action(), mc.player);
		}

		if (replayIndex >= clicks.size()) {
			info("Replayed %d click%s.", clicks.size(), clicks.size() == 1 ? "" : "s");
			if (autoClose.get()) mc.player.closeHandledScreen();

			if (mine.get()) {
				stage = Stage.MINE;
				timer = 0;
				mineSwapped = false;
			} else {
				reset();
			}
		}
	}

	private void tickMine() {
		// If auto-close is off, wait for the player to close the real shulker
		if (isRealShulkerOpen()) return;

		BlockState state = mc.world.getBlockState(placePos);
		if (!(state.getBlock() instanceof ShulkerBoxBlock)) {
			if (!overhead) info("Shulker broken at %d %d %d, go pick it up.", placePos.getX(), placePos.getY(), placePos.getZ());
			reset(); // mined, done
			return;
		}

		if (++timer > mineTimeout.get()) {
			error("Couldn't mine the shulker in time, it's still at %d %d %d.", placePos.getX(), placePos.getY(), placePos.getZ());
			reset();
			return;
		}

		if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(placePos)) > 6.0) {
			error("Shulker is out of reach.");
			reset();
			return;
		}

		if (autoSwapTool.get() && !mineSwapped) {
			int tool = findBestTool(state);
			if (tool != -1) {
				InvUtils.swap(tool, true);
				mineSwapped = true;
			}
		}

		if (rotate.get()) {
			Vec3d c = Vec3d.ofCenter(placePos);
			Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c), 50, () -> BlockUtils.breakBlock(placePos, true));
		} else {
			BlockUtils.breakBlock(placePos, true);
		}
	}

	// ------------------------------------------------------------------
	// Placement
	// ------------------------------------------------------------------

	private boolean findPlacePos() {
		overhead = false;
		if (placeAt.get() == PlaceAt.AboveHead) {
			if (findOverheadPos()) {
				overhead = true;
				return true;
			}
			if (!fallbackNearby.get()) return false;
			warning("Can't place above your head, using a nearby spot instead.");
		}
		return findNearbyPos();
	}

	/**
	* The block 2 above your feet (right above your head). When it's broken the shulker drops straight
	* down onto you, so nobody else can grab it.
	*/
	private boolean findOverheadPos() {
		BlockPos pos = mc.player.getBlockPos().up(2);
		if (!BlockUtils.canPlace(pos, true)) return false;

		// Prefer placing against a real block if there is one (ceiling or a side)
		for (Direction dir : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			BlockPos neighbour = pos.offset(dir);
			BlockState ns = mc.world.getBlockState(neighbour);
			if (ns.isReplaceable() || BlockUtils.isClickable(ns.getBlock())) continue;
			if (ns.getCollisionShape(mc.world, neighbour).isEmpty()) continue;

			Direction face = dir.getOpposite();
			if (!hasRoomForLid(pos, face)) continue;

			Vec3d hitVec = Vec3d.ofCenter(neighbour).add(Vec3d.of(face.getVector()).multiply(0.5));
			placePos = pos;
			placeHit = new BlockHitResult(hitVec, face, neighbour, false);
			openFace = Direction.DOWN; // click the bottom, the side facing you
			return true;
		}

		// Nothing to place against: air-place it
		if (!airPlace.get()) return false;
		Direction face = hasRoomForLid(pos, Direction.UP) ? Direction.UP : Direction.DOWN;
		placePos = pos;
		placeHit = new BlockHitResult(Vec3d.ofCenter(pos), face, pos, false);
		openFace = Direction.DOWN;
		return true;
	}

	/** Finds a spot with a solid neighbour to click on and room for the lid to open. */
	private boolean findNearbyPos() {
		Vec3d eyes = mc.player.getEyePos();
		double range = placeRange.get();
		int r = (int) Math.ceil(range);
		BlockPos origin = mc.player.getBlockPos();

		BlockPos bestPos = null;
		BlockHitResult bestHit = null;
		double bestDist = Double.MAX_VALUE;

		for (int x = -r; x <= r; x++) {
			for (int y = -r; y <= r + 1; y++) {
				for (int z = -r; z <= r; z++) {
					BlockPos pos = origin.add(x, y, z);
					if (!BlockUtils.canPlace(pos, true)) continue;

					for (Direction dir : Direction.values()) {
						BlockPos neighbour = pos.offset(dir);
						BlockState ns = mc.world.getBlockState(neighbour);
						if (ns.isReplaceable() || BlockUtils.isClickable(ns.getBlock())) continue;
						if (ns.getCollisionShape(mc.world, neighbour).isEmpty()) continue;

						// Shulker FACING = the face we click on, i.e. pointing away from the neighbour
						Direction face = dir.getOpposite();
						if (!hasRoomForLid(pos, face)) continue;

						Vec3d hitVec = Vec3d.ofCenter(neighbour).add(Vec3d.of(face.getVector()).multiply(0.5));
						double dist = eyes.distanceTo(hitVec);
						if (dist > range || dist >= bestDist) continue;

						bestDist = dist;
						bestPos = pos;
						bestHit = new BlockHitResult(hitVec, face, neighbour, false);
					}
				}
			}
		}

		if (bestPos == null && airPlace.get()) {
			BlockPos above = origin.up(2);
			if (BlockUtils.canPlace(above, true) && hasRoomForLid(above, Direction.UP)) {
				bestPos = above;
				bestHit = new BlockHitResult(Vec3d.ofCenter(above), Direction.UP, above, false);
			}
		}

		if (bestPos == null) return false;

		placePos = bestPos;
		placeHit = bestHit;
		openFace = bestHit.getSide();
		return true;
	}

	private boolean hasRoomForLid(BlockPos pos, Direction facing) {
		BlockPos front = pos.offset(facing);
		return mc.world.getBlockState(front).getCollisionShape(mc.world, front).isEmpty();
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static boolean isShulker(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
	}

	private int findShulkerSlot() {
		PlayerInventory inv = mc.player.getInventory();
		if (shulkerInvSlot >= 0 && ItemStack.areItemsAndComponentsEqual(inv.getStack(shulkerInvSlot), ghostStack)) return shulkerInvSlot;
		for (int i = 0; i < 36; i++) {
			if (ItemStack.areItemsAndComponentsEqual(inv.getStack(i), ghostStack)) return i;
		}
		return -1;
	}

	private SimpleInventory loadShulkerContents(ItemStack stack) {
		DefaultedList<ItemStack> items = DefaultedList.ofSize(27, ItemStack.EMPTY);
		ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
		if (container != null) container.copyTo(items);
		SimpleInventory inv = new SimpleInventory(27);
		for (int i = 0; i < 27; i++) inv.setStack(i, items.get(i).copy());
		return inv;
	}

	/**
	 * A copy of your inventory for the fake screen, with the shulker's own slot empty: once the real shulker is
	 * placed that slot is free, so shift-clicks in the preview land where they will land for real.
	 */
	private PlayerInventory detachedInventory(int shulkerSlot) {
		PlayerInventory real = mc.player.getInventory();
		PlayerInventory copy = new PlayerInventory(mc.player);
		for (int i = 0; i < real.size(); i++) copy.setStack(i, real.getStack(i).copy());
		copy.setStack(shulkerSlot, ItemStack.EMPTY);
		copy.selectedSlot = real.selectedSlot;
		return copy;
	}

	private boolean isRealShulkerOpen() {
		return mc.player != null
			&& mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler
			&& mc.player.currentScreenHandler != ghostMenu;
	}

	private int findBestTool(BlockState state) {
		int best = -1;
		float bestSpeed = 1.0f;
		for (int i = 0; i < 9; i++) {
			float speed = mc.player.getInventory().getStack(i).getMiningSpeedMultiplier(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = i;
			}
		}
		return best;
	}

	private void reset() {
		if (mineSwapped) {
			InvUtils.swapBack();
			mineSwapped = false;
		}
		stage = Stage.IDLE;
		clicks.clear();
		replayIndex = 0;
		timer = 0;
		placePos = null;
		placeHit = null;
		overhead = false;
		ghostMenu = null;
		ghostStack = ItemStack.EMPTY;
		shulkerInvSlot = -1;
	}
}
