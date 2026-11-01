package com.greysworkshop.portalquarry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Development only ({@code ./gradlew runPracticalTest}): is it practical? A normal generated world on Normal
 * difficulty, a small glassed-in base at spawn like a real one, and the loop left running AFK for a set time in real
 * nether terrain with real mobs. Stops for low health are restarted the way a player would (press H again). Reports
 * the obsidian per hour, every stop and why, where each portal was built, and any obsidian lost.
 */
final class PracticalTest {
	private static final String WORLD = "portalquarry-practical";
	private static final long SEED = Long.getLong("portalquarry.seed", 20261101L);
	private static final int MINUTES = Integer.getInteger("portalquarry.minutes", 20);
	private static boolean started;
	private static int tick = -1, phaseStart, restarts, lastCycles;
	private static String phase = "wait";
	private static BlockPos origin, chestA, chestB, boxPos;
	private static long runStart;
	private static final List<String> stops = new ArrayList<>();
	private static final Map<BlockPos, Integer> portals = new LinkedHashMap<>();
	private static boolean sawMine;
	private static int shots, floorsLeft, prevFloors;

	private PracticalTest() {}

	static void start() {
		log("Practical test armed: seed " + SEED + ", " + MINUTES + " minutes");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!started && screen instanceof net.minecraft.client.gui.screen.AccessibilityOnboardingScreen) {
				client.options.onboardAccessibility = false;
				client.send(() -> client.setScreen(new TitleScreen()));
				return;
			}
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(PracticalTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		client.options.getViewDistance().setValue(6);
		client.options.getSimulationDistance().setValue(6);
		deleteTree(client.runDirectory.toPath().resolve("saves").resolve(WORLD));
		try {
			QuarryConfig.useFile(Files.createTempDirectory("portalquarry").resolve("portalquarry.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.NORMAL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, new GeneratorOptions(SEED, true, false),
				WorldPresets::createDemoOptions, new TitleScreen());
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		int since = tick - phaseStart;
		switch (phase) {
			case "wait" -> {
				if (tick == 100) {
					build(client);
					next("start");
				}
			}
			case "start" -> {
				if (since == 60) {
					Quarry.start(client);
					if (!Quarry.running()) {
						log("RESULT could not start: " + Quarry.stopReason);
						finish(client);
						return;
					}
					runStart = System.currentTimeMillis();
					next("run");
				}
			}
			case "run" -> {
				long minutes = (System.currentTimeMillis() - runStart) / 60_000;
				if (Quarry.state == Quarry.State.MINE && !sawMine && Quarry.portalMin != null) {
					sawMine = true;
					portals.merge(Quarry.portalMin, 1, Integer::sum);
					if (shots < 2) screenshot(client, "nether_" + (++shots));
				}
				if (Quarry.state != Quarry.State.MINE) sawMine = false;
				if (Quarry.floorsFilled != prevFloors) prevFloors = Quarry.floorsFilled;
				if (Quarry.cycles != lastCycles) {
					lastCycles = Quarry.cycles;
					int mined = server(client, s -> player(s).getStatHandler().getStat(Stats.MINED.getOrCreateStat(Blocks.OBSIDIAN)));
					log("  mined so far " + mined);
					log("cycle " + Quarry.cycles + " at " + elapsed() + ": stored " + Quarry.stored + ", " + Math.round(Quarry.perHour()) + "/hour");
				}
				if (!Quarry.running()) {
					String where = client.world.getRegistryKey() == World.NETHER ? "nether" : "overworld";
					stops.add(elapsed() + " in the " + where + ": " + Quarry.stopReason);
					log("STOP " + stops.get(stops.size() - 1));
					// A player would heal up and press H again; anything else needs them to look.
					boolean restartable = where.equals("overworld") && Quarry.stopReason.contains("health") && restarts < 10;
					if (!restartable) {
						next("report");
						return;
					}
					restarts++;
					next("restart");
					return;
				}
				if (minutes >= MINUTES) {
					Quarry.stop(client, "Practical test time is up.");
					next("report");
				}
			}
			case "restart" -> {
				if (since == 100) {
					Quarry.start(client);
					if (!Quarry.running()) {
						stops.add(elapsed() + ": couldn't restart: " + Quarry.stopReason);
						next("report");
						return;
					}
					next("run");
				}
			}
			case "report" -> {
				if (since == 40) report(client);
			}
			default -> {}
		}
	}

	private static String elapsed() {
		long s = (System.currentTimeMillis() - runStart) / 1000;
		return String.format("%d:%02d", s / 60, s % 60);
	}

	private static void report(MinecraftClient client) {
		double hours = (System.currentTimeMillis() - runStart) / 3_600_000.0;
		int mined = server(client, s -> player(s).getStatHandler().getStat(Stats.MINED.getOrCreateStat(Blocks.OBSIDIAN)));
		int deaths = server(client, s -> player(s).getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DEATHS)));
		int held = server(client, s -> {
			ServerPlayerEntity sp = player(s);
			int n = obsidian(sp.getInventory()) + obsidian(sp.getEnderChestInventory());
			for (BlockPos p : new BlockPos[] {boxPos, chestA, chestB}) {
				if (s.getOverworld().getBlockEntity(p) instanceof Inventory inv) n += obsidian(inv);
			}
			return n;
		});
		int dropped = server(client, s -> {
			int n = 0;
			for (ItemEntity e : s.getWorld(World.NETHER).getEntitiesByClass(ItemEntity.class, new Box(-3e7, -64, -3e7, 3e7, 320, 3e7), e -> e.getStack().isOf(Items.OBSIDIAN))) {
				n += e.getStack().getCount();
			}
			return n;
		});
		log("REPORT ===================================");
		log("REPORT ran " + String.format("%.1f", hours * 60) + " min, " + Quarry.cycles + " cycles (this run), deaths " + deaths + ", restarts " + restarts);
		log("REPORT obsidian mined " + mined + ", stored/banked " + held + ", lying in the nether " + dropped + ", lost " + (mined - held - dropped));
		log("REPORT rate " + Math.round(held / Math.max(hours, 1e-6)) + " obsidian/hour over the whole test (incl. stops)");
		log("REPORT all-time counter (stored at the bed) " + QuarryConfig.INSTANCE.totalObsidian + " over " + QuarryConfig.INSTANCE.totalCycles + " cycles");
		log("REPORT portals built at " + portals.size() + " place(s): " + portals);
		log("REPORT stops: " + (stops.isEmpty() ? "none" : String.join(" | ", stops)));
		screenshot(client, "end");
		finish(client);
	}

