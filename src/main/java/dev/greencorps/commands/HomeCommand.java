package dev.greencorps.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.greencorps.backup.Home;
import dev.greencorps.backup.SnapshotIndex;
import dev.greencorps.backup.StoreStats;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.gui.PlayableConfirmScreen;
import dev.greencorps.modules.BaseBackup;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import java.util.Optional;

/** Thin convenience wrapper — GUI is the primary interface. */
public class HomeCommand extends Command {
	public HomeCommand() {
		super("home", "Time Machine shortcuts. Prefer the module GUI.", "bb");
	}

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {
		builder.executes(ctx -> {
			BaseBackup mod = BaseBackup.get();
			if (mod == null || !mod.isActive()) {
				error("Enable Time Machine first.");
				return SINGLE_SUCCESS;
			}
			mod.openManageScreen();
			return SINGLE_SUCCESS;
		});

		builder.then(literal("list").executes(ctx -> {
			BaseBackup mod = require();
			if (mod == null) return SINGLE_SUCCESS;
			for (Home h : mod.homeStore().homes()) {
				info(h.name + " r=" + h.radiusChunks + " cov=" + (int) mod.coverage(h)
					+ "pct snaps=" + mod.snapshotCount(h)
					+ (h.armed ? " armed" : " idle"));
			}
			return SINGLE_SUCCESS;
		}));

		builder.then(literal("info").then(argument("name", StringArgumentType.string()).executes(ctx -> {
			BaseBackup mod = require();
			if (mod == null) return SINGLE_SUCCESS;
			String name = StringArgumentType.getString(ctx, "name");
			Optional<Home> oh = mod.homeStore().findByName(name);
			if (oh.isEmpty()) {
				error("Unknown home");
				return SINGLE_SUCCESS;
			}
			Home h = oh.get();
			info(h.name + " @ " + h.centerChunkX + "," + h.centerChunkZ
				+ " r=" + h.radiusChunks + " cov=" + (int) mod.coverage(h) + "pct"
				+ " snaps=" + mod.snapshotCount(h)
				+ " health=" + mod.engine().health().label(mod.engine().degradedSections()));
			mod.stats(h).ifPresent(s -> info("store " + StoreStats.formatBytes(s.storeBytes())
				+ " mean " + StoreStats.formatBytes((long) s.meanBytesPerCommit())
				+ " ~30d " + StoreStats.formatBytes(s.project30d())));
			return SINGLE_SUCCESS;
		})));

		builder.then(literal("commit").executes(ctx -> {
			BaseBackup mod = require();
			if (mod == null) return SINGLE_SUCCESS;
			mod.homeStore().homes().stream().filter(h -> mod.insideHomes().contains(h.id))
				.findFirst().ifPresentOrElse(mod::doCommit, () -> error("Not inside a home"));
			return SINGLE_SUCCESS;
		}).then(argument("name", StringArgumentType.string()).executes(ctx -> {
			BaseBackup mod = require();
			if (mod == null) return SINGLE_SUCCESS;
			mod.homeStore().findByName(StringArgumentType.getString(ctx, "name"))
				.ifPresentOrElse(mod::doCommit, () -> error("Unknown home"));
			return SINGLE_SUCCESS;
		})));

		builder.then(literal("export")
			.then(argument("name", StringArgumentType.string())
				.then(argument("when", StringArgumentType.string()).executes(ctx -> {
					BaseBackup mod = require();
					if (mod == null) return SINGLE_SUCCESS;
					String name = StringArgumentType.getString(ctx, "name");
					String when = StringArgumentType.getString(ctx, "when");
					Optional<Home> oh = mod.homeStore().findByName(name);
					if (oh.isEmpty()) {
						error("Unknown home");
						return SINGLE_SUCCESS;
					}
					try {
						var path = mod.exportHome(oh.get(), when, null);
						info("Exported " + path.getFileName());
					} catch (Exception e) {
						error(e.toString());
					}
					return SINGLE_SUCCESS;
				}))));

		builder.then(literal("snapshot").executes(ctx -> {
			BaseBackup mod = require();
			if (mod == null) return SINGLE_SUCCESS;
			try {
				Home h = mod.addSnapshotHere("snap-" + (System.currentTimeMillis() % 100000), mod.snapshotRadiusChunks());
				info("Snapshot " + h.name);
				PlayableConfirmScreen.promptAfterCreate(mod, h);
			} catch (Exception e) {
				error(e.toString());
			}
			return SINGLE_SUCCESS;
		}).then(argument("radius", IntegerArgumentType.integer(1, 128)).executes(ctx -> {
			BaseBackup mod = require();
			if (mod == null) return SINGLE_SUCCESS;
			try {
				int r = IntegerArgumentType.getInteger(ctx, "radius");
				Home h = mod.addSnapshotHere("snap-" + (System.currentTimeMillis() % 100000), r);
				info("Snapshot " + h.name);
				PlayableConfirmScreen.promptAfterCreate(mod, h);
			} catch (Exception e) {
				error(e.toString());
			}
			return SINGLE_SUCCESS;
		})));

		builder.then(literal("help").executes(ctx -> {
			info(".home — open manage GUI");
			info(".home list | info <name> | commit [name]");
			info(".home export <name> <latest|#n|-3d|ISO>");
			info(".home snapshot [radius]");
			return SINGLE_SUCCESS;
		}));
	}

	private BaseBackup require() {
		BaseBackup mod = BaseBackup.get();
		if (mod == null || !mod.isActive() || mod.homeStore() == null) {
			error("Enable Time Machine first.");
			return null;
		}
		return mod;
	}
}
