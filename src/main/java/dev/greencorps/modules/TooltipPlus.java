package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.gui.GcMapTooltipComponent;
import dev.greencorps.gui.tooltip.GcBannerPlusTooltipComponent;
import dev.greencorps.gui.tooltip.GcBookTooltipComponent;
import dev.greencorps.gui.tooltip.GcBundleTooltipComponent;
import dev.greencorps.gui.tooltip.GcStorageTooltipComponent;
import dev.greencorps.util.BannerGiveCommand;
import dev.greencorps.util.BundleSelect;
import dev.greencorps.util.EnderChestCache;
import dev.greencorps.util.ItemLocations;
import dev.greencorps.util.ItemStackBytes;
import dev.greencorps.util.MapCenterCache;
import meteordevelopment.meteorclient.events.game.ItemStackTooltipEvent;
import meteordevelopment.meteorclient.events.render.TooltipDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.TooltipBackgroundRenderer;
import net.minecraft.client.input.Scroller;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.item.BannerItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GreenCorps tooltip suite: bundle, storage, book, banner, map, location, file size.
 */
public class TooltipPlus extends Module {
	public enum BundleMapMode {
		LOCKED("Locked only"),
		BOTH("Locked and hover");

		private final String label;

		BundleMapMode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public enum StorageStyle {
		Preview("Preview"),
		List("List");

		private final String title;

		StorageStyle(String title) {
			this.title = title;
		}

		@Override
		public String toString() {
			return title;
		}
	}

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgBundle = settings.createGroup("Bundle");
	private final SettingGroup sgLocation = settings.createGroup("Location");
	private final SettingGroup sgStorage = settings.createGroup("Storage");
	private final SettingGroup sgMap = settings.createGroup("Map");
	private final SettingGroup sgBook = settings.createGroup("Book");
	private final SettingGroup sgBanner = settings.createGroup("Banner");

	private final Setting<Boolean> itemFileSize = sgGeneral.add(new BoolSetting.Builder()
		.name("item-file-size")
		.description("Show estimated item size at the bottom of tooltips (banners draw size in the preview).")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> bundlePreview = sgBundle.add(new BoolSetting.Builder()
		.name("bundle-preview")
		.description("Vanilla-style bundle tooltip that shows every stack (grid grows to 8×8); scroll selects any of them.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Keybind> bundleLock = sgBundle.add(new KeybindSetting.Builder()
		.name("bundle-lock")
		.description("Press over a bundle to pin its tooltip; click a cell to take that stack. Press again or Esc to unpin.")
		.defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_LEFT_ALT))
		.visible(bundlePreview::get)
		.build()
	);

	private final Setting<Boolean> bundleMapPreview = sgBundle.add(new BoolSetting.Builder()
		.name("bundle-map-preview")
		.description("Show the preview of a map in a bundle next to the bundle tooltip.")
		.defaultValue(true)
		.visible(bundlePreview::get)
		.build()
	);

	private final Setting<BundleMapMode> bundleMapMode = sgBundle.add(new EnumSetting.Builder<BundleMapMode>()
		.name("bundle-map-preview-mode")
		.description("Locked only: for the hovered cell of a pinned bundle. Locked and hover: also for the selected stack while you hover a bundle.")
		.defaultValue(BundleMapMode.BOTH)
		.visible(() -> bundlePreview.get() && bundleMapPreview.get())
		.build()
	);

	private final Setting<Double> bundleMapScale = sgBundle.add(new DoubleSetting.Builder()
		.name("bundle-map-scale")
		.description("Size of the map preview next to a bundle.")
		.defaultValue(0.75)
		.min(0.25)
		.sliderRange(0.25, 2.0)
		.visible(() -> bundlePreview.get() && bundleMapPreview.get())
		.build()
	);

