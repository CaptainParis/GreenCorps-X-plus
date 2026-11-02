package dev.greencorps.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisHome;
import dev.greencorps.stasis.StasisPearl;
import dev.greencorps.stasis.StasisPerf;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

/** Thin convenience wrapper — module GUI is primary. */
public class StasisCommand extends Command {
	public StasisCommand() {
		super("stasis", "Stasis Monitor shortcuts.", "stasis-monitor", "stasis-chambers");
	}

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {
		builder.executes(ctx -> {
			StasisChambers mod = StasisChambers.get();
			if (mod == null) {
				error("Stasis Monitor missing.");
				return SINGLE_SUCCESS;
			}
			mod.openOverview();
			return SINGLE_SUCCESS;
		});

		builder.then(literal("list").executes(ctx -> {
			StasisChambers mod = require();
			if (mod == null) return SINGLE_SUCCESS;
			for (StasisHome h : mod.store().homes()) {
				var cap = mod.capacityFor(h);
				info(h.name + " " + cap.quotasLine()
					+ " r=" + h.radiusChunks);
			}
			for (StasisPearl p : mod.store().pearls()) {
				info((p.loaded ? "L" : "E") + " " + p.x + " " + p.y + " " + p.z
					+ " " + (p.ownerIgn != null ? p.ownerIgn : "unknown"));
			}
			return SINGLE_SUCCESS;
		}));

		builder.then(literal("perf")
			.executes(ctx -> {
				if (!StasisPerf.enabled()) {
					info("Perf logging is off. Use .stasis perf on (or the Debug > perf-log setting).");
					return SINGLE_SUCCESS;
				}
				info(StasisPerf.snapshot());
				return SINGLE_SUCCESS;
			})
			.then(literal("on").executes(ctx -> setPerf(true)))
			.then(literal("off").executes(ctx -> setPerf(false)))
			.then(literal("reset").executes(ctx -> {
				StasisPerf.reset();
				info("Perf window reset.");
				return SINGLE_SUCCESS;
			})));

		builder.then(literal("audit")
			.executes(ctx -> {
				StasisChambers mod = require();
				if (mod == null) return SINGLE_SUCCESS;
				for (String line : mod.auditLines()) {
					info(line);
					dev.greencorps.GreenCorps.LOG.info("[Stasis] audit: {}", line);
				}
				return SINGLE_SUCCESS;
			})
			.then(literal("fix").executes(ctx -> {
				StasisChambers mod = require();
				if (mod == null) return SINGLE_SUCCESS;
				try {
					var r = mod.repairHomesNow();
					info(r.any()
						? "Repaired home links (" + r.slotsMoved() + " slots, " + r.pearlsMoved() + " pearls)."
						: "Home links OK, nothing to repair.");
				} catch (java.io.IOException e) {
					error("Repair failed: " + e.getClass().getSimpleName());
				}
				return SINGLE_SUCCESS;
			})));

		builder.then(literal("overview").executes(ctx -> {
			StasisChambers mod = require();
			if (mod == null) return SINGLE_SUCCESS;
			mod.openOverview();
			return SINGLE_SUCCESS;
		}));

		builder.then(literal("add-here")
			.executes(ctx -> addHere(null, -1, -1))
			.then(argument("name", StringArgumentType.string())
				.executes(ctx -> addHere(
					StringArgumentType.getString(ctx, "name"), -1, -1))
				.then(argument("pearls", IntegerArgumentType.integer(1, 64))
					.executes(ctx -> addHere(
						StringArgumentType.getString(ctx, "name"),
						IntegerArgumentType.getInteger(ctx, "pearls"),
						-1))
					.then(argument("radius", IntegerArgumentType.integer(0, 64))
						.executes(ctx -> addHere(
							StringArgumentType.getString(ctx, "name"),
							IntegerArgumentType.getInteger(ctx, "pearls"),
							IntegerArgumentType.getInteger(ctx, "radius")))))));
	}

	private int addHere(String name, int pearls, int radius) {
		StasisChambers mod = require();
		if (mod == null) return SINGLE_SUCCESS;
		try {
			int p = pearls > 0 ? pearls : mod.defaultPearls();
			int r = radius >= 0 ? radius : mod.homeRadiusChunks();
			StasisHome h = mod.addHomeHere(name, r, p);
			info("Stasis home " + h.name + " " + h.pearlsRequired + " pearls r=" + h.radiusChunks);
		} catch (Exception e) {
			error(e.toString());
		}
		return SINGLE_SUCCESS;
	}

	private int setPerf(boolean on) {
		StasisChambers mod = require();
		if (mod == null) return SINGLE_SUCCESS;
		mod.setPerfLog(on);
		info("Stasis perf logging " + (on ? "on (5s windows in latest.log)." : "off."));
		return SINGLE_SUCCESS;
	}

	private StasisChambers require() {
		StasisChambers mod = StasisChambers.get();
		if (mod == null) {
			error("Stasis Monitor missing.");
			return null;
		}
		if (!mod.ensureStore() || mod.store() == null) {
			error("Store not open.");
			return null;
		}
		return mod;
	}
}
