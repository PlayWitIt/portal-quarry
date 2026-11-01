package com.greysworkshop.portalquarry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.Box;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
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
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Development-only scripted test, run with {@code ./gradlew runSelfTest}. Not in the release jar. A bed, a portal, an
 * ender chest, a nearly full shulker box and a double chest of empty boxes in the overworld; a sealed room of
 * netherrack in the nether. Runs the loop for real and checks every obsidian ends up stored.
 */
final class SelfTest {
	private static final String WORLD = "portalquarry-selftest";
	private static final BlockPos BED = new BlockPos(1, -60, 3), ECHEST = new BlockPos(3, -60, 3), BOX = new BlockPos(-1, -60, 2),
			CHEST_A = new BlockPos(-2, -60, 4), CHEST_B = new BlockPos(-1, -60, 4);
	/** In the box at the start: one short of full by 4. */
	private static final int PREFILLED = 26 * 64 + 60;
	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed;
	private static String phase = "setup";
	private static BlockPos firstPortal;
	private static int framesMined, cycleStartTick, lastCycles;
	private static int pickBeforeMend;
	private static final StringBuilder cycleTimes = new StringBuilder();

	private SelfTest() {}

	static void start() {
		log("Self-test armed");
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
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		deleteTree(client.runDirectory.toPath().resolve("saves").resolve(WORLD));
		try {
			QuarryConfig.useFile(Files.createTempDirectory("portalquarry").resolve("portalquarry.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear " + dir + ": " + e);
		}
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		int since = tick - phaseStart;
		if (Quarry.cycles != lastCycles) {
			int took = tick - cycleStartTick;
			cycleTimes.append(took).append(' ');
			log("cycle " + Quarry.cycles + " took " + took + " ticks (" + String.format("%.1f", took / 20.0) + " s)");
			cycleStartTick = tick;
			lastCycles = Quarry.cycles;
		}
		switch (phase) {
			case "setup" -> {
				if (tick == 20) build(client);
				if (tick == 100) {
					command(client, "tp @p 1.5 -60 2.5 180 0");
					// A few things that must stop it starting.
					command(client, "item replace entity @p armor.head with minecraft:iron_helmet");
				}
				if (tick == 120) {
					Quarry.start(client);
					check("won't start wearing armour (" + Quarry.stopReason + ")", !Quarry.running() && Quarry.stopReason.contains("armour"));
					command(client, "item replace entity @p armor.head with minecraft:air");
					command(client, "give @p minecraft:diamond 3");
				}
				if (tick == 130) {
					Quarry.start(client);
					check("won't start carrying something it would lose (" + Quarry.stopReason + ")", !Quarry.running() && Quarry.stopReason.contains("Diamond"));
					command(client, "clear @p minecraft:diamond");
				}
				if (tick == 140) {
					Quarry.start(client);
					check("starts at the bed", Quarry.running());
					cycleStartTick = tick;
					next("cycle1");
				}
			}
			case "cycle1" -> {
				if (Quarry.state == Quarry.State.MINE && firstPortal == null && Quarry.portalMin != null) {
					firstPortal = Quarry.portalMin;
					check("the game built a 2x3 portal in the nether: 14 obsidian (" + Quarry.upper.size() + " + " + Quarry.floor.size() + " floor)",
							Quarry.upper.size() == 10 && Quarry.floor.size() == 4);
				}
				if (Quarry.state == Quarry.State.FLOOR && since % 20 == 0 && !shot1) {
					shot1 = true;
					screenshot(client, "1_nether_floor");
				}
				if (Quarry.cycles >= 1) {
					framesMined++;
					check("the ender chest was put down in the nether, clear of the portal (" + Quarry.homeChestNether + ")",
							Quarry.homeChestNether != null && !Quarry.inPortalSpace(Quarry.homeChestNether));
					check("the floor was mined and filled", Quarry.floorsFilled == 1 && floorFilled(client));
					// Wear the pickaxe (it's in the ender chest now) so the next stop at the bed mends it.
					pickBeforeMend = server(client, s -> {
						ItemStack pick = findPick(player(s).getEnderChestInventory());
						if (pick == null) return -1;
						pick.setDamage(pick.getMaxDamage() - 100);
						return 100;
					});
					check("the pickaxe went into the ender chest before dying", pickBeforeMend == 100);
					next("cycle2");
				}
				if (failIfStopped(client) || since > 3600) fail(client, "cycle 1 didn't finish (" + Quarry.status.getString() + ")");
			}
			case "cycle2" -> {
				if (Quarry.state == Quarry.State.TO_PORTAL && !checkedStore) {
					checkedStore = true;
					int[] st = storage(client);
					check("the full shulker box went into the chest (" + st[1] + " full in the chests)", st[1] == 1);
					check("an empty one took its place and holds the rest (" + st[0] + " in the placed box)", st[0] == 14 - 4);
					check("stored count matches (" + Quarry.stored + ")", Quarry.stored == 14);
					ItemStack pick = Quarry.bestPick(client.player.getInventory(), 0);
					int left = pick == null ? 0 : pick.getMaxDamage() - pick.getDamage();
					check("auto-mend repaired the pickaxe with XP bottles from the chest (100 -> " + left + " uses)", left > 600);
				}
				if (Quarry.state == Quarry.State.MINE && Quarry.portalMin != null && !checkedSecond) {
					checkedSecond = true;
					check("the next portal was built in the same place (" + Quarry.portalMin + " vs " + firstPortal + ")", Quarry.portalMin.equals(firstPortal));
				}
				if (Quarry.cycles >= 2) {
					framesMined++;
					int mined = server(client, s -> player(s).getStatHandler().getStat(Stats.MINED.getOrCreateStat(Blocks.OBSIDIAN)));
					check("14 obsidian mined each trip (" + mined + " after 2)", mined == 28);
					next("cycle3");
				}
				if (failIfStopped(client) || since > 3600) fail(client, "cycle 2 didn't finish (" + Quarry.status.getString() + ")");
			}
			case "cycle3" -> {
				if (Quarry.state == Quarry.State.MINE && Quarry.portalMin != null && !checkedThird) {
					checkedThird = true;
					check("and again on the third trip (" + Quarry.portalMin + ")", Quarry.portalMin.equals(firstPortal));
					// Health drops while mining: it should bank, die and stop at the bed.
					command(client, "damage @p 15 minecraft:generic");
					screenshot(client, "2_nether_mining");
				}
				if (!Quarry.running()) {
					framesMined++;
					check("low health in the nether: banked, died and stopped at the bed (" + Quarry.stopReason + ")",
							Quarry.stopReason.contains("health") && client.world.getRegistryKey() == World.OVERWORLD);
					check("nothing on me after that", Quarry.emptyHanded(client.player) || onlyOurs(client));
					next("worn_pick");
				}
				if (since > 3600) fail(client, "cycle 3 didn't finish (" + Quarry.status.getString() + ")");
			}
			case "worn_pick" -> {
				if (since == 10) {
					// Put everything back in the ender chest and wear the pickaxe out, with no XP bottles anywhere.
					server(client, s -> {
						ServerPlayerEntity sp = player(s);
						for (int i = 0; i < sp.getInventory().size(); i++) {
							ItemStack st = sp.getInventory().getStack(i);
							if (st.isEmpty()) continue;
							if (!st.isOf(Items.EXPERIENCE_BOTTLE)) sp.getEnderChestInventory().addStack(st.copy());
							sp.getInventory().setStack(i, ItemStack.EMPTY);
						}
						removeAll(sp.getEnderChestInventory(), Items.EXPERIENCE_BOTTLE);
						for (BlockPos c : new BlockPos[] {CHEST_A, CHEST_B}) {
							if (s.getOverworld().getBlockEntity(c) instanceof Inventory inv) removeAll(inv, Items.EXPERIENCE_BOTTLE);
						}
						ItemStack pick = findPick(sp.getEnderChestInventory());
						pick.setDamage(pick.getMaxDamage() - 50);
						return 0;
					});
				}
				if (since == 30) {
					Quarry.start(client);
				}
				if (since > 30 && !Quarry.running()) {
					check("won't take a pickaxe that could break into the nether (" + Quarry.stopReason + ")",
							Quarry.stopReason.contains("uses left") && client.world.getRegistryKey() == World.OVERWORLD);
					next("bad_kill");
				}
				if (since > 1200) fail(client, "the worn pickaxe run didn't stop");
			}
			case "bad_kill" -> {
				if (since == 10) {
					ItemStack pick = Quarry.bestPick(client.player.getInventory(), 0);
					if (pick != null) {
						// Mended by hand: the client's copy and the server's.
						server(client, s -> {
							ItemStack sp = findPick(player(s).getInventory());
							if (sp != null) sp.setDamage(0);
							return 0;
						});
					}
					QuarryConfig.INSTANCE.killCommand = "notarealcommand";
				}
				if (since == 40) Quarry.start(client);
				if (since > 40 && !Quarry.running()) {
					framesMined++;
					check("a kill command that doesn't work stops it with everything banked (" + Quarry.stopReason + ")",
							Quarry.stopReason.contains("didn't kill") && client.world.getRegistryKey() == World.NETHER && Quarry.emptyHanded(client.player));
					next("count");
				}
				if (since > 3600) fail(client, "the bad kill command run didn't stop");
			}
			case "count" -> {
				if (since == 20) {
					int total = server(client, s -> {
						ServerPlayerEntity sp = player(s);
						int n = obsidian(sp.getInventory()) + obsidian(sp.getEnderChestInventory());
						for (BlockPos p : new BlockPos[] {BOX, CHEST_A, CHEST_B}) {
							BlockEntity be = s.getOverworld().getBlockEntity(p);
							if (be instanceof Inventory inv) n += obsidian(inv);
						}
						return n;
					});
					int mined = server(client, s -> player(s).getStatHandler().getStat(Stats.MINED.getOrCreateStat(Blocks.OBSIDIAN)));
					int dropped = server(client, s -> s.getWorld(World.NETHER).getEntitiesByClass(ItemEntity.class, new Box(-40, 0, -40, 40, 128, 40),
							e -> e.getStack().isOf(Items.OBSIDIAN)).size());
					check("every obsidian mined is stored or banked: " + total + " = " + PREFILLED + " + " + mined + " mined", total == PREFILLED + mined);
					check("none left lying in the nether (" + dropped + ")", dropped == 0);
					check("about 14 a trip (" + mined + " mined over " + framesMined + " trips)", mined >= 14 * 2 && mined <= 14 * framesMined);
					log("cycle times (ticks): " + cycleTimes);
					finish(client);
				}
			}
			default -> {}
		}
	}

	private static boolean shot1, checkedStore, checkedSecond, checkedThird;

	private static boolean failIfStopped(MinecraftClient client) {
		if (!Quarry.running()) {
			log("stopped early: " + Quarry.stopReason);
			return true;
		}
		return false;
	}

	/** The overworld side, and a sealed netherrack room in the nether where the portal will be built. */
	private static void build(MinecraftClient client) {
		command(client, "time set 1000");
		// Portal: frame x 0..3, y -61..-57, z 0.
		command(client, "fill 0 -61 0 3 -57 0 minecraft:obsidian");
		command(client, "fill 1 -60 0 2 -58 0 minecraft:nether_portal[axis=x]");
		command(client, "setblock 1 -60 3 minecraft:red_bed[facing=south,part=foot]");
		command(client, "setblock 1 -60 4 minecraft:red_bed[facing=south,part=head]");
		command(client, "spawnpoint @p 1 -60 3");
		command(client, "setblock 3 -60 3 minecraft:ender_chest[facing=west]");
		command(client, "setblock -1 -60 2 minecraft:shulker_box[facing=up]");
		for (int i = 0; i < 26; i++) command(client, "item replace block -1 -60 2 container." + i + " with minecraft:obsidian 64");
		command(client, "item replace block -1 -60 2 container.26 with minecraft:obsidian 60");
		command(client, "setblock -2 -60 4 minecraft:chest[facing=north,type=left]");
		command(client, "setblock -1 -60 4 minecraft:chest[facing=north,type=right]");
		for (int i = 0; i < 3; i++) command(client, "item replace block -2 -60 4 container." + i + " with minecraft:shulker_box");
		command(client, "item replace block -2 -60 4 container.10 with minecraft:experience_bottle 64");
		command(client, "item replace block -2 -60 4 container.11 with minecraft:experience_bottle 64");
		command(client, "clear @p");
		command(client, "item replace entity @p enderchest.0 with minecraft:cobblestone 64");
		command(client, "give @p minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5,\"minecraft:mending\":1}]");
		command(client, "give @p minecraft:ender_chest 1");
		// The nether: solid netherrack, with an empty room at y 40-50 for the portal.
		String in = "execute in minecraft:the_nether run ";
		command(client, in + "forceload add -32 -32 31 31");
		int[][] slabs = {{0, 22}, {23, 45}, {46, 68}, {69, 91}, {92, 114}, {115, 127}};
		for (int[] s : slabs) command(client, in + "fill -18 " + s[0] + " -18 18 " + s[1] + " 18 minecraft:netherrack");
		command(client, in + "fill -18 40 -18 18 50 18 minecraft:air");
	}

	private static boolean floorFilled(MinecraftClient client) {
		return server(client, s -> {
			ServerWorld nether = s.getWorld(World.NETHER);
			for (BlockPos f : Quarry.floor) if (nether.getBlockState(f).isAir() || nether.getBlockState(f).isOf(Blocks.OBSIDIAN)) return 0;
			return 1;
		}) == 1;
	}

	/** {obsidian in the placed box, full boxes in the chests}. */
	private static int[] storage(MinecraftClient client) {
		int box = server(client, s -> s.getOverworld().getBlockEntity(BOX) instanceof Inventory inv ? obsidian(inv) : -1);
		int full = server(client, s -> {
			int n = 0;
			for (BlockPos c : new BlockPos[] {CHEST_A, CHEST_B}) {
				if (!(s.getOverworld().getBlockEntity(c) instanceof Inventory inv)) continue;
				for (int i = 0; i < inv.size(); i++) {
					ItemStack st = inv.getStack(i);
					if (Quarry.isBox(st) && !Quarry.emptyBox(st)) n++;
				}
			}
			return n;
		});
		return new int[] {box, full};
	}

	private static boolean onlyOurs(MinecraftClient client) {
		var inv = client.player.getInventory();
		for (int i = 0; i < inv.size(); i++) if (!inv.getStack(i).isEmpty() && !Quarry.ours(inv.getStack(i))) return false;
		return true;
	}

	private static int obsidian(Inventory inv) {
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack st = inv.getStack(i);
			if (st.isOf(Items.OBSIDIAN)) n += st.getCount();
			ContainerComponent c = st.get(DataComponentTypes.CONTAINER);
			if (c != null) n += c.streamNonEmpty().filter(x -> x.isOf(Items.OBSIDIAN)).mapToInt(ItemStack::getCount).sum();
		}
		return n;
	}

	private static void removeAll(Inventory inv, net.minecraft.item.Item item) {
		for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(item)) inv.setStack(i, ItemStack.EMPTY);
	}

	private static ItemStack findPick(Inventory inv) {
		for (int i = 0; i < inv.size(); i++) if (Quarry.isPick(inv.getStack(i))) return inv.getStack(i);
		return null;
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

	private static void fail(MinecraftClient client, String why) {
		check(why, false);
		log("cycle times (ticks): " + cycleTimes);
		finish(client);
	}

	private static void command(MinecraftClient client, String command) {
		// Waits for it: the server can be busy (the nether fill takes seconds), and checks follow straight after.
		server(client, s -> {
			s.getCommandManager().parseAndExecute(s.getCommandSource(), command);
			return 0;
		});
	}

	private static void next(String name) {
		phase = name;
		phaseStart = tick;
		log("phase " + name);
	}

	private static void finish(MinecraftClient client) {
		log("RESULT " + passed + " passed, " + failed + " failed");
		next("done");
		client.scheduleStop();
	}

	private static void screenshot(MinecraftClient client, String name) {
		client.getToastManager().clear();
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "pq_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		PortalQuarry.LOGGER.info("[SELFTEST] {}", message);
	}
}
