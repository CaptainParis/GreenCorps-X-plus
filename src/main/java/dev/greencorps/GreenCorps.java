package dev.greencorps;

import net.minecraft.client.gui.screen.ingame.LoomScreen;
import dev.greencorps.gui.AutoLoomWing;
import dev.greencorps.modules.AutoLoom;
import com.mojang.logging.LogUtils;
import dev.greencorps.commands.HomeCommand;
import dev.greencorps.commands.StasisCommand;
import dev.greencorps.gui.GcWings;
import dev.greencorps.gui.GreenCorpsTitleTag;
import dev.greencorps.gui.MapDuperWing;
import dev.greencorps.gui.ShulkerSortWing;
import dev.greencorps.hud.AntiClientHud;
import dev.greencorps.hud.BaseBackupHud;
import dev.greencorps.hud.BreezesHud;
import dev.greencorps.hud.OutpostsHud;
import dev.greencorps.hud.SolidPocketHud;
import dev.greencorps.mapdupe.LiveInventory;
import dev.greencorps.commands.AntiClientCommand;
import dev.greencorps.modules.AntiClient;
import dev.greencorps.modules.BaseBackup;
import dev.greencorps.modules.BookFiller;
import dev.greencorps.modules.BreezeFinder;
import dev.greencorps.modules.DiscordPings;
import dev.greencorps.util.CompanionSettingsBackup;
import dev.greencorps.util.EnderChestCache;
import dev.greencorps.util.ShulkerScreenTint;
import dev.greencorps.modules.EndFloor;
import dev.greencorps.modules.GatewayESP;
import dev.greencorps.modules.ItemFrameControl;
import dev.greencorps.modules.MapDuperPlus;
import dev.greencorps.modules.MapSlotPreview;
import dev.greencorps.modules.TooltipPlus;
import dev.greencorps.modules.OutpostFinder;
import dev.greencorps.modules.SafeShulker;
import dev.greencorps.modules.SignSearch;
import dev.greencorps.modules.SolidPocketFinder;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.modules.TimedCommands;
import dev.greencorps.render.EndFloorRenderer;
import dev.greencorps.render.HomeForcefieldRenderer;
import dev.greencorps.util.EspTitles;
import dev.greencorps.util.TimeMachineConfigMigrate;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class GreenCorps extends MeteorAddon {
	public static final Logger LOG = LogUtils.getLogger();
	public static final Category CATEGORY = new Category("GreenCorps", Items.EMERALD.getDefaultStack());
	public static final HudGroup HUD_GROUP = new HudGroup("GreenCorps");

	@Override
	public void onInitialize() {
		LOG.info("Initializing GreenCorps");
		TimeMachineConfigMigrate.maybeMigrate();
		CompanionSettingsBackup.backupIfCompanionMissing();
		EnderChestCache.register();
		ShulkerScreenTint.register();
		Modules.get().add(new BreezeFinder());
		Modules.get().add(new OutpostFinder());
		Modules.get().add(new SolidPocketFinder());
		Modules.get().add(new AutoLoom());
		GcWings.register(screen -> {
			AutoLoom mod = AutoLoom.get();
			if (mod == null || !mod.showWing()) return null;
			if (!(screen instanceof LoomScreen)) return null;
			return new AutoLoomWing(screen);
		});
		// Hook for modules outside the public build (empty here).
		if (!BuildFlags.SHAREABLE) {
			PrivateModules.register();
		}
		Modules.get().add(new MapSlotPreview());
		Modules.get().add(new BookFiller());
		Modules.get().add(new EndFloor());
		Modules.get().add(new TooltipPlus());
		Modules.get().add(new GatewayESP());
		Modules.get().add(new ItemFrameControl());
		Modules.get().add(new BaseBackup());
		Modules.get().add(new StasisChambers());
		Modules.get().add(new SignSearch());
		Modules.get().add(new TimedCommands());
		Modules.get().add(new DiscordPings());
		Modules.get().add(new SafeShulker());
		Modules.get().add(new AntiClient());
		Modules.get().add(new MapDuperPlus());
		GcWings.register(screen -> {
			MapDuperPlus mod = MapDuperPlus.get();
			if (mod == null || !mod.isActive()) return null;
			if (LiveInventory.stationFor(screen) == null) return null;
			return new MapDuperWing(screen);
		});
		GcWings.register(screen -> {
			MapDuperPlus mod = MapDuperPlus.get();
			if (mod == null || !mod.isActive() || !(screen instanceof ShulkerBoxScreen)) return null;
			return new ShulkerSortWing(screen);
		});
		EspTitles.applyAll();
		final boolean[] alwaysOnDone = { false };
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (alwaysOnDone[0] || client.world == null) return;
			alwaysOnDone[0] = true;
			StasisChambers stasis = StasisChambers.get();
			if (stasis != null) stasis.ensureAlwaysOn();
			SignSearch signs = SignSearch.get();
			if (signs != null) signs.ensureAlwaysOn();
			if (!CompanionSettingsBackup.companionInstalled() && CompanionSettingsBackup.hasBackup()) {
				ChatUtils.info("God's Eye Companion is now a separate jar (gods-eye-companion). Your settings are kept and come back when you install it.");
			}
		});
		Hud.get().register(BreezesHud.INFO);
		Hud.get().register(OutpostsHud.INFO);
		Hud.get().register(SolidPocketHud.INFO);
		Hud.get().register(BaseBackupHud.INFO);
		Hud.get().register(AntiClientHud.INFO);
		Commands.add(new HomeCommand());
		Commands.add(new AntiClientCommand());
		Commands.add(new StasisCommand());
		GreenCorpsTitleTag.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			BaseBackup mod = BaseBackup.get();
			if (mod != null && !mod.isActive()) mod.drainCaptureTick();
		});

		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
			BaseBackup mod = BaseBackup.get();
			if (mod != null) HomeForcefieldRenderer.render(context, mod);
			EndFloor floor = EndFloor.get();
			if (floor != null) EndFloorRenderer.render(context, floor);
		});
	}

	@Override
	public void onRegisterCategories() {
		Modules.registerCategory(CATEGORY);
	}

	@Override
	public String getPackage() {
		return "dev.greencorps";
	}
}