	/** A small base at spawn: floor, glass walls and roof, portal, bed, ender chest, shulker box, double chest. */
	private static void build(MinecraftClient client) {
		BlockPos o = client.player.getBlockPos();
		origin = o;
		int x = o.getX(), y = o.getY(), z = o.getZ();
		command(client, "time set 1000");
		command(client, "fill " + (x - 6) + " " + y + " " + (z - 6) + " " + (x + 6) + " " + (y + 6) + " " + (z + 6) + " minecraft:air");
		command(client, "fill " + (x - 6) + " " + (y - 1) + " " + (z - 6) + " " + (x + 6) + " " + (y - 1) + " " + (z + 6) + " minecraft:stone");
		command(client, "fill " + (x - 6) + " " + y + " " + (z - 6) + " " + (x + 6) + " " + (y + 5) + " " + (z + 6) + " minecraft:glass hollow");
		command(client, "fill " + (x - 6) + " " + (y - 1) + " " + (z - 6) + " " + (x + 6) + " " + (y - 1) + " " + (z + 6) + " minecraft:stone");
		command(client, "fill " + (x - 1) + " " + (y - 1) + " " + (z - 3) + " " + (x + 2) + " " + (y + 3) + " " + (z - 3) + " minecraft:obsidian");
		command(client, "fill " + x + " " + y + " " + (z - 3) + " " + (x + 1) + " " + (y + 2) + " " + (z - 3) + " minecraft:nether_portal[axis=x]");
		command(client, "setblock " + x + " " + y + " " + (z + 1) + " minecraft:red_bed[facing=south,part=foot]");
		command(client, "setblock " + x + " " + y + " " + (z + 2) + " minecraft:red_bed[facing=south,part=head]");
		command(client, "spawnpoint @p " + x + " " + y + " " + (z + 1));
		command(client, "setblock " + (x + 2) + " " + y + " " + (z + 1) + " minecraft:ender_chest[facing=west]");
		boxPos = new BlockPos(x - 2, y, z);
		command(client, "setblock " + (x - 2) + " " + y + " " + z + " minecraft:shulker_box[facing=up]");
		chestA = new BlockPos(x - 3, y, z + 2);
		chestB = new BlockPos(x - 2, y, z + 2);
		command(client, "setblock " + (x - 3) + " " + y + " " + (z + 2) + " minecraft:chest[facing=north,type=left]");
		command(client, "setblock " + (x - 2) + " " + y + " " + (z + 2) + " minecraft:chest[facing=north,type=right]");
		String a = (x - 3) + " " + y + " " + (z + 2);
		for (int i = 0; i < 10; i++) command(client, "item replace block " + a + " container." + i + " with minecraft:shulker_box");
		for (int i = 10; i < 13; i++) command(client, "item replace block " + a + " container." + i + " with minecraft:experience_bottle 64");
		command(client, "clear @p");
		command(client, "item replace entity @p enderchest.0 with minecraft:cobblestone 64");
		for (int i = 13; i < 16; i++) command(client, "item replace block " + a + " container." + i + " with minecraft:cobblestone 64");
		command(client, "give @p minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5,\"minecraft:unbreaking\":3,\"minecraft:mending\":1}]");
		command(client, "give @p minecraft:ender_chest 1");
		command(client, "tp @p " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 180 0");
		log("base built at " + o);
	}

	private static int obsidian(Inventory inv) {
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack st = inv.getStack(i);
			if (st.isOf(Items.OBSIDIAN)) n += st.getCount();
			ContainerComponent c = st.get(DataComponentTypes.CONTAINER);
			if (c != null) n += c.streamNonEmpty().filter(s -> s.isOf(Items.OBSIDIAN)).mapToInt(ItemStack::getCount).sum();
		}
		return n;
	}

	private static ServerPlayerEntity player(MinecraftServer s) {
		return s.getPlayerManager().getPlayerList().get(0);
	}

	private static int server(MinecraftClient client, Function<MinecraftServer, Integer> job) {
		MinecraftServer s = client.getServer();
		try {
			return s.submit(() -> job.apply(s)).get();
		} catch (InterruptedException | ExecutionException e) {
			log("server job failed: " + e);
			return Integer.MIN_VALUE;
		}
	}

	private static void command(MinecraftClient client, String command) {
		server(client, s -> {
			s.getCommandManager().parseAndExecute(s.getCommandSource(), command);
			return 0;
		});
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear " + dir + ": " + e);
		}
	}

	private static void next(String name) {
		phase = name;
		phaseStart = tick;
	}

	private static void finish(MinecraftClient client) {
		next("done");
		client.scheduleStop();
	}

	private static void screenshot(MinecraftClient client, String name) {
		client.getToastManager().clear();
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "pq_practical_" + name + ".png", client.getFramebuffer(), 1, message -> {});
	}

	private static void log(String message) {
		PortalQuarry.LOGGER.info("[PRACTICAL] {}", message);
	}
}
