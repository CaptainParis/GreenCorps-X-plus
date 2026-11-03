package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.function.Predicate;

/**
 * Hides item frames that hold a map or an item (whole frame or only the contents) and can
 * replace empty frames with an ESP box. Hidden frames, and filled frames on storage blocks,
 * are skipped by the crosshair so they can't be rotated or emptied by accident. The render
 * and crosshair mixins ask {@link #actionFor(ItemFrameEntity)} and {@link #skipIgnoredFrames};
 * the decisions themselves are {@link #decide} and {@link #passesThrough} (unit-tested).
 */
public class ItemFrameControl extends Module {
	public enum Content {
		EMPTY,
		MAP,
		ITEM
	}

	public enum Action {
		VANILLA,
		/** Map / item drawn as in an invisible frame: no border, contents flush with the wall. */
		HIDE_FRAME,
		HIDE,
		HIDE_CONTENTS,
		ESP,
		ESP_AND_VANILLA;

		public boolean hidesFrame() {
			return this == HIDE_FRAME;
		}

		/** The vanilla frame model is not drawn at all. */
		public boolean skipsVanilla() {
			return this == HIDE || this == ESP;
		}

		/** The map or item is not visible, so the crosshair may pass through. */
		public boolean hidesContents() {
			return this == HIDE || this == HIDE_CONTENTS;
		}

		public boolean drawsEsp() {
			return this == ESP || this == ESP_AND_VANILLA;
		}
	}

	public enum FrameLook {
		HIDE_FRAME("Hide frame"),
		VANILLA("Vanilla");

		private final String label;