	private final Setting<Boolean> locationTooltip = sgLocation.add(new BoolSetting.Builder()
		.name("tooltip")
		.description("Add coordinate lines for maps, compasses, and hives.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> locationHighlight = sgLocation.add(new BoolSetting.Builder()
		.name("highlight")
		.description("Tint items that carry coordinates.")
		.defaultValue(true)
		.build()
	);

	private final Setting<SettingColor> locationColor = sgLocation.add(new ColorSetting.Builder()
		.name("highlight-color")
		.description("Slot tint and coordinate line color.")
		.defaultValue(new SettingColor(255, 210, 0, 70))
		.build()
	);

	private final Setting<Boolean> locationHighlightHotbar = sgLocation.add(new BoolSetting.Builder()
		.name("highlight-hotbar")
		.description("Tint matching items on the HUD hotbar.")
		.defaultValue(true)
		.visible(locationHighlight::get)
		.build()
	);

	private final Setting<Boolean> locationShowDistance = sgLocation.add(new BoolSetting.Builder()
		.name("show-distance")
		.description("Append horizontal distance.")
		.defaultValue(true)
		.visible(locationTooltip::get)
		.build()
	);

	private final Setting<Boolean> locationShowOrigin = sgLocation.add(new BoolSetting.Builder()
		.name("server-origin")
		.description("Show Paper.Origin when present.")
		.defaultValue(true)
		.visible(locationTooltip::get)
		.build()
	);

	private final Setting<Integer> locationMaxLines = sgLocation.add(new IntSetting.Builder()
		.name("max-lines")
		.description("Cap on location lines.")
		.defaultValue(10)
		.min(1)
		.max(24)
		.sliderRange(1, 16)
		.visible(locationTooltip::get)
		.build()
	);

	private final Setting<Boolean> locationNested = sgLocation.add(new BoolSetting.Builder()
		.name("nested-containers")
		.description("Read coords inside shulkers and bundles.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> locationMinHighlightDistance = sgLocation.add(new IntSetting.Builder()
		.name("min-highlight-distance")
		.description("Do not tint coordinates closer than this (horizontal).")
		.defaultValue(0)
		.min(0)
		.max(3000000)
		.sliderRange(0, 100000)
		.visible(locationHighlight::get)
		.build()
	);

	private final Setting<Boolean> shulkerTooltip = sgStorage.add(new BoolSetting.Builder()
		.name("shulker-tooltip")
		.description("Shulker box inventory tooltips.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> enderTooltip = sgStorage.add(new BoolSetting.Builder()
		.name("ender-tooltip")
		.description("Ender chest tooltips from the last time you opened it (kept per account and server, across restarts).")
		.defaultValue(true)
		.build()
	);

	private final Setting<StorageStyle> shulkerStyle = sgStorage.add(new EnumSetting.Builder<StorageStyle>()
		.name("shulker-style")
		.description("Preview = slot grid; List = compact item lines with identical items merged. Both in the tinted shulker frame.")
		.defaultValue(StorageStyle.Preview)
		.visible(shulkerTooltip::get)
		.build()
	);

	private final Setting<Keybind> styleKey = sgStorage.add(new KeybindSetting.Builder()
		.name("style-key")
		.description("Press over a shulker or ender chest to switch its tooltip between Preview and List (the choice is kept).")
		.defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_LEFT_ALT))
		.build()
	);

	private final Setting<Boolean> storageColored = sgStorage.add(new BoolSetting.Builder()
		.name("colored-tooltip")
		.description("Tint the shulker tooltip with the box's dye colour, and the ender chest tooltip in ender chest teal. Off: plain vanilla grey.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> storageColoredScreen = sgStorage.add(new BoolSetting.Builder()
		.name("colored-shulker-screen")
		.description("Tint an open shulker box's screen with the box's dye colour, and the ender chest screen in ender chest teal, like the colored tooltip.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> mapTooltip = sgMap.add(new BoolSetting.Builder()
		.name("map-tooltip")
		.description("Large mapart parchment when hovering a filled map.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Double> mapScale = sgMap.add(new DoubleSetting.Builder()
		.name("map-scale")
		.description("Size multiplier for the hover mapart preview.")
		.defaultValue(1.0)
		.min(0.5)
		.sliderRange(0.5, 2.0)
		.visible(mapTooltip::get)
		.build()
	);

	private final Setting<Boolean> bookPreview = sgBook.add(new BoolSetting.Builder()
		.name("book-preview")
		.description("Mini open-book tooltip; scroll to change pages.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> bannerPreview = sgBanner.add(new BoolSetting.Builder()
		.name("banner-preview")
		.description("Banner/shield preview beside the tooltip.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> bannerShowComponents = sgBanner.add(new BoolSetting.Builder()
		.name("show-components")
		.description("Colored pattern name list top-right of the banner.")
		.defaultValue(true)
		.visible(bannerPreview::get)
		.build()
	);

	private final Setting<Boolean> bannerCopyCommand = sgBanner.add(new BoolSetting.Builder()
		.name("copy-command")
		.description("Press the bundle-lock key (Left Alt) over a banner or shield to copy its /give command, ready to paste into Auto Loom.")
		.defaultValue(true)
		.visible(bannerPreview::get)
		.build()
	);

	private final Setting<Boolean> bannerShowIngredients = sgBanner.add(new BoolSetting.Builder()
		.name("show-ingredients")
		.description("Dye and pattern-item slot grid under the names.")
		.defaultValue(true)
		.visible(bannerPreview::get)
		.build()
	);

	private final ItemStack[] storageItems = new ItemStack[27];
	private ItemStack hoverStack;
	private boolean copiedThisHover;
	private ItemStack bannerHoverStack;
	private boolean bannerCopied;
	/** The style key switched once and is still held. */
	private boolean styleKeyHeld;
	private int highlightCacheTick = Integer.MIN_VALUE;
	private final Map<ItemStack, Boolean> highlightCache = new IdentityHashMap<>();

	private int bookPage;
	private ItemStack bookStack;
	private final Scroller bundleScroller = new Scroller();
	/** Pinned bundle tooltip: the bundle's slot and the tooltip's frozen origin. */
	private Slot pinnedSlot;
	private int pinnedX;
	private int pinnedY;
	private GcBundleTooltipComponent pinnedTip;
	/** Storage preview set at HIGHEST, re-applied at LOWEST if Meteor BetterTooltips swapped in its own. */
	private ItemStack storageTipStack;
	private GcStorageTooltipComponent storageTip;

	public TooltipPlus() {
		super(GreenCorps.CATEGORY, "tooltip-plus",
			"Bundle, storage, book, banner, map, location tooltips and item file size.");
	}

	public static TooltipPlus get() {
		return Modules.get().get(TooltipPlus.class);
	}

	public double mapTooltipScale() {
		return mapScale.get();
	}

	public boolean mapTooltipEnabled() {
		return isActive() && mapTooltip.get();
	}

	/** Packed ARGB for Location highlight (used by DrawContext mixin). */
	public int highlightColor(ItemStack stack) {
		if (!isActive() || !locationHighlight.get() || stack == null || stack.isEmpty()) return 0;
		if (mc.currentScreen == null && !locationHighlightHotbar.get()) return 0;
		if (!ItemLocations.mayCarry(stack)) return 0;
		if (!cachedHighlight(stack)) return 0;
		return locationColor.get().getPacked();
	}

	/** @return true if scroll was consumed */
	public boolean onInventoryScroll(double horizontal, double vertical) {
		if (!isActive() || mc.player == null) return false;
		Slot slot = pinnedSlot != null ? pinnedSlot : focusedSlot();
		if (slot == null) return false;
		ItemStack focused = slot.getStack();

		if (bundlePreview.get() && hasBundleStacks(focused)) {
			// Vanilla's BundleTooltipSubmenuHandler, but cycling every stack instead of the first 12.
			int n = filledBundleStacks(focused).size();
			Vector2i step = bundleScroller.update(horizontal, vertical);
			int delta = step.y == 0 ? -step.x : step.y;
			if (delta != 0) {
				int current = BundleItem.getSelectedStackIndex(focused);
				int next = Scroller.scrollCycling(delta, current, n);
				if (next != current) selectBundleStack(slot, next);
			}
			return true;
		}

		if (bookPreview.get() && isBook(focused)) {
			List<Text> pages = bookPages(focused);
			if (pages.isEmpty()) return false;
			if (focused != bookStack) {
				bookStack = focused;
				bookPage = 0;
			}
			int delta = vertical > 0 ? -1 : 1;
			bookPage = Math.floorMod(bookPage + delta, pages.size());
			return true;
		}
		return false;
	}

	/** Bundle lock key / Esc in an inventory screen. @return true if consumed */
	public boolean onInventoryKey(int keyCode, int modifiers) {
		if (pinnedSlot != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
			unpinBundle();
			return true;
		}
		if (styleKey.get().matches(true, keyCode, modifiers) && toggleStorageStyle()) return true;
		return bundleLock.get().matches(true, keyCode, modifiers) && toggleBundlePin();
	}

	/** Over a shulker / ender chest: Preview ⇄ List. Holding the key switches once (key repeat is ignored). */
	private boolean toggleStorageStyle() {
		if (!isActive()) return false;
		ItemStack stack = focusedStack();
		if (stack == null || stack.isEmpty()) return false;
		boolean storage = (shulkerTooltip.get() && isShulker(stack)) || (enderTooltip.get() && stack.isOf(Items.ENDER_CHEST));
		if (!storage) return false;
		if (!styleKeyHeld) {
			shulkerStyle.set(shulkerStyle.get() == StorageStyle.Preview ? StorageStyle.List : StorageStyle.Preview);
			styleKeyHeld = true;
		}
		return true;
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		if (styleKeyHeld && !styleKey.get().isPressed()) styleKeyHeld = false;
	}

	/** Mouse click in an inventory screen. @return true if consumed */
	public boolean onInventoryClick(double mouseX, double mouseY, int button) {
		if (styleKey.get().matches(false, button, 0) && toggleStorageStyle()) return true;
		if (bundleLock.get().matches(false, button, 0) && toggleBundlePin()) return true;
		if (pinnedSlot == null || pinnedTip == null || !insidePinned(mouseX, mouseY)) return false;
		int index = pinnedTip.cellAt(mouseX, mouseY);
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && index >= 0
			&& mc.player != null && mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
			takeFromBundle(pinnedSlot, index);
		}
		// Clicks on the pinned panel never reach the slots underneath it.
		return true;
	}

	public boolean isBundlePinned() {
		return pinnedSlot != null;
	}

	public void onInventoryClosed() {
		unpinBundle();
	}

	/** Pinned bundle tooltip, drawn after the screen (vanilla tooltips are hidden while pinned). */
	public void renderPinnedBundle(DrawContext context, int mouseX, int mouseY) {
		if (pinnedSlot == null) return;
		ItemStack bundle = pinnedSlot.getStack();
		if (!isActive() || !bundlePreview.get() || !hasBundleStacks(bundle)) {
			unpinBundle();
			return;
		}
		GcBundleTooltipComponent tip = bundleTip(bundle);
		tip.setPinned(true);
		tip.setHoveredIndex(pinnedTip == null ? -1 : pinnedTip.cellAt(mouseX, mouseY));
		pinnedTip = tip;
		// Drawn like the hover tooltip: the same title lines above the grid, at the spot it was pinned from.
		List<Text> title = Screen.getTooltipFromItem(mc, bundle);
		int titleH = titleHeight(title);
		int w = Math.max(tip.getWidth(mc.textRenderer), titleWidth(title));
		int h = tip.getHeight(mc.textRenderer);
		int top = pinnedY - titleH;
		MatrixStack matrices = context.getMatrices();
		matrices.push();
		TooltipBackgroundRenderer.render(context, pinnedX, top, w, titleH + h, 400, bundle.get(DataComponentTypes.TOOLTIP_STYLE));
		matrices.translate(0f, 0f, 400f);
		drawTitle(context, title, pinnedX, top);
		tip.drawItems(mc.textRenderer, pinnedX, pinnedY, w, h, context);
		matrices.pop();
		if (bundleMapPreview.get()) drawMapBeside(context, tip.stack(tip.focusedIndex()), pinnedX, top, w, titleH + h, 400);
	}

	/** Called from the hover bundle tooltip: the selected stack's map preview at its top corner (mode Locked and hover). */
	public void drawHoverBundleMap(DrawContext context, GcBundleTooltipComponent tip, int x, int y, int width, int height) {
		if (!isActive() || !bundlePreview.get() || !bundleMapPreview.get() || bundleMapMode.get() != BundleMapMode.BOTH) return;
		int titleH = titleHeight(Screen.getTooltipFromItem(mc, tip.bundle()));
		drawMapBeside(context, tip.stack(tip.focusedIndex()), x, y - titleH, width, titleH + height, 0);
	}

	/** Space between the bundle tooltip and the map preview: their borders touch at the corner. */
	private static final int GROUP_GAP = 9;
	/** Vanilla tooltip text lines: 10 px each, 2 px more under the first one. */
	private static final int TITLE_LINE_H = 10;

	private static int titleHeight(List<Text> lines) {
		return lines.isEmpty() ? 0 : lines.size() * TITLE_LINE_H + 2;
	}

	private int titleWidth(List<Text> lines) {
		int w = 0;
		for (Text line : lines) w = Math.max(w, mc.textRenderer.getWidth(line));
		return w;
	}

	private void drawTitle(DrawContext context, List<Text> lines, int x, int y) {
		for (int i = 0; i < lines.size(); i++) {
			context.drawTextWithShadow(mc.textRenderer, lines.get(i), x, y + i * TITLE_LINE_H + (i > 0 ? 2 : 0), 0xFFFFFFFF);
		}
	}

	/** {@code stack}'s map tooltip right of a tooltip at x/y (left of it when there's no room); z on top of the current. */
	private void drawMapBeside(DrawContext context, ItemStack stack, int tipX, int tipY, int tipW, int tipH, int z) {
		if (stack == null || !stack.isOf(Items.FILLED_MAP)) return;
		GcMapTooltipComponent map = new GcMapTooltipComponent(stack, bundleMapScale.get());
		int w = map.getWidth(mc.textRenderer);
		int h = map.getHeight(mc.textRenderer);
		// At the bundle tooltip's top-right corner, else its top-left, else under it on a narrow screen.
		int screenW = context.getScaledWindowWidth();
		int screenH = context.getScaledWindowHeight();
		int x = tipX + tipW + GROUP_GAP;
		int y = tipY;
		if (x + w + 4 > screenW) x = tipX - w - GROUP_GAP;
		if (x < 4) {
			x = Math.max(4, Math.min(tipX, screenW - w - 4));
			y = tipY + tipH + GROUP_GAP;
		}
		y = Math.max(4, Math.min(y, screenH - h - 4));
		MatrixStack matrices = context.getMatrices();
		matrices.push();
		TooltipBackgroundRenderer.render(context, x, y, w, h, z, null);
		matrices.translate(0f, 0f, z);
		map.drawItems(mc.textRenderer, x, y, w, h, context);
		matrices.pop();
	}

	private boolean toggleBundlePin() {
		if (!isActive() || !bundlePreview.get()) return false;
		if (pinnedSlot != null) {
			unpinBundle();
			return true;
		}
		Slot slot = focusedSlot();
		if (slot == null || !hasBundleStacks(slot.getStack())) return false;
		GcBundleTooltipComponent shown = GcBundleTooltipComponent.lastDrawn();
		if (shown == null || shown.bundle() != slot.getStack()) return false;
		pinnedSlot = slot;
		pinnedX = shown.originX();
		pinnedY = shown.originY();
		pinnedTip = null;
		return true;
	}

	private void unpinBundle() {
		pinnedSlot = null;
		pinnedTip = null;
	}

	private boolean insidePinned(double mouseX, double mouseY) {
		int w = pinnedTip.getWidth(mc.textRenderer);
		int h = pinnedTip.getHeight(mc.textRenderer);
		return mouseX >= pinnedX - 4 && mouseX < pinnedX + w + 4 && mouseY >= pinnedY - 4 && mouseY < pinnedY + h + 4;
	}

	/** Select {@code index} and pull it onto the cursor. */
	private void takeFromBundle(Slot slot, int index) {
		if (mc.interactionManager == null || mc.player == null) return;
		selectBundleStack(slot, index);
		mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot.id, 1, SlotActionType.PICKUP, mc.player);
	}

	/** Selects on the client and the server (in the server's index order, see {@link BundleSelect}). */
	private void selectBundleStack(Slot slot, int index) {
		BundleSelect.select(mc, slot.id, slot.getStack(), index);
	}

	/** Tooltip+ bundle tooltip for {@code stack}, or null for vanilla's (called from the BundleItem mixin). */
	public GcBundleTooltipComponent bundleTooltip(ItemStack stack) {
		if (!isActive() || !bundlePreview.get() || !hasBundleStacks(stack)) return null;
		return bundleTip(stack);
	}

	private GcBundleTooltipComponent bundleTip(ItemStack bundle) {
		BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		float occupancy = contents == null ? 0f : contents.getOccupancy().floatValue();
		return new GcBundleTooltipComponent(bundle, filledBundleStacks(bundle), occupancy, BundleItem.getSelectedStackIndex(bundle));
	}

	private static boolean hasBundleStacks(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
		return contents != null && !contents.isEmpty();
	}

	private Slot focusedSlot() {
		if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return null;
		try {
			return ((dev.greencorps.mixin.HandledScreenAccessor) hs).greencorps$getFocusedSlot();
		} catch (Throwable t) {
			return null;
		}
	}

	private ItemStack focusedStack() {
		Slot s = focusedSlot();
		return s == null ? null : s.getStack();
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	private void onTooltipData(TooltipDataEvent event) {
		if (!isActive()) return;
		ItemStack stack = event.itemStack;
		if (stack == null || stack.isEmpty()) return;

		if (mapTooltip.get() && stack.isOf(Items.FILLED_MAP)) {
			event.tooltipData = new GcMapTooltipComponent(stack);
			return;
		}


		// Storage frames show the size bare in their footer, next to the fill.
		String sizeLine = itemFileSize.get() ? (ItemStackBytes.estimate(stack) + " bytes") : "";

		if (shulkerTooltip.get() && isShulker(stack)) {
			if (!Utils.hasItems(stack)) {
				// Empty box: the tinted frame with just its title strip and the size line.
				setStorageTip(event, stack, GcStorageTooltipComponent.empty(
					GcStorageTooltipComponent.Palette.tinted(storageTint(stack)), stack.getName().getString(), sizeLine));
				return;
			}
			Utils.getItemsInContainerItem(stack, storageItems);
			setStorageTip(event, stack, new GcStorageTooltipComponent(
				storageItems.clone(),
				GcStorageTooltipComponent.Palette.tinted(storageTint(stack)),
				stack.getName().getString(),
				sizeLine,
				"",
				shulkerStyle.get() == StorageStyle.List
			));
			return;
		}

		if (enderTooltip.get() && stack.isOf(Items.ENDER_CHEST)) {
			ItemStack[] ender = EnderChestCache.items();
			setStorageTip(event, stack, new GcStorageTooltipComponent(
				ender == null ? null : ender.clone(),
				storageColored.get() ? GcStorageTooltipComponent.Palette.ENDER : GcStorageTooltipComponent.Palette.tinted(PLAIN_TINT),
				stack.getName().getString(),
				sizeLine,
				"Open your ender chest once",
				shulkerStyle.get() == StorageStyle.List
			));
			return;
		}

		if (bookPreview.get() && isBook(stack)) {
			List<Text> pages = bookPages(stack);
			if (!pages.isEmpty()) {
				if (stack != bookStack) {
					bookStack = stack;
					bookPage = 0;
				}
				int idx = Math.floorMod(bookPage, pages.size());
				event.tooltipData = new GcBookTooltipComponent(pages.get(idx), idx, pages.size());
				return;
			}
		}

		if (bannerPreview.get()) {
			if (stack.getItem() instanceof BannerItem banner) {
				BannerPatternsComponent patterns = stack.getOrDefault(DataComponentTypes.BANNER_PATTERNS, BannerPatternsComponent.DEFAULT);
				event.tooltipData = new GcBannerPlusTooltipComponent(
					banner.getColor(), patterns, stack,
					bannerShowComponents.get(), bannerShowIngredients.get(), itemFileSize.get()
				);
				return;
			}
			if (stack.getItem() instanceof ShieldItem) {
				BannerPatternsComponent patterns = stack.getOrDefault(DataComponentTypes.BANNER_PATTERNS, BannerPatternsComponent.DEFAULT);
				DyeColor base = stack.get(DataComponentTypes.BASE_COLOR);
				if (base == null) base = DyeColor.WHITE;
				event.tooltipData = new GcBannerPlusTooltipComponent(
					base, patterns, stack,
					bannerShowComponents.get(), bannerShowIngredients.get(), itemFileSize.get()
				);
			}
		}
	}

	private void setStorageTip(TooltipDataEvent event, ItemStack stack, GcStorageTooltipComponent tip) {
		storageTipStack = stack;
		storageTip = tip;
		event.tooltipData = tip;
	}

	/** Meteor BetterTooltips (Alt held) replaces shulker / ender data after us; keep ours. */
	@EventHandler(priority = EventPriority.LOWEST)
	private void onTooltipDataLate(TooltipDataEvent event) {
		if (!isActive() || storageTip == null) return;
		if (event.itemStack == storageTipStack && event.tooltipData != storageTip) event.tooltipData = storageTip;
	}

	@EventHandler
	private void onTooltipText(ItemStackTooltipEvent event) {
		if (!isActive()) return;
		ItemStack stack = event.itemStack();

		if (bannerPreview.get() && (stack.getItem() instanceof BannerItem || stack.getItem() instanceof ShieldItem)) {
			stripVanillaBannerPatterns(stack, event.list());
			if (bannerCopyCommand.get()) appendBannerCopy(event, stack);
		}

		if (locationTooltip.get() && ItemLocations.mayCarry(stack)) {
			List<String> lines = ItemLocations.lines(stack, mapCenters(), locationOptions(), locationView());
			if (!lines.isEmpty()) {
				SettingColor c = locationColor.get();
				int rgb = ((c.r & 0xFF) << 16) | ((c.g & 0xFF) << 8) | (c.b & 0xFF);
				var style = net.minecraft.text.Style.EMPTY.withColor(net.minecraft.text.TextColor.fromRgb(rgb));
				event.appendEnd(Text.empty());
				for (String line : lines) {
					event.appendEnd(Text.literal(line).setStyle(style));
				}
				if (stack != hoverStack) {
					hoverStack = stack;
					copiedThisHover = false;
				}
				if (bundleLock.get().isPressed()) {
					if (!copiedThisHover && mc.keyboard != null) {
						mc.keyboard.setClipboard(String.join("\n", lines));
						copiedThisHover = true;
					}
					event.appendEnd(Text.literal("Copied").formatted(Formatting.GRAY));
				} else {
					copiedThisHover = false;
					event.appendEnd(Text.literal("Hold lock key to copy").formatted(Formatting.GRAY));
				}
			}
		}

		// Size for banners/storage is drawn inside their components.
		boolean sizeInComponent = (bannerPreview.get() && (stack.getItem() instanceof BannerItem || stack.getItem() instanceof ShieldItem))
			|| (shulkerTooltip.get() && isShulker(stack))
			|| (enderTooltip.get() && stack.isOf(Items.ENDER_CHEST));
		if (itemFileSize.get() && !sizeInComponent) {
			int bytes = ItemStackBytes.estimate(stack);
			event.appendEnd(Text.literal("Size: " + bytes + " bytes").formatted(Formatting.DARK_GRAY));
		}
	}

	/** Lock key over a banner: copy its /give command once per hover (Auto Loom parses it). */
	private void appendBannerCopy(ItemStackTooltipEvent event, ItemStack stack) {
		String command = BannerGiveCommand.of(stack);
		if (command == null) return;
		if (stack != bannerHoverStack) {
			bannerHoverStack = stack;
			bannerCopied = false;
		}
		if (bundleLock.get().isPressed()) {
			if (!bannerCopied && mc.keyboard != null) {
				mc.keyboard.setClipboard(command);
				bannerCopied = true;
			}
			event.appendEnd(Text.literal("Give command copied").formatted(Formatting.GRAY));
		} else {
			bannerCopied = false;
			event.appendEnd(Text.literal("Press " + bundleLock.get() + " to copy the give command").formatted(Formatting.GRAY));
		}
	}

	/** The banner preview lists every layer in colour, so drop vanilla's grey layer lines (one per pattern). */
	private static void stripVanillaBannerPatterns(ItemStack stack, List<Text> list) {
		BannerPatternsComponent patterns = stack.get(DataComponentTypes.BANNER_PATTERNS);
		if (list == null || patterns == null) return;
		List<String> layerLines = new ArrayList<>();
		for (BannerPatternsComponent.Layer layer : patterns.layers()) layerLines.add(layer.getTooltipText().getString());
		Iterator<Text> it = list.iterator();
		boolean first = true;
		while (it.hasNext() && !layerLines.isEmpty()) {
			Text line = it.next();
			if (first) {
				first = false; // item name
				continue;
			}
			if (layerLines.remove(line.getString())) it.remove();
		}
	}

	private static boolean isShulker(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem bi)) return false;
		return bi.getBlock() instanceof ShulkerBoxBlock;
	}

	/** White = plain vanilla grey frame. */
	private static final int PLAIN_TINT = 0xFFFFFFFF;
	/** Undyed shulkers are purple. */
	private static final int UNDYED_SHULKER_TINT = 0xFFB07BC8;

	/** Colour the vanilla frame greys are multiplied with (ender chests use their own palette). */
	private int storageTint(ItemStack stack) {
		if (!storageColored.get()) return PLAIN_TINT;
		if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock shulker) {
			return shulkerTint(shulker.getColor());
		}
		return PLAIN_TINT;
	}

	/** Dye colour of a shulker box (purple when undyed) as a multiply tint. */
	public static int shulkerTint(DyeColor dye) {
		if (dye == null) return UNDYED_SHULKER_TINT;
		return 0xFF000000 | (dye.getEntityColor() & 0xFFFFFF);
	}

	/** Multiply tint that turns the vanilla grey container texture into ender chest teal (body ≈ #1C2E2C). */
	private static final int ENDER_SCREEN_TINT = 0xFF243B38;

	/** Tint for an open ender chest screen, or 0 to leave it vanilla. */
	public int enderScreenTint() {
		return isActive() && storageColoredScreen.get() ? ENDER_SCREEN_TINT : 0;
	}

	/** Tint for an open shulker box screen, or 0 to leave it vanilla. */
	public int shulkerScreenTint(DyeColor dye) {
		if (!isActive() || !storageColoredScreen.get()) return 0;
		return shulkerTint(dye);
	}

	private static List<ItemStack> filledBundleStacks(ItemStack bundle) {
		List<ItemStack> out = new ArrayList<>();
		BundleContentsComponent c = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		if (c == null) return out;
		for (ItemStack s : c.iterate()) {
			if (s != null && !s.isEmpty()) out.add(s);
		}
		return out;
	}

	private static boolean isBook(ItemStack stack) {
		return stack.contains(DataComponentTypes.WRITTEN_BOOK_CONTENT)
			|| stack.contains(DataComponentTypes.WRITABLE_BOOK_CONTENT);
	}

	private static List<Text> bookPages(ItemStack stack) {
		List<Text> pages = new ArrayList<>();
		WrittenBookContentComponent written = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
		if (written != null) {
			for (RawFilteredPair<Text> page : written.pages()) {
				pages.add(page.get(false));
			}
			return pages;
		}
		WritableBookContentComponent writable = stack.get(DataComponentTypes.WRITABLE_BOOK_CONTENT);
		if (writable != null) {
			for (RawFilteredPair<String> page : writable.pages()) {
				pages.add(Text.literal(page.get(false)));
			}
		}
		return pages;
	}

	private boolean cachedHighlight(ItemStack stack) {
		int tick = mc.player != null ? mc.player.age : -1;
		if (tick != highlightCacheTick) {
			highlightCacheTick = tick;
			highlightCache.clear();
		}
		Boolean hit = highlightCache.get(stack);
		if (hit != null) return hit;
		boolean value = ItemLocations.hasHighlight(stack, mapCenters(), locationOptions(), locationView());
		highlightCache.put(stack, value);
		return value;
	}

	private ItemLocations.Options locationOptions() {
		return new ItemLocations.Options(
			locationNested.get(),
			locationMaxLines.get(),
			locationShowDistance.get(),
			locationShowOrigin.get(),
			locationMinHighlightDistance.get()
		);
	}

	private ItemLocations.View locationView() {
		if (mc.player == null || mc.world == null) return null;
		return new ItemLocations.View(
			mc.world.getRegistryKey().getValue().toString(),
			mc.player.getX(),
			mc.player.getY(),
			mc.player.getZ()
		);
	}

	/** Reflection so shareable jars can omit MapDuperPlus without breaking Tooltip+. */
	private static MapCenterCache mapCenters() {
		try {
			Module m = Modules.get().get("map-duper-plus");
			if (m == null) return null;
			Object cache = m.getClass().getMethod("centers").invoke(m);
			return cache instanceof MapCenterCache c ? c : null;
		} catch (Throwable t) {
			return null;
		}
	}
}
