package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.gui.WMultilineText;
import dev.greencorps.util.BannerCommandParser;
import dev.greencorps.util.BannerPatternItems;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import java.util.List;
import java.util.Locale;

/**
 * Parses minecraftmaps.com give-commands and guides one-click loom layering.
 */
public class AutoLoom extends Module {
	private final SettingGroup sgGeneral = settings.getDefaultGroup();

	/** Persisted; hidden — edited via multiline {@link #getWidget}. */
	private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
		.name("command")
		.description("Paste a /give banner command from a banner maker (minecraftmaps.com).")
		.defaultValue("")
		.visible(() -> false)
		.build()
	);

	private final Setting<Boolean> showWing = sgGeneral.add(new BoolSetting.Builder()
		.name("show-wing")
		.description("Show the vanilla-style guide wing beside the loom.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> applySound = sgGeneral.add(new BoolSetting.Builder()
		.name("apply-sound")
		.description("Play a soft sound when a loom step is applied.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> clickDelay = sgGeneral.add(new IntSetting.Builder()
		.name("click-delay")
		.description("Ticks to wait between inventory clicks when applying a step.")
		.defaultValue(2)
		.min(1)
		.max(10)
		.sliderRange(1, 8)
		.build()
	);

	private BannerCommandParser.BannerPlan plan = BannerCommandParser.BannerPlan.empty(null);
	private int currentStep;
	private boolean waitingForTake;
	private String status = "Paste a give command.";

	public AutoLoom() {
		super(GreenCorps.CATEGORY, "auto-loom", "Paste a banner give-command; guide one-click loom steps beside the native loom GUI.");
		reparse();
	}

	public static AutoLoom get() {
		return meteordevelopment.meteorclient.systems.modules.Modules.get().get(AutoLoom.class);
	}

	public boolean showWing() {
		return isActive() && showWing.get();
	}

	public BannerCommandParser.BannerPlan plan() {
		return plan;
	}

	public int currentStep() {
		return currentStep;
	}

	public boolean waitingForTake() {
		return waitingForTake;
	}

	public String status() {
		return status;
	}

	public Setting<String> commandSetting() {
		return command;
	}

	@Override
	public WWidget getWidget(GuiTheme theme) {
		WVerticalList list = theme.verticalList();
		list.add(theme.label("Command (multiline paste):"));
		WMultilineText box = list.add(new WMultilineText(command.get(), 5, 65536)).expandX().widget();
		box.action = () -> {
			command.set(box.get());
			reparse();
		};
		var row = list.add(theme.horizontalList()).expandX().widget();
		row.add(theme.button("Load Clipboard")).widget().action = () -> {
			loadClipboard();
			box.set(command.get());
		};
		row.add(theme.button("Reparse")).widget().action = () -> {
			command.set(box.get());
			reparse();
		};
		row.add(theme.button("Reset Steps")).widget().action = this::resetSteps;
		list.add(theme.label(status == null ? "" : status));
		return list;
	}

	public void loadClipboard() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		String clip = mc.keyboard.getClipboard();
		if (clip == null || clip.isBlank()) {
			status = "Clipboard empty.";
			return;
		}
		command.set(clip.trim());
		reparse();
		status = plan.ok ? ("Loaded " + plan.layers.size() + " layers.") : "Parse failed.";
	}

	public void reparse() {
		plan = BannerCommandParser.parse(command.get());
		currentStep = 0;
		waitingForTake = false;
		if (plan.ok) {
			status = "Step 1/" + plan.layers.size() + " ready.";
		} else if (!plan.warnings.isEmpty()) {
			status = plan.warnings.get(0);
		} else {
			status = "Paste a give command.";
		}
	}

	public void resetSteps() {
		currentStep = 0;
		waitingForTake = false;
		status = plan.ok ? ("Step 1/" + plan.layers.size() + " ready.") : status;
	}

	/** Called from the wing each frame while loom is open. */
	public void tickProgress(LoomScreenHandler handler) {
		if (!plan.ok || currentStep >= plan.layers.size()) return;

		Slot out = handler.getOutputSlot();
		if (waitingForTake) {
			if (out != null && out.getStack().isEmpty()) {
				// Player took result; advance if inventory now has matching layer count.
				if (findBannerWithLayers(currentStep + 1) != null || countLayers(handler.getBannerSlot().getStack()) == currentStep + 1) {
					currentStep++;
					waitingForTake = false;
					if (currentStep >= plan.layers.size()) {
						status = "Done — banner complete.";
					} else {
						status = "Step " + (currentStep + 1) + "/" + plan.layers.size() + " ready.";
					}
				}
			}
			return;
		}

		if (out != null && !out.getStack().isEmpty()) {
			waitingForTake = true;
			status = "Take the banner from the result slot.";
		}
	}

	public boolean applyCurrentStep(LoomScreenHandler handler) {
		if (!plan.ok) {
			status = "No valid plan.";
			return false;
		}
		if (currentStep >= plan.layers.size()) {
			status = "Already done.";
			return false;
		}
		if (waitingForTake) {
			status = "Take the result first.";
			return false;
		}

		BannerCommandParser.Layer layer = plan.layers.get(currentStep);
		ItemStack inputBanner = pickInputBanner(handler);
		if (inputBanner == null || inputBanner.isEmpty()) {
			status = currentStep == 0
				? ("Need blank " + plan.base.getName() + " banner.")
				: ("Need banner with " + currentStep + " layer(s).");
			return false;
		}

		Item dye = BannerPatternItems.dyeFor(layer.color);
		FindItemResult dyeSlot = InvUtils.find(dye);
		if (!dyeSlot.found()) {
			status = "Missing " + dye.getName().getString();
			return false;
		}

		if (layer.patternItem != null) {
			FindItemResult pat = InvUtils.find(layer.patternItem);
			Slot patternSlot = handler.getPatternSlot();
			boolean inSlot = patternSlot != null && patternSlot.getStack().isOf(layer.patternItem);
			if (!pat.found() && !inSlot) {
				status = "Missing pattern: " + layer.patternItem.getName().getString();
				return false;
			}
		}

		// Clear wrong inputs (leave matching pattern item).
		clearWrong(handler.getBannerSlot());
		clearWrong(handler.getDyeSlot());
		if (layer.patternItem == null) {
			clearWrong(handler.getPatternSlot());
		} else if (handler.getPatternSlot() != null && !handler.getPatternSlot().getStack().isEmpty()
			&& !handler.getPatternSlot().getStack().isOf(layer.patternItem)) {
			clearWrong(handler.getPatternSlot());
		}

		// Move banner into banner slot if needed.
		if (handler.getBannerSlot().getStack().isEmpty()) {
			FindItemResult bannerFind = findBannerResult(currentStep);
			if (!bannerFind.found()) {
				status = "Could not find input banner.";
				return false;
			}
			InvUtils.move().from(bannerFind.slot()).toId(handler.getBannerSlot().id);
		}

		if (handler.getDyeSlot().getStack().isEmpty()) {
			dyeSlot = InvUtils.find(dye);
			if (!dyeSlot.found()) {
				status = "Missing dye.";
				return false;
			}
			InvUtils.move().from(dyeSlot.slot()).toId(handler.getDyeSlot().id);
		}

		if (layer.patternItem != null && handler.getPatternSlot().getStack().isEmpty()) {
			FindItemResult pat = InvUtils.find(layer.patternItem);
			if (pat.found()) {
				InvUtils.move().from(pat.slot()).toId(handler.getPatternSlot().id);
			}
		}

		int patternIndex = findPatternIndex(handler, layer.patternId);
		if (patternIndex < 0) {
			status = "Pattern not selectable: " + layer.patternId;
			return false;
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.interactionManager == null || mc.player == null) return false;
		mc.interactionManager.clickButton(handler.syncId, patternIndex);

		waitingForTake = true;
		status = "Take the banner from the result slot.";
		if (applySound.get() && mc.world != null) {
			mc.world.playSound(
				mc.player,
				mc.player.getX(), mc.player.getY(), mc.player.getZ(),
				SoundEvents.UI_LOOM_SELECT_PATTERN,
				SoundCategory.PLAYERS,
				0.4f,
				1.0f
			);
		}
		return true;
	}

	private void clearWrong(Slot slot) {
		if (slot == null || slot.getStack().isEmpty()) return;
		InvUtils.shiftClick().slotId(slot.id);
	}

	private ItemStack pickInputBanner(LoomScreenHandler handler) {
		ItemStack inSlot = handler.getBannerSlot().getStack();
		if (!inSlot.isEmpty() && matchesStep(inSlot, currentStep)) return inSlot;
		FindItemResult found = findBannerResult(currentStep);
		if (found.found() && mc.player != null) {
			return mc.player.getInventory().getStack(found.slot());
		}
		return ItemStack.EMPTY;
	}

	private FindItemResult findBannerResult(int layers) {
		return InvUtils.find(stack -> matchesStep(stack, layers));
	}

	private ItemStack findBannerWithLayers(int layers) {
		FindItemResult r = findBannerResult(layers);
		if (!r.found() || mc.player == null) return null;
		return mc.player.getInventory().getStack(r.slot());
	}

	private boolean matchesStep(ItemStack stack, int layers) {
		if (stack == null || stack.isEmpty()) return false;
		if (plan.baseBanner == null || !stack.isOf(plan.baseBanner)) return false;
		int have = countLayers(stack);
		if (layers == 0) return have == 0;
		if (have != layers) return false;
		BannerPatternsComponent comp = stack.get(DataComponentTypes.BANNER_PATTERNS);
		if (comp == null) return layers == 0;
		List<BannerPatternsComponent.Layer> haveLayers = comp.layers();
		for (int i = 0; i < layers; i++) {
			BannerCommandParser.Layer want = plan.layers.get(i);
			BannerPatternsComponent.Layer got = haveLayers.get(i);
			String gotId = got.pattern().getKey().map(k -> k.getValue().getPath()).orElse("");
			if (!gotId.equalsIgnoreCase(want.patternId)) return false;
			if (got.color() != want.color) return false;
		}
		return true;
	}

	private static int countLayers(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return -1;
		BannerPatternsComponent comp = stack.get(DataComponentTypes.BANNER_PATTERNS);
		return comp == null ? 0 : comp.layers().size();
	}

	private int findPatternIndex(LoomScreenHandler handler, String patternId) {
		String want = patternId.toLowerCase(Locale.ROOT);
		if (want.startsWith("minecraft:")) want = want.substring("minecraft:".length());
		List<RegistryEntry<net.minecraft.block.entity.BannerPattern>> list = handler.getBannerPatterns();
		for (int i = 0; i < list.size(); i++) {
			RegistryEntry<net.minecraft.block.entity.BannerPattern> entry = list.get(i);
			String path = entry.getKey().map(k -> k.getValue().getPath()).orElse("");
			if (path.equalsIgnoreCase(want)) return i;
		}
		return -1;
	}
}