		FrameLook(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public enum HideMode {
		WHOLE_FRAME("Whole frame"),
		CONTENTS_ONLY("Contents only");

		private final String label;

		HideMode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public enum EmptyStyle {
		VANILLA("Vanilla"),
		ESP("ESP"),
		ESP_AND_VANILLA("ESP + vanilla");

		private final String label;

		EmptyStyle(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** Snapshot of the settings {@link #decide} needs. */
	record Rules(FrameLook frameLook, boolean noRenderMaps, boolean noRenderItems, HideMode noRenderMode,
				 EmptyStyle emptyStyle, boolean includeInvisible) {
	}

	/** Keeps the ESP box off the wall the frame hangs on. */
	private static final double Z_FIGHT = 0.002;

	/** Hot path: the mixins ask once per frame for every item frame in the world. */
	private static ItemFrameControl instance;

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgNoRender = settings.createGroup("No Render");
	private final SettingGroup sgStorage = settings.createGroup("Storage Frames");
	private final SettingGroup sgEmpty = settings.createGroup("Empty Frames");

	private final Setting<FrameLook> filledFrames = sgGeneral.add(new EnumSetting.Builder<FrameLook>()
		.name("filled-frames")
		.description("Frames that hold a map or an item: Hide frame drops the frame border (map and item stay, like an invisible frame); Vanilla leaves them alone.")
		.defaultValue(FrameLook.HIDE_FRAME)
		.build()
	);

	private final Setting<Boolean> clickThrough = sgGeneral.add(new BoolSetting.Builder()
		.name("click-through")
		.description("Your crosshair ignores frames whose border or contents are hidden, so you mine and place behind them and never rotate or pop one by accident.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Boolean> sneakToEdit = sgGeneral.add(new BoolSetting.Builder()
		.name("sneak-to-edit")
		.description("Hold sneak to target clicked-through frames normally, to rotate them or take the item out.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> regularFrames = sgGeneral.add(new BoolSetting.Builder()
		.name("regular-frames")
		.description("Apply to normal item frames.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> glowFrames = sgGeneral.add(new BoolSetting.Builder()
		.name("glow-frames")
		.description("Apply to glow item frames.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> noRenderMaps = sgNoRender.add(new BoolSetting.Builder()
		.name("no-render-maps")
		.description("Don't draw frames that hold a filled map (mapart) at all.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Boolean> noRenderItems = sgNoRender.add(new BoolSetting.Builder()
		.name("no-render-items")
		.description("Don't draw frames that hold any other item (empty maps count as items).")
		.defaultValue(false)
		.build()
	);

	private final Setting<HideMode> noRenderMode = sgNoRender.add(new EnumSetting.Builder<HideMode>()
		.name("no-render-mode")
		.description("Whole frame hides frame and contents; Contents only keeps the frame and makes it look empty.")
		.defaultValue(HideMode.WHOLE_FRAME)
		.visible(this::noRendersAnything)
		.build()
	);

	private final Setting<Boolean> storageClickThrough = sgStorage.add(new BoolSetting.Builder()
		.name("storage-click-through")
		.description("Clicks pass through filled frames on storage blocks, so you open the container and never rotate or empty the frame.")
		.defaultValue(true)
		.build()
	);

	private final Setting<List<Block>> storageBlocks = sgStorage.add(new BlockListSetting.Builder()
		.name("storage-blocks")
		.description("Blocks whose filled frames are clicked through.")
		.defaultValue(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.CRAFTER, Blocks.DROPPER, Blocks.DISPENSER)
		.visible(storageClickThrough::get)
		.build()
	);

	private final Setting<EmptyStyle> emptyStyle = sgEmpty.add(new EnumSetting.Builder<EmptyStyle>()
		.name("style")
		.description("How empty frames are drawn: the vanilla model, an ESP box instead, or both.")
		.defaultValue(EmptyStyle.ESP)
		.build()
	);

	private final Setting<ShapeMode> shapeMode = sgEmpty.add(new EnumSetting.Builder<ShapeMode>()
		.name("shape-mode")
		.description("How the ESP box is drawn.")
		.defaultValue(ShapeMode.Both)
		.visible(this::espOn)
		.build()
	);

	private final Setting<SettingColor> color = sgEmpty.add(new ColorSetting.Builder()
		.name("color")
		.description("ESP color for normal frames. Its alpha is the line opacity.")
		.defaultValue(new SettingColor(255, 176, 32, 255))
		.visible(this::espOn)
		.build()
	);

	private final Setting<SettingColor> glowColor = sgEmpty.add(new ColorSetting.Builder()
		.name("glow-color")
		.description("ESP color for glow item frames. Its alpha is the line opacity.")
		.defaultValue(new SettingColor(64, 224, 208, 255))
		.visible(this::espOn)
		.build()
	);

	private final Setting<Integer> fillOpacity = sgEmpty.add(new IntSetting.Builder()
		.name("fill-opacity")
		.description("Opacity of the ESP fill.")
		.defaultValue(40)
		.range(0, 255)
		.sliderMax(255)
		.visible(this::espOn)
		.build()
	);

	private final Setting<Boolean> includeInvisible = sgEmpty.add(new BoolSetting.Builder()
		.name("include-invisible")
		.description("Also draw ESP boxes for empty frames that are invisible.")
		.defaultValue(true)
		.visible(this::espOn)
		.build()
	);

	private final Color lineColor = new Color();
	private final Color sideColor = new Color();

	private Rules rules = snapshot();
	private int hiddenCount;

	public ItemFrameControl() {
		super(GreenCorps.CATEGORY, "item-frame-control",
			"Drops the border of filled item frames and marks empty ones with ESP boxes.");
		instance = this;
	}

	public static ItemFrameControl get() {
		return Modules.get().get(ItemFrameControl.class);
	}

	/** What to do with this frame right now; {@link Action#VANILLA} while the module is off. */
	public static Action actionFor(ItemFrameEntity frame) {
		ItemFrameControl mod = instance;
		if (mod == null || !mod.isActive()) return Action.VANILLA;
		return mod.action(frame);
	}

	/** Wraps a crosshair entity filter so it skips hidden frames and filled frames on storage blocks. */
	public static Predicate<Entity> skipIgnoredFrames(Predicate<Entity> filter) {
		ItemFrameControl mod = instance;
		if (mod == null || !mod.isActive() || !(mod.clickThrough.get() || mod.storageClickThrough.get())) return filter;
		return filter.and(entity -> !(entity instanceof ItemFrameEntity frame && mod.ignoresClicks(frame)));
	}

	@Override
	public void onActivate() {
		rules = snapshot();
		hiddenCount = 0;
	}

	@Override
	public String getInfoString() {
		return hiddenCount > 0 ? hiddenCount + " hidden" : null;
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		rules = snapshot();
		int hidden = 0;
		if (mc.world != null) {
			for (Entity entity : mc.world.getEntities()) {
				if (entity instanceof ItemFrameEntity frame && (action(frame).hidesContents() || action(frame).hidesFrame())) hidden++;
			}
		}
		hiddenCount = hidden;
	}

	@EventHandler
	private void onRender3D(Render3DEvent event) {
		if (mc.world == null || rules.emptyStyle() == EmptyStyle.VANILLA) return;
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof ItemFrameEntity frame) || !action(frame).drawsEsp()) continue;
			SettingColor base = isGlow(frame) ? glowColor.get() : color.get();
			lineColor.set(base);
			sideColor.set(base);
			sideColor.a = fillOpacity.get();
			event.renderer.box(frame.getBoundingBox().expand(Z_FIGHT), sideColor, lineColor, shapeMode.get(), 0);
		}
	}

	private Action action(ItemFrameEntity frame) {
		if (mc.player == null) return Action.VANILLA;
		Content content = frame.getHeldItemStack().isEmpty() ? Content.EMPTY
			: frame.containsMap() ? Content.MAP : Content.ITEM;
		return decide(content, typeEnabled(frame), frame.isInvisible(), rules);
	}

	private boolean ignoresClicks(ItemFrameEntity frame) {
		boolean storageFrame = storageClickThrough.get() && !frame.getHeldItemStack().isEmpty()
			&& typeEnabled(frame) && onStorageBlock(frame);
		boolean editing = sneakToEdit.get() && mc.player != null && mc.player.isSneaking();
		return passesThrough(action(frame), clickThrough.get(), storageFrame, editing);
	}

	/** Frames with a hidden border or contents follow click-through, filled frames on storage pass; sneaking edits. */
	static boolean passesThrough(Action action, boolean clickThrough, boolean storageFrame, boolean editing) {
		if (editing) return false;
		if (clickThrough && (action.hidesContents() || action.hidesFrame())) return true;
		return storageFrame;
	}

	private boolean onStorageBlock(ItemFrameEntity frame) {
		if (mc.world == null) return false;
		BlockPos behind = frame.getAttachedBlockPos().offset(frame.getHorizontalFacing().getOpposite());
		return storageBlocks.get().contains(mc.world.getBlockState(behind).getBlock());
	}

	private boolean typeEnabled(ItemFrameEntity frame) {
		return isGlow(frame) ? glowFrames.get() : regularFrames.get();
	}

	static Action decide(Content content, boolean typeEnabled, boolean invisible, Rules rules) {
		if (!typeEnabled) return Action.VANILLA;
		if (content == Content.EMPTY) {
			if (rules.emptyStyle() == EmptyStyle.VANILLA) return Action.VANILLA;
			if (invisible && !rules.includeInvisible()) return Action.VANILLA;
			return rules.emptyStyle() == EmptyStyle.ESP ? Action.ESP : Action.ESP_AND_VANILLA;
		}
		boolean noRender = content == Content.MAP ? rules.noRenderMaps() : rules.noRenderItems();
		if (noRender) return rules.noRenderMode() == HideMode.WHOLE_FRAME ? Action.HIDE : Action.HIDE_CONTENTS;
		return rules.frameLook() == FrameLook.HIDE_FRAME ? Action.HIDE_FRAME : Action.VANILLA;
	}

	private static boolean isGlow(ItemFrameEntity frame) {
		return frame.getType() == EntityType.GLOW_ITEM_FRAME;
	}

	private Rules snapshot() {
		return new Rules(filledFrames.get(), noRenderMaps.get(), noRenderItems.get(), noRenderMode.get(),
			emptyStyle.get(), includeInvisible.get());
	}

	private boolean noRendersAnything() {
		return noRenderMaps.get() || noRenderItems.get();
	}

	private boolean espOn() {
		return emptyStyle.get() != EmptyStyle.VANILLA;
	}
}
