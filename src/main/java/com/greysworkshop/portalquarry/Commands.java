package com.greysworkshop.portalquarry;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /portalquarry: start, stop, status and settings. All client-side. */
final class Commands {
	private Commands() {}

	static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("portalquarry")
				.executes(Commands::help)
				.then(literal("start").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> Quarry.start(client));
					return 1;
				}))
				.then(literal("stop").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> Quarry.stop(client, "Stopped."));
					return 1;
				}))
				.then(literal("status").executes(Commands::status))
				.then(literal("kill").then(argument("command", StringArgumentType.greedyString()).executes(c -> {
					String cmd = StringArgumentType.getString(c, "command").strip();
					if (cmd.startsWith("/")) cmd = cmd.substring(1);
					QuarryConfig.INSTANCE.killCommand = cmd;
					return saved(c, "Kill command: /" + cmd);
				})))
				.then(literal("players").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.stopNearPlayers = BoolArgumentType.getBool(c, "on"),
						"Stop when a player comes near: " + BoolArgumentType.getBool(c, "on")))))
				.then(literal("range").then(argument("blocks", IntegerArgumentType.integer(8, 256)).executes(c -> set(c,
						cf -> cf.playerRange = IntegerArgumentType.getInteger(c, "blocks"), "Player range: " + IntegerArgumentType.getInteger(c, "blocks") + " blocks"))))
				.then(literal("mend").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.autoMend = BoolArgumentType.getBool(c, "on"),
						"Auto-mend with XP bottles: " + BoolArgumentType.getBool(c, "on")))))
				.then(literal("mendbelow").then(argument("percent", IntegerArgumentType.integer(5, 95)).executes(c -> set(c,
						cf -> cf.mendBelow = IntegerArgumentType.getInteger(c, "percent"), "Mend below " + IntegerArgumentType.getInteger(c, "percent") + "%"))))
				.then(literal("minuses").then(argument("uses", IntegerArgumentType.integer(25, 500)).executes(c -> set(c,
						cf -> cf.minDurability = IntegerArgumentType.getInteger(c, "uses"),
						"Never take a pickaxe with fewer than " + IntegerArgumentType.getInteger(c, "uses") + " uses left into the nether"))))
				.then(literal("floor").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.mineFloor = BoolArgumentType.getBool(c, "on"),
						"Mine the floor and fill it: " + BoolArgumentType.getBool(c, "on")))))
				.then(literal("hud").then(argument("on", BoolArgumentType.bool()).executes(c -> set(c, cf -> cf.hud = BoolArgumentType.getBool(c, "on"),
						"HUD: " + BoolArgumentType.getBool(c, "on"))))));
	}

	private static int set(CommandContext<FabricClientCommandSource> c, Consumer<QuarryConfig> change, String said) {
		change.accept(QuarryConfig.INSTANCE);
		return saved(c, said);
	}

	private static int saved(CommandContext<FabricClientCommandSource> c, String said) {
		QuarryConfig.save();
		c.getSource().sendFeedback(Text.literal(said).formatted(Formatting.GREEN));
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> c) {
		QuarryConfig cf = QuarryConfig.INSTANCE;
		FabricClientCommandSource s = c.getSource();
		s.sendFeedback(Text.literal("Portal Quarry: " + (Quarry.running() ? "running · " + Quarry.status.getString() : "stopped"
				+ (Quarry.stopReason.isEmpty() ? "" : " (" + Quarry.stopReason + ")"))).formatted(Formatting.LIGHT_PURPLE));
		s.sendFeedback(Text.literal("This run: " + Quarry.cycles + " cycles, " + Quarry.stored + " obsidian stored, " + Math.round(Quarry.perHour()) + "/hour"));
		s.sendFeedback(Text.literal("All time: " + cf.totalCycles + " cycles, " + cf.totalObsidian + " obsidian"));
		s.sendFeedback(Text.literal("Kill command /" + cf.killCommand + " · stop near players " + cf.stopNearPlayers + " (" + cf.playerRange
				+ " blocks) · auto-mend " + cf.autoMend + " below " + cf.mendBelow + "% · min uses " + cf.minDurability + " · floor " + cf.mineFloor)
				.formatted(Formatting.GRAY));
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		FabricClientCommandSource s = c.getSource();
		s.sendFeedback(Text.literal("Portal Quarry: press H at your bed to start or stop.").formatted(Formatting.LIGHT_PURPLE));
		s.sendFeedback(Text.literal("/portalquarry start | stop | status"));
		s.sendFeedback(Text.literal("/portalquarry kill <command> · players <true|false> · range <blocks> · mend <true|false> · mendbelow <percent> · "
				+ "minuses <uses> · floor <true|false> · hud <true|false>").formatted(Formatting.GRAY));
		return 1;
	}
}
