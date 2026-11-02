package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.gui.SignSearchScreen;
import dev.greencorps.mixin.ModuleScreenAccessor;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search sign text and coordinates from the 2b2t.place world download (April-June 2026).
 * The pack lives in {@code config/greencorps/sign-search/} and is not part of the jar.
 */
public class SignSearch extends Module {
	public static final String DESCRIPTION =
		"Search sign text and coordinates from the 2b2t.place world download (April-June 2026). "
			+ "Left click or the module keybind opens the search window. "
			+ "Place overworld.gesign in config/greencorps/sign-search/.";

	public static final String SOURCE = "2b2t.place world download (April-June 2026)";

	private final SettingGroup sgGeneral = settings.getDefaultGroup();

	private final Setting<Integer> resultsHeight = sgGeneral.add(new IntSetting.Builder()
		.name("results-height")
		.description("Height of the search results panel in the SignSearch window (drag the grip under the list).")
		.defaultValue(320)
		.min(120)
		.max(900)
		.sliderRange(120, 700)
		.build()
	);

	private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "greencorps-sign-search");
		t.setDaemon(true);
		return t;
	});

	public SignSearch() {
		super(GreenCorps.CATEGORY, "SignSearch-(WDL)", DESCRIPTION);
	}

	public int resultsHeight() {
		return resultsHeight.get();
	}

	public void setResultsHeight(int px) {
		resultsHeight.set(Math.max(120, Math.min(900, px)));
	}

	public static SignSearch get() {
		return meteordevelopment.meteorclient.systems.modules.Modules.get().get(SignSearch.class);
	}

	public ExecutorService executor() {
		return searchExecutor;
	}

	@Override
	public WWidget getWidget(GuiTheme theme) {
		WVerticalList list = theme.verticalList();
		list.add(theme.label(SOURCE)).expandX();
		list.add(theme.label("Pack: config/greencorps/sign-search/")).expandX();
		WButton open = list.add(theme.button("Open search")).expandX().widget();
		open.action = this::openScreen;
		return list;
	}

	/**
	 * Left click / keybind: turn on if off (no window — safe during Meteor fromTag load),
	 * otherwise open or close the search window. Settings Active checkbox still toggles for real.
	 */
	@Override
	public void toggle() {
		if (isSettingsToggle()) {
			super.toggle();
			return;
		}
		if (!isActive()) {
			super.toggle();
			return;
		}
		toggleSearchWindow();
	}

	public void ensureAlwaysOn() {
		if (!isActive()) super.toggle();
	}

	private boolean isSettingsToggle() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !(client.currentScreen instanceof ModuleScreen screen)) return false;
		return ((ModuleScreenAccessor) (Object) screen).greencorps$module() == this;
	}

	private void toggleSearchWindow() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		if (client.currentScreen instanceof SignSearchScreen) {
			client.currentScreen.close();
			return;
		}
		openScreen();
	}

	public void openScreen() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		if (GuiThemes.get() == null) return;
		if (client.currentScreen instanceof SignSearchScreen) return;
		SignSearchScreen screen = new SignSearchScreen(this);
		screen.parent = client.currentScreen;
		client.setScreen(screen);
	}

	/** Closing the search window leaves the module on. */
	public void onSearchClosed() {
	}
}
