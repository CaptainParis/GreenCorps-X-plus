package dev.greencorps.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.greencorps.modules.AntiClient;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

/** Test helpers for Anti-Client: markers, scenario ids, summary. */
public class AntiClientCommand extends Command {
	public AntiClientCommand() {
		super("anticlient", "Anti-Client test helpers (mark, scenario, summary, dump, reset, where).", "ac");
	}

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {
		builder.then(literal("mark").executes(ctx -> {
			AntiClient m = need();
			if (m != null) m.mark("manual");
			return SINGLE_SUCCESS;
		}).then(argument("label", StringArgumentType.greedyString()).executes(ctx -> {
			AntiClient m = need();
			if (m != null) m.mark(StringArgumentType.getString(ctx, "label"));
			return SINGLE_SUCCESS;
		})));

		builder.then(literal("scenario").then(argument("id", StringArgumentType.word()).executes(ctx -> {
			AntiClient m = need();
			if (m == null) return SINGLE_SUCCESS;
			m.setScenario(StringArgumentType.getString(ctx, "id"));
			info("Scenario is now " + m.scenarioId());
			return SINGLE_SUCCESS;
		})));

		builder.then(literal("summary").executes(ctx -> {
			AntiClient m = need();
			if (m != null) for (String l : m.summaryLines()) info(l);
			return SINGLE_SUCCESS;
		}));

		builder.then(literal("dump").executes(ctx -> {
			AntiClient m = need();
			if (m != null) {
				m.dumpNow();
				info("Flight recorder written.");
			}
			return SINGLE_SUCCESS;
		}));

		builder.then(literal("reset").executes(ctx -> {
			AntiClient m = need();
			if (m != null) {
				m.resetStats();
				info("Stats reset (the log file keeps going).");
			}
			return SINGLE_SUCCESS;
		}));

		builder.then(literal("where").executes(ctx -> {
			AntiClient m = need();
			if (m != null) info(m.logPath());
			return SINGLE_SUCCESS;
		}));
	}

	private AntiClient need() {
		AntiClient m = AntiClient.get();
		if (m == null || !m.isActive()) {
			error("Enable the anti-client module first.");
			return null;
		}
		return m;
	}
}
