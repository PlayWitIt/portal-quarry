package com.greysworkshop.portalquarry;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The obsidian loop. At the bed: take the pickaxe and the obsidian out of the ender chest, store the obsidian (in a
 * shulker box, swapping in an empty one when it's full, or in chests), mend the pickaxe if it needs it, and walk into
 * the portal. In the nether: mine the frame the game just built, fill the floor holes so the next one is built in the
 * same place, pick everything up, put it all in the ender chest there, and /kill with an empty inventory. Respawn at
 * the bed and go again.
 */
final class Quarry {
	enum State {
		OFF, TAKE, STORE, SWAP_BOX, MEND, TO_PORTAL, IN_PORTAL, ARRIVE, MINE, STEP_OUT, FLOOR, COLLECT, DEPOSIT, KILL, RESPAWN
	}

	static State state = State.OFF;
	static Text status = Text.empty();
	/** Why the last run stopped, for the HUD and the self-test. */
	static String stopReason = "";

	// Set up at the start, in the overworld.
	private static BlockPos home, bed, homeChest, box;
	private static final List<BlockPos> storage = new ArrayList<>();
	private static final List<BlockPos> portal = new ArrayList<>();

	// The frame being mined in the nether.
	static final List<BlockPos> upper = new ArrayList<>(), floor = new ArrayList<>();
	static BlockPos portalMin;
	static Direction.Axis portalAxis;
	private static int side;

	// This run's numbers.
	static int cycles, stored, minedThisCycle, floorsFilled;
	static long startedAt;
	/** Stop once back at the bed (a player came near, or health got low, in the nether). */
	private static String stopAtBed;
	private static boolean warnedNoFiller;

	private static int ticks, wait, tries;
	private static final Walker walker = new Walker();
	private static BlockPos walkingTo;
	private static BlockPos breaking;
	private static int breakTicks;
	private static BlockPos opened;
	private static int clicks, chestIndex;
	private static int mendThrows;

	private Quarry() {}

	static boolean running() {
		return state != State.OFF;
	}

	static void toggle(MinecraftClient client) {
		if (running()) stop(client, "Stopped.");
		else start(client);
	}

	// ---------------------------------------------------------------- start / stop

	static void start(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		ClientWorld world = client.world;
		if (p == null || world == null) return;
		stopReason = "";
		if (world.getRegistryKey() != World.OVERWORLD) {
			refuse(p, "Start at your bed in the overworld, next to the portal.");
			return;
		}
		if (p.isCreative() || p.isSpectator()) {
			refuse(p, "This needs survival mode.");
			return;
		}
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
			if (!p.getEquippedStack(slot).isEmpty()) {
				refuse(p, slot == EquipmentSlot.OFFHAND ? "Empty your offhand first: everything you carry is dropped when you /kill."
						: "Take your armour off first: everything you wear is dropped when you /kill.");
				return;
			}
		}
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && !ours(s)) {
				refuse(p, "Put away the " + s.getName().getString() + " first. Carry only pickaxes, obsidian, filler blocks, XP bottles and an ender chest.");
				return;
			}
		}
		BlockPos at = p.getBlockPos();
		bed = nearest(world, at, 6, s -> s.getBlock() instanceof net.minecraft.block.BedBlock);
		homeChest = nearest(world, at, 6, s -> s.isOf(Blocks.ENDER_CHEST));
		BlockPos portalBlock = nearest(world, at, 10, s -> s.isOf(Blocks.NETHER_PORTAL));
		List<String> missing = new ArrayList<>();
		if (bed == null) missing.add("a bed you sleep in (within 6 blocks)");
		if (homeChest == null) missing.add("an ender chest (within 6 blocks)");
		if (portalBlock == null) missing.add("a lit nether portal (within 10 blocks)");
		if (!missing.isEmpty()) {
			refuse(p, "Missing: " + String.join(", ", missing) + ".");
			return;
		}
		home = at;
		portal.clear();
		for (BlockPos q : BlockPos.iterate(portalBlock.add(-22, -22, -22), portalBlock.add(22, 22, 22))) {
			if (world.getBlockState(q).isOf(Blocks.NETHER_PORTAL) && !world.getBlockState(q.down()).isOf(Blocks.NETHER_PORTAL)) portal.add(q.toImmutable());
		}
		box = nearest(world, at, 6, s -> s.getBlock() instanceof ShulkerBoxBlock);
		storage.clear();
		for (BlockPos q : BlockPos.iterate(at.add(-6, -3, -6), at.add(6, 3, 6))) {
			BlockState s = world.getBlockState(q);
			if (!(s.isOf(Blocks.CHEST) || s.isOf(Blocks.TRAPPED_CHEST) || s.isOf(Blocks.BARREL))) continue;
			BlockPos pos = q.toImmutable();
			// One half of each double chest is enough.
			if (s.getBlock() instanceof ChestBlock && s.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE && storage.contains(pos.offset(ChestBlock.getFacing(s)))) continue;
			storage.add(pos);
		}
		storage.sort(Comparator.comparingDouble(q -> q.getSquaredDistance(at)));
		if (box == null && storage.isEmpty()) {
			refuse(p, "Missing: somewhere to put the obsidian. Place a shulker box or a chest within 6 blocks.");
			return;
		}
		cycles = 0;
		stored = 0;
		floorsFilled = 0;
		stopAtBed = null;
		warnedNoFiller = false;
		startedAt = System.currentTimeMillis();
		chat(p, Text.literal("Started. Press H or type /portalquarry stop to stop. Storing in "
				+ (box != null ? "the shulker box" + (storage.isEmpty() ? "" : ", with " + storage.size() + " chest(s) for full and empty boxes") : storage.size() + " chest(s)")
				+ ".").formatted(Formatting.GREEN));
		log("start: home {} bed {} chest {} portal {} box {} storage {}", home, bed, homeChest, portal.size(), box, storage);
		enter(State.TAKE);
	}

	static void stop(MinecraftClient client, String why) {
		if (state == State.OFF) return;
		state = State.OFF;
		stopReason = why;
		release(client);
		ClientPlayerEntity p = client.player;
		if (p != null) {
			if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
			chat(p, Text.literal(why).formatted(Formatting.YELLOW));
			p.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1, 0.7f);
		}
		status = Text.literal(why);
		log("stopped: {}", why);
		QuarryConfig.save();
	}

	/** Stop without the loop having started. */
	private static void refuse(ClientPlayerEntity p, String why) {
		stopReason = why;
		chat(p, Text.literal(why).formatted(Formatting.RED));
	}

	private static void release(MinecraftClient client) {
		walker.clear(client);
		walkingTo = null;
		if (breaking != null && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		breaking = null;
		client.options.useKey.setPressed(false);
	}

	private static void enter(State s) {
		state = s;
		Walker.magmaOk = false;
		ticks = 0;
		wait = 0;
		tries = 0;
		clicks = 0;
		chestIndex = 0;
		opened = null;
		openTries = 0;
		reachTries = 0;
		walkingTo = null;
		log("-> {}", s);
	}

	// ---------------------------------------------------------------- the loop

	static void tick(MinecraftClient client) {
		if (state == State.OFF) return;
		ClientPlayerEntity p = client.player;
		ClientWorld world = client.world;
		if (p == null || world == null || client.interactionManager == null) return;
		ticks++;
		boolean nether = world.getRegistryKey() == World.NETHER;
		boolean dead = p.isDead() || client.currentScreen instanceof DeathScreen;
		if (!dead && state != State.KILL && state != State.RESPAWN) {
			String danger = danger(client, p);
			if (danger != null) {
				if (nether && state.ordinal() >= State.ARRIVE.ordinal()) {
					// Dying is the way out: bank what we have and go.
					if (stopAtBed == null) {
						stopAtBed = danger;
						chat(p, Text.literal(danger + " Banking the obsidian and heading home.").formatted(Formatting.YELLOW));
						if (state != State.DEPOSIT) enter(State.DEPOSIT);
					}
				} else if (state != State.IN_PORTAL || !nether) {
					stop(client, danger);
					return;
				}
			}
		}
		if (wait > 0) {
			wait--;
			return;
		}
		switch (state) {
			case TAKE -> take(client, p, world);
			case STORE -> store(client, p, world);
			case SWAP_BOX -> swapBox(client, p, world);
			case MEND -> mend(client, p, world);
			case TO_PORTAL -> toPortal(client, p, world);
			case IN_PORTAL -> inPortal(client, p, world);
			case ARRIVE -> arrive(client, p, world);
			case MINE -> mine(client, p, world);
			case STEP_OUT -> stepOut(client, p, world);
			case FLOOR -> mineFloor(client, p, world);
			case COLLECT -> collect(client, p, world);
			case DEPOSIT -> deposit(client, p, world);
			case KILL -> kill(client, p, world);
			case RESPAWN -> respawn(client, p, world);
			default -> {}
		}
	}

	/** Why it's not safe to carry on, or null. */
	private static String danger(MinecraftClient client, ClientPlayerEntity p) {
		QuarryConfig c = QuarryConfig.INSTANCE;
		if (c.stopNearPlayers) {
			for (PlayerEntity other : client.world.getPlayers()) {
				if (other == p || other.isSpectator()) continue;
				if (other.squaredDistanceTo(p) <= (double) c.playerRange * c.playerRange) {
					return "Stopped: " + other.getName().getString() + " is " + (int) other.distanceTo(p) + " blocks away.";
				}
			}
		}
		if (p.getHealth() <= 6) return "Stopped: your health is low.";
		return null;
	}

	// ---- at the bed

	/** Ender chest at the bed: take the obsidian, a usable pickaxe, filler, and XP bottles if the pickaxe needs mending. */
	private static void take(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		if (stopAtBed != null) {
			String why = stopAtBed;
			stopAtBed = null;
			if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
			stop(client, why + " You're at your bed; the obsidian is in your ender chest.");
			return;
		}
		if (!open(client, p, homeChest, "your ender chest")) return;
		ScreenHandler h = p.currentScreenHandler;
		List<Slot> chest = containerSlots(h, p);
		// One click a tick.
		ItemStack carried = bestPick(p.getInventory(), QuarryConfig.INSTANCE.minDurability);
		ItemStack any = bestPick(p.getInventory(), 0);
		boolean needMend = QuarryConfig.INSTANCE.autoMend && any != null && needsMend(any) && hasMending(any);
		for (Slot s : chest) {
			ItemStack st = s.getStack();
			if (st.isEmpty()) continue;
			boolean want = st.isOf(Items.OBSIDIAN)
					|| isPick(st) && (any == null || carried == null && usable(st, QuarryConfig.INSTANCE.minDurability))
					|| filler(st) && count(p.getInventory(), Quarry::filler) == 0
					|| st.isOf(Items.EXPERIENCE_BOTTLE) && needMend && count(p.getInventory(), x -> x.isOf(Items.EXPERIENCE_BOTTLE)) < 64;
			if (want && hasRoom(p.getInventory(), st)) {
				quickMove(client, p, h, s);
				return;
			}
		}
		if (++clicks < 3) return; // let the last moves settle
		p.closeHandledScreen();
		ItemStack pick = bestPick(p.getInventory(), 0);
		if (pick == null) {
			stop(client, "No pickaxe: put one in your inventory or your ender chest.");
			return;
		}
		enter(State.STORE);
	}

	/** Obsidian into the shulker box (or the chests). */
	private static void store(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		if (count(p.getInventory(), x -> x.isOf(Items.OBSIDIAN)) == 0) {
			if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
			enter(State.MEND);
			return;
		}
		BlockPos target = box != null ? box : storage.get(Math.min(tries, storage.size() - 1));
		if (box != null && !(world.getBlockState(box).getBlock() instanceof ShulkerBoxBlock)) {
			enter(State.SWAP_BOX);
			return;
		}
		if (!open(client, p, target, box != null ? "the shulker box" : "the chest")) return;
		ScreenHandler h = p.currentScreenHandler;
		Slot obsidian = playerSlot(h, p, x -> x.isOf(Items.OBSIDIAN));
		if (obsidian != null && hasRoom(containerSlots(h, p), obsidian.getStack())) {
			int before = obsidian.getStack().getCount();
			quickMove(client, p, h, obsidian);
			int moved = before - obsidian.getStack().getCount();
			stored += moved;
			QuarryConfig.INSTANCE.totalObsidian += moved;
			return;
		}
		p.closeHandledScreen();
		if (box != null) {
			enter(State.SWAP_BOX);
			return;
		}
		// Loose in chests: on to the next.
		if (++tries >= storage.size()) {
			stop(client, "Storage is full: every chest next to the bed is full of obsidian.");
			return;
		}
		opened = null;
	}

	/** Full box: break it, put it in a chest, take an empty one out, and place it where the full one was. */
	private static void swapBox(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		PlayerInventory inv = p.getInventory();
		BlockState there = world.getBlockState(box);
		boolean placed = there.getBlock() instanceof ShulkerBoxBlock;
		int full = slot(inv, x -> isBox(x) && !emptyBox(x));
		int empty = slot(inv, Quarry::emptyBox);
		switch (tries) {
			case 0 -> { // break the full box
				if (storage.isEmpty()) {
					stop(client, "The shulker box is full, and there's no chest next to the bed to keep full and empty boxes in.");
					return;
				}
				if (placed) {
					breakBlock(client, p, world, box);
					return;
				}
				tries = 1;
				ticks = 0;
			}
			case 1 -> { // pick it up
				if (full >= 0 || empty >= 0) {
					tries = 2;
					opened = null;
					return;
				}
				ItemEntity drop = nearestDrop(world, p, 6, Quarry::isBox);
				if (drop != null) walkOnto(client, p, world, drop);
				if (ticks > 200) stop(client, "Lost the full shulker box: it didn't come back after breaking it.");
			}
			case 2 -> { // chests: full box in, empty box out
				BlockPos chest = storage.get(Math.min(chestIndex, storage.size() - 1));
				if (!open(client, p, chest, "the chest")) return;
				ScreenHandler h = p.currentScreenHandler;
				Slot fullSlot = playerSlot(h, p, x -> isBox(x) && !emptyBox(x));
				if (fullSlot != null) {
					if (hasRoom(containerSlots(h, p), fullSlot.getStack())) {
						quickMove(client, p, h, fullSlot);
						return;
					}
				} else if (empty < 0) {
					for (Slot s : containerSlots(h, p)) {
						if (emptyBox(s.getStack())) {
							quickMove(client, p, h, s);
							return;
						}
					}
				}
				if (fullSlot == null && empty >= 0) {
					p.closeHandledScreen();
					tries = 3;
					ticks = 0;
					return;
				}
				// Not this chest: the next.
				p.closeHandledScreen();
				int next = chestIndex + 1;
				if (next >= storage.size()) {
					stop(client, fullSlot != null ? "No room for the full shulker box in the chests next to the bed."
							: "Out of empty shulker boxes: put more in the chests next to the bed.");
					return;
				}
				chestIndex = next;
				opened = null;
			}
			case 3 -> { // place the empty one
				if (placed) {
					log("new shulker box placed at {}", box);
					enter(State.STORE);
					return;
				}
				if (ticks > 100) {
					stop(client, "Couldn't place the empty shulker box at " + box.toShortString() + ".");
					return;
				}
				if (ticks % 10 == 1) place(client, p, world, box, x -> emptyBox(x));
			}
			default -> enter(State.STORE);
		}
	}

	/** Mending with XP bottles: the pickaxe in the offhand, bottles thrown at our feet. */
	private static void mend(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		QuarryConfig c = QuarryConfig.INSTANCE;
		PlayerInventory inv = p.getInventory();
		ItemStack off = p.getOffHandStack();
		if (tries == 0) {
			ItemStack pick = bestPick(inv, 0);
			if (pick == null) {
				stop(client, "No pickaxe left that's safe to use.");
				return;
			}
			int left = pick.getMaxDamage() - pick.getDamage();
			boolean mendable = c.autoMend && needsMend(pick) && hasMending(pick);
			int bottles = count(inv, x -> x.isOf(Items.EXPERIENCE_BOTTLE));
			if (mendable && bottles == 0 && ticks < 2) {
				// Maybe in the chests.
				tries = 10;
				return;
			}
			if (mendable && bottles > 0) {
				tries = 1;
				mendThrows = 0;
				return;
			}
			if (left < c.minDurability) {
				ItemStack spare = bestPick(inv, c.minDurability);
				if (spare == null) {
					stop(client, "Your pickaxe has " + left + " uses left, too few for another trip"
							+ (hasMending(pick) ? (c.autoMend ? ", and there are no XP bottles to mend it with. Put some in your ender chest or the chests." : ". Turn on auto-mend or mend it.")
							: ". It has no Mending: put a fresh pickaxe in your ender chest."));
					return;
				}
			}
			enter(State.TO_PORTAL);
			return;
		}
		if (tries == 10) { // look in the chests for XP bottles
			if (chestIndex >= storage.size()) {
				tries = 0;
				ticks = 5;
				return;
			}
			if (!open(client, p, storage.get(chestIndex), "the chest")) return;
			ScreenHandler h = p.currentScreenHandler;
			if (count(inv, x -> x.isOf(Items.EXPERIENCE_BOTTLE)) < 64) {
				for (Slot s : containerSlots(h, p)) {
					if (s.getStack().isOf(Items.EXPERIENCE_BOTTLE) && hasRoom(inv, s.getStack())) {
						quickMove(client, p, h, s);
						return;
					}
				}
			}
			p.closeHandledScreen();
			if (count(inv, x -> x.isOf(Items.EXPERIENCE_BOTTLE)) > 0) {
				tries = 0;
				ticks = 5;
				return;
			}
			chestIndex++;
			opened = null;
			return;
		}
		if (tries == 1) { // pickaxe to the offhand
			if (isPick(off)) {
				tries = 2;
				return;
			}
			ItemStack pick = bestPick(inv, 0);
			int slot = indexOf(inv, pick);
			client.interactionManager.clickSlot(p.playerScreenHandler.syncId, invToScreenSlot(slot), 40, SlotActionType.SWAP, p);
			wait = 2;
			return;
		}
		if (tries == 2) { // throw
			boolean full = !off.isDamaged();
			int bottle = slot(inv, x -> x.isOf(Items.EXPERIENCE_BOTTLE));
			if (full || bottle < 0 || !isPick(off) || mendThrows > 2000) {
				tries = 3;
				ticks = 0;
				return;
			}
			select(client, p, bottle);
			p.setPitch(90);
			client.interactionManager.interactItem(p, Hand.MAIN_HAND);
			mendThrows++;
			wait = 2;
			return;
		}
		if (tries == 3) { // let the orbs land, then put the pickaxe back
			if (ticks < 30) return;
			if (isPick(off)) {
				int free = inv.getEmptySlot();
				if (free < 0) {
					stop(client, "No room to take the pickaxe out of your offhand.");
					return;
				}
				client.interactionManager.clickSlot(p.playerScreenHandler.syncId, invToScreenSlot(free), 40, SlotActionType.SWAP, p);
				wait = 2;
				return;
			}
			ItemStack pick = bestPick(inv, 0);
			log("mended with {} bottles: {} of {} uses", mendThrows, pick == null ? 0 : pick.getMaxDamage() - pick.getDamage(), pick == null ? 0 : pick.getMaxDamage());
			tries = 0;
			ticks = 5;
			// Don't mend again straight away if bottles ran out: go on to the durability check.
			if (pick != null && needsMend(pick) && count(inv, x -> x.isOf(Items.EXPERIENCE_BOTTLE)) == 0) tries = 20;
			return;
		}
		if (tries == 20) {
			ItemStack pick = bestPick(inv, c.minDurability);
			if (pick == null) {
				ItemStack worn = bestPick(inv, 0);
				stop(client, "Out of XP bottles, and your pickaxe has only " + (worn == null ? 0 : worn.getMaxDamage() - worn.getDamage()) + " uses left.");
				return;
			}
			enter(State.TO_PORTAL);
		}
	}

	private static void toPortal(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		if (!p.getInventory().getStack(PlayerInventory.OFF_HAND_SLOT).isEmpty()) {
			stop(client, "Something's in your offhand: it would be lost.");
			return;
		}
		BlockPos feet = Walker.feet(p);
		if (world.getBlockState(feet).isOf(Blocks.NETHER_PORTAL)) {
			walker.clear(client);
			enter(State.IN_PORTAL);
			return;
		}
		if (walkingTo == null || !walker.moving() && walker.arrived()) {
			List<BlockPos> cells = portal.stream().filter(q -> world.getBlockState(q).isOf(Blocks.NETHER_PORTAL)).toList();
			if (cells.isEmpty()) {
				stop(client, "The portal has gone out. Light it again and press H.");
				return;
			}
			if (++tries > 4 || !walker.plan(world, p, cells::contains, bounds(home, 24, 8))) {
				stop(client, "Can't find a way into the portal from the bed.");
				return;
			}
			walkingTo = cells.get(0);
		}
		if (!walker.tick(client)) walkingTo = null;
		status = Text.literal("Walking into the portal");
	}

	private static void inPortal(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		walker.stop(client);
		if (world.getRegistryKey() == World.NETHER) {
			enter(State.ARRIVE);
			return;
		}
		if (!world.getBlockState(Walker.feet(p)).isOf(Blocks.NETHER_PORTAL)) {
			enter(State.TO_PORTAL);
			return;
		}
		status = Text.literal("In the portal");
		// Generous: the first trip makes the server generate the nether, and a laggy server can take a long time.
		if (ticks > 2400) stop(client, "The portal didn't take me to the nether in 2 minutes.");
	}

	// ---- in the nether

	private static void arrive(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		status = Text.literal("Arrived");
		if (world.getRegistryKey() != World.NETHER) {
			if (ticks > 1200) stop(client, "Expected to be in the nether.");
			return;
		}
		BlockPos feet = Walker.feet(p);
		if (!world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return;
		BlockPos start = null;
		for (BlockPos q : BlockPos.iterate(feet.add(-2, -2, -2), feet.add(2, 3, 2))) {
			if (world.getBlockState(q).isOf(Blocks.NETHER_PORTAL)) {
				start = q.toImmutable();
				break;
			}
		}
		if (start == null) {
			if (ticks > 1200) {
				// Nothing to mine (someone broke it?). Still bank and go home.
				log("no portal where I arrived at {}", feet);
				enter(State.COLLECT);
			}
			return;
		}
		findFrame(world, start);
		minedThisCycle = 0;
		log("arrived: portal at {} axis {}, {} upper + {} floor obsidian", portalMin, portalAxis, upper.size(), floor.size());
		enter(State.MINE);
		mine(client, p, world);
	}

	/** The portal blocks joined to {@code start}, and the obsidian around them. */
	static void findFrame(ClientWorld world, BlockPos start) {
		BlockState st = world.getBlockState(start);
		Direction.Axis axis = st.get(NetherPortalBlock.AXIS);
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> todo = new ArrayDeque<>();
		todo.add(start);
		seen.add(start);
		int minA = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		Direction along = Direction.from(axis, Direction.AxisDirection.POSITIVE);
		while (!todo.isEmpty() && seen.size() < 500) {
			BlockPos q = todo.poll();
			int a = axis == Direction.Axis.X ? q.getX() : q.getZ();
			minA = Math.min(minA, a);
			maxA = Math.max(maxA, a);
			minY = Math.min(minY, q.getY());
			maxY = Math.max(maxY, q.getY());
			for (BlockPos n : new BlockPos[] {q.up(), q.down(), q.offset(along), q.offset(along.getOpposite())}) {
				if (seen.contains(n) || !world.getBlockState(n).isOf(Blocks.NETHER_PORTAL)) continue;
				seen.add(n);
				todo.add(n);
			}
		}
		int plane = axis == Direction.Axis.X ? start.getZ() : start.getX();
		portalAxis = axis;
		portalMin = axis == Direction.Axis.X ? new BlockPos(minA, minY, plane) : new BlockPos(plane, minY, minA);
		upper.clear();
		floor.clear();
		for (int y = maxY + 1; y >= minY - 1; y--) {
			for (int a = minA - 1; a <= maxA + 1; a++) {
				if (!(a == minA - 1 || a == maxA + 1 || y == minY - 1 || y == maxY + 1)) continue;
				BlockPos q = axis == Direction.Axis.X ? new BlockPos(a, y, plane) : new BlockPos(plane, y, a);
				if (!world.getBlockState(q).isOf(Blocks.OBSIDIAN)) continue;
				(y == minY - 1 ? floor : upper).add(q);
			}
		}
	}

	/** The frame from the top down, standing inside it; not the floor yet. */
	private static void mine(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		status = Text.literal("Mining the frame (" + minedThisCycle + ")");
		for (BlockPos q : upper) {
			if (!world.getBlockState(q).isOf(Blocks.OBSIDIAN)) continue;
			if (bestPick(p.getInventory(), 5) == null) {
				log("pickaxe nearly broken mid-cycle: banking");
				enter(State.COLLECT);
				return;
			}
			if (breakBlock(client, p, world, q)) minedThisCycle++;
			return;
		}
		enter(QuarryConfig.INSTANCE.mineFloor && !floor.isEmpty() ? State.STEP_OUT : State.COLLECT);
	}

	/** Off the floor and beside it, so the floor can be mined and filled. */
	private static void stepOut(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		status = Text.literal("Stepping out");
		if (count(p.getInventory(), Quarry::filler) < floor.size()) {
			if (!warnedNoFiller) {
				warnedNoFiller = true;
				chat(p, Text.literal("No filler blocks, so I'm leaving the portal's floor (10 obsidian a cycle, not 14). "
						+ "Put cobblestone or netherrack in your ender chest for the full 14.").formatted(Formatting.YELLOW));
			}
			enter(State.COLLECT);
			return;
		}
		Direction across = portalAxis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
		List<BlockPos> goals = new ArrayList<>();
		for (BlockPos f : floor) {
			for (int s : new int[] {1, -1}) {
				BlockPos cell = f.up().offset(across, s);
				if (Walker.standable(world, cell)) goals.add(cell);
			}
		}
		BlockPos feet = Walker.feet(p);
		if (goals.contains(feet) && p.isOnGround()) {
			walker.stop(client);
			side = portalAxis == Direction.Axis.X ? feet.getZ() - portalMin.getZ() : feet.getX() - portalMin.getX();
			enter(State.FLOOR);
			return;
		}
		if (walkingTo == null) {
			if (goals.isEmpty() || ++tries > 3 || !walker.plan(world, p, goals::contains, bounds(feet, 6, 3))) {
				log("nowhere to stand beside the floor: leaving it");
				enter(State.COLLECT);
				return;
			}
			walkingTo = goals.get(0);
		}
		if (!walker.tick(client) || walker.arrived()) walkingTo = null;
	}

	/** Each floor block: mine it, then fill the hole so the ground is as the game left it. */
	private static void mineFloor(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		status = Text.literal("Mining the floor");
		Direction across = portalAxis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
		for (BlockPos q : floor) {
			BlockState s = world.getBlockState(q);
			if (s.isOf(Blocks.OBSIDIAN)) {
				if (bestPick(p.getInventory(), 5) == null) {
					enter(State.COLLECT);
					return;
				}
				if (breakBlock(client, p, world, q)) {
					minedThisCycle++;
					tries = 0;
				}
				return;
			}
			if (s.isReplaceable() || s.isAir()) {
				if (count(p.getInventory(), Quarry::filler) == 0) continue;
				if (++tries > 30) {
					log("couldn't fill {}", q);
					continue;
				}
				if (tries % 5 == 1) {
					BlockPos against = q.offset(across, side >= 0 ? 1 : -1);
					placeAgainst(client, p, world, q, against, Quarry::filler);
				}
				return;
			}
		}
		floorsFilled++;
		enter(State.COLLECT);
	}

	/** Walk over the dropped obsidian. */
	private static void collect(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		status = Text.literal("Picking up");
		if (ticks < 12) return; // a drop can't be picked up for its first half second
		ItemEntity drop = nearestDrop(world, p, 8, x -> x.isOf(Items.OBSIDIAN) || filler(x));
		Walker.magmaOk = true;
		if (drop == null || ticks > 200) {
			Walker.magmaOk = false;
			if (Boolean.getBoolean("portalquarry.debug")) {
				for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(16, 16, 16), e -> e.getStack().isOf(Items.OBSIDIAN))) {
					log("left behind: {} obsidian at {} (in {}, on {}), {} blocks away, age {}", e.getStack().getCount(), e.getBlockPos(),
							world.getBlockState(e.getBlockPos()).getBlock(), world.getBlockState(e.getBlockPos().down()).getBlock(),
							String.format("%.1f", e.distanceTo(p)), e.getItemAge());
				}
			}
			walker.stop(client);
			enter(State.DEPOSIT);
			return;
		}
		walkOnto(client, p, world, drop);
	}

	/** Everything into the ender chest here. Nothing may be left on us when we die. */
	private static void deposit(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		status = Text.literal("Banking " + count(p.getInventory(), x -> x.isOf(Items.OBSIDIAN)) + " obsidian");
		if (homeChestNether == null || !world.getBlockState(homeChestNether).isOf(Blocks.ENDER_CHEST)
				|| homeChestNether.getSquaredDistance(p.getBlockPos()) > 24 * 24) {
			homeChestNether = nearest(world, p.getBlockPos(), 16, x -> x.isOf(Blocks.ENDER_CHEST));
		}
		if (homeChestNether == null && slot(p.getInventory(), x -> x.isOf(Items.ENDER_CHEST)) >= 0) {
			placeNetherChest(client, p, world);
			return;
		}
		if (homeChestNether == null) {
			stop(client, "No ender chest near the nether portal. Place one beside where you come out (not in the way of the portal), "
					+ "walk home, and press H.");
			return;
		}
		if (!open(client, p, homeChestNether, "the ender chest")) return;
		ScreenHandler h = p.currentScreenHandler;
		Slot mine = playerSlot(h, p, x -> true);
		if (mine != null) {
			if (!hasRoom(containerSlots(h, p), mine.getStack())) {
				p.closeHandledScreen();
				stop(client, "Your ender chest is full, so I'm not dying with things in my inventory. Make room in it.");
				return;
			}
			quickMove(client, p, h, mine);
			return;
		}
		if (++clicks < 4) return;
		p.closeHandledScreen();
		if (!emptyHanded(p)) {
			// Something landed in the inventory meanwhile.
			if (++tries > 4) {
				stop(client, "Couldn't empty my inventory into the ender chest.");
				return;
			}
			clicks = 0;
			opened = null;
			return;
		}
		enter(State.KILL);
	}

	static BlockPos homeChestNether;
	private static BlockPos chestSpot;

	/**
	 * First trip: put the carried ender chest down beside the portal, but outside the space the game needs clear to
	 * build the next portal in the same place (the frame's width, one either side, four high).
	 */
	private static void placeNetherChest(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		if (chestSpot == null || !world.getBlockState(chestSpot).isReplaceable()) {
			chestSpot = null;
			BlockPos feet = Walker.feet(p);
			double best = Double.MAX_VALUE;
			for (BlockPos q : BlockPos.iterate(feet.add(-4, -1, -4), feet.add(4, 1, 4))) {
				BlockState s = world.getBlockState(q);
				if (!s.isAir() || !world.getBlockState(q.up()).isAir() || !world.getBlockState(q.down()).isSolidBlock(world, q.down())) continue;
				if (inPortalSpace(q) || q.equals(feet) || q.equals(feet.up())) continue;
				double d = q.getSquaredDistance(feet);
				if (d < 2 || d > 12) continue;
				if (d < best) {
					best = d;
					chestSpot = q.toImmutable();
				}
			}
			if (chestSpot == null) {
				stop(client, "Couldn't find a spot for the ender chest here. Place it by hand beside the portal, walk home, and press H.");
				return;
			}
		}
		if (++tries > 40) {
			stop(client, "Couldn't place the ender chest.");
			return;
		}
		if (tries % 8 == 1) place(client, p, world, chestSpot, x -> x.isOf(Items.ENDER_CHEST));
		if (world.getBlockState(chestSpot).isOf(Blocks.ENDER_CHEST)) {
			homeChestNether = chestSpot;
			log("placed the nether ender chest at {}", chestSpot);
			tries = 0;
		}
	}

	/** Inside the space the game checks when it builds a portal where this one was. */
	static boolean inPortalSpace(BlockPos q) {
		if (portalMin == null) return false;
		int a = portalAxis == Direction.Axis.X ? q.getX() - portalMin.getX() : q.getZ() - portalMin.getZ();
		int c = portalAxis == Direction.Axis.X ? q.getZ() - portalMin.getZ() : q.getX() - portalMin.getX();
		int y = q.getY() - portalMin.getY();
		return a >= -1 && a <= 2 && c >= -1 && c <= 1 && y >= -1 && y <= 3;
	}

	private static void kill(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		status = Text.literal("/" + QuarryConfig.INSTANCE.killCommand);
		if (p.isDead() || client.currentScreen instanceof DeathScreen || world.getRegistryKey() == World.OVERWORLD && ticks > 1) {
			cycles++;
			QuarryConfig.INSTANCE.totalCycles++;
			log("cycle {} done: {} mined", cycles, minedThisCycle);
			enter(State.RESPAWN);
			return;
		}
		if (ticks == 1) {
			if (!emptyHanded(p)) {
				enter(State.DEPOSIT);
				return;
			}
			if (Boolean.getBoolean("portalquarry.debug")) {
				for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(3, 2, 3), e -> true)) {
					log("at /kill, a drop near me: {} x{} at {}", e.getStack().getItem(), e.getStack().getCount(), e.getBlockPos());
				}
			}
			p.networkHandler.sendChatCommand(QuarryConfig.INSTANCE.killCommand);
		}
		if (ticks > 600) {
			stop(client, "/" + QuarryConfig.INSTANCE.killCommand + " didn't kill me. Your things are in the ender chest. Set the server's kill command with "
					+ "/portalquarry kill <command>.");
		}
	}

	private static void respawn(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		status = Text.literal("Respawning");
		if (client.currentScreen instanceof DeathScreen) {
			if (ticks >= 22 && ticks % 20 == 2) {
				p.requestRespawn();
				client.setScreen(null);
			}
			return;
		}
		if (p.isDead()) {
			if (ticks > 60 && ticks % 40 == 0) p.requestRespawn();
			if (ticks > 2400) stop(client, "Couldn't respawn.");
			return;
		}
		if (world.getRegistryKey() != World.OVERWORLD) {
			if (ticks > 2400) stop(client, "Respawned in the nether, not at the bed.");
			return;
		}
		BlockPos feet = p.getBlockPos();
		if (!world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4) || ticks < 10) return;
		if (feet.getSquaredDistance(home) > 10 * 10) {
			stop(client, "I respawned away from the bed (at " + feet.toShortString() + "). Is the bed still there and set as your spawn? "
					+ "Your obsidian is in the ender chest.");
			return;
		}
		enter(State.TAKE);
	}

	// ---------------------------------------------------------------- doing things

	/** Gets in reach of a container and opens it. True once its screen is open. */
	private static boolean open(MinecraftClient client, ClientPlayerEntity p, BlockPos pos, String what) {
		ScreenHandler h = p.currentScreenHandler;
		if (opened != null && opened.equals(pos) && h != p.playerScreenHandler && !containerSlots(h, p).isEmpty()) {
			openTries = 0;
			return true;
		}
		if (!reach(client, p, client.world, pos)) return false;
		if (h != p.playerScreenHandler) {
			p.closeHandledScreen();
			wait = 2;
			return false;
		}
		if (opened != null && opened.equals(pos) && ++openWait < 60) return false;
		// Crouching would place the held block on the container instead of opening it.
		client.options.sneakKey.setPressed(false);
		if (p.isSneaking()) return false;
		// A solid block on top stops a chest opening: mine it off.
		BlockPos above = pos.up();
		BlockState top = client.world.getBlockState(above);
		BlockState self = client.world.getBlockState(pos);
		if ((self.isOf(Blocks.ENDER_CHEST) || self.getBlock() instanceof ChestBlock) && top.isSolidBlock(client.world, above)
				&& !top.isOf(Blocks.BEDROCK) && !top.isOf(Blocks.NETHER_PORTAL)) {
			log("clearing {} off the top of {}", top.getBlock(), pos);
			breakBlock(client, p, client.world, above);
			return false;
		}
		holdPick(client, p);
		if (++openTries > 6) {
			openTries = 0;
			stop(client, "Couldn't open " + what + " at " + pos.toShortString() + ". Is something on top of it?");
			return false;
		}
		Vec3d hit = Vec3d.ofCenter(pos).add(0, 0.45, 0);
		look(p, hit);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
		p.swingHand(Hand.MAIN_HAND);
		opened = pos;
		openWait = 0;
		return false;
	}

	private static int openTries, openWait;

	/** Walks until {@code pos} is in reach. True when it is. */
	private static boolean reach(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, BlockPos pos) {
		double range = p.getBlockInteractionRange() - 0.4;
		if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range) {
			if (walkingTo != null) {
				walker.stop(client);
				walkingTo = null;
			}
			return true;
		}
		if (walkingTo == null || !walkingTo.equals(pos) || walker.arrived() && !walker.moving()) {
			double want = range - 0.6;
			Predicate<BlockPos> goal = cell -> Vec3d.ofBottomCenter(cell).add(0, 1.62, 0).squaredDistanceTo(Vec3d.ofCenter(pos)) <= want * want;
			if (++reachTries > 5 || !walker.plan(world, p, goal, bounds(pos, 12, 6))) {
				reachTries = 0;
				stop(client, "Can't get to " + world.getBlockState(pos).getBlock().getName().getString() + " at " + pos.toShortString() + ".");
				return false;
			}
			walkingTo = pos;
		}
		if (!walker.tick(client)) walkingTo = null;
		return false;
	}

	private static int reachTries;

	private static void walkOnto(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, ItemEntity drop) {
		BlockPos cell = drop.getBlockPos();
		if (p.getBoundingBox().expand(1, 0.5, 1).intersects(drop.getBoundingBox())) {
			walker.stop(client);
			return;
		}
		if (walkingTo == null || !walkingTo.equals(cell) || walker.arrived()) {
			Predicate<BlockPos> onto = c -> c.getX() == cell.getX() && c.getZ() == cell.getZ() && Math.abs(c.getY() - cell.getY()) <= 1;
			// Can't stand on it (on lava, a ledge)? Right beside it: the pickup box reaches about a block out.
			Predicate<BlockPos> beside = c -> Math.abs(c.getX() - cell.getX()) + Math.abs(c.getZ() - cell.getZ()) == 1
					&& Math.abs(c.getY() - cell.getY()) <= 1;
			boolean planned = walker.plan(world, p, onto, bounds(cell, 8, 4));
			if (!planned) planned = walker.plan(world, p, beside, bounds(cell, 8, 4));
			if (!planned) {
				// Out of reach: wait for it (or for the collect timeout).
				walkingTo = null;
				return;
			}
			walkingTo = cell;
		}
		if (!walker.tick(client)) walkingTo = null;
	}

	/** Breaks a block with the best pickaxe. True the tick it's gone. */
	private static boolean breakBlock(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		if (s.isAir() || s.isReplaceable()) {
			boolean was = pos.equals(breaking);
			breaking = null;
			return was;
		}
		if (!reach(client, p, world, pos)) return false;
		Direction face = Direction.getFacing(p.getEyePos().subtract(Vec3d.ofCenter(pos)));
		look(p, Vec3d.ofCenter(pos).add(Vec3d.of(face.getVector()).multiply(0.5)));
		// The pickaxe must be in hand every tick, not just at the start: anything that changes the held item
		// mid-break (placing filler, a pickup into the hotbar) would otherwise leave us mining obsidian by hand.
		if (!holdPick(client, p)) {
			if (++breakTicks > 400) stop(client, "No pickaxe to mine with.");
			return false;
		}
		if (!pos.equals(breaking)) {
			client.interactionManager.attackBlock(pos, face);
			breaking = pos;
			breakTicks = 0;
		} else {
			if (++breakTicks > 400) {
				stop(client, "Couldn't break the block at " + pos.toShortString() + ".");
				return false;
			}
			client.interactionManager.updateBlockBreakingProgress(pos, face);
		}
		p.swingHand(Hand.MAIN_HAND);
		if (world.getBlockState(pos).isAir()) {
			breaking = null;
			return true;
		}
		return false;
	}

	/** Puts the best pickaxe in the main hand. True once it's there. */
	private static boolean holdPick(MinecraftClient client, ClientPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		ItemStack pick = bestPick(inv, 5);
		if (pick == null) {
			// Left in the offhand (by mending, say)? Swap it back to the hotbar.
			if (usable(p.getOffHandStack(), 5) && p.currentScreenHandler == p.playerScreenHandler) {
				client.interactionManager.clickSlot(p.playerScreenHandler.syncId, invToScreenSlot(inv.getSelectedSlot()), 40, SlotActionType.SWAP, p);
			}
			return false;
		}
		if (p.getMainHandStack() == pick) return true;
		if (p.currentScreenHandler != p.playerScreenHandler) return false;
		select(client, p, indexOf(inv, pick));
		return p.getMainHandStack() == pick;
	}

	/** Places a block into {@code cell}, against the block under it. */
	private static void place(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, BlockPos cell, Predicate<ItemStack> item) {
		placeAgainst(client, p, world, cell, cell.down(), item);
	}

	private static void placeAgainst(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, BlockPos cell, BlockPos against, Predicate<ItemStack> item) {
		int slot = slot(p.getInventory(), item);
		if (slot < 0) return;
		if (!reach(client, p, world, cell)) return;
		select(client, p, slot);
		Direction face = Direction.getFacing(Vec3d.ofCenter(cell).subtract(Vec3d.ofCenter(against)));
		Vec3d hit = Vec3d.ofCenter(against).add(Vec3d.of(face.getVector()).multiply(0.5));
		look(p, hit);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
		p.swingHand(Hand.MAIN_HAND);
	}

	private static void quickMove(MinecraftClient client, ClientPlayerEntity p, ScreenHandler h, Slot s) {
		client.interactionManager.clickSlot(h.syncId, h.slots.indexOf(s), 0, SlotActionType.QUICK_MOVE, p);
		clicks = 0;
	}

	/** Puts inventory slot {@code slot} in hand, swapping it into the hotbar if need be. */
	private static void select(MinecraftClient client, ClientPlayerEntity p, int slot) {
		PlayerInventory inv = p.getInventory();
		if (slot < 0) return;
		if (slot < PlayerInventory.getHotbarSize()) {
			inv.setSelectedSlot(slot);
			return;
		}
		int hotbar = inv.getSelectedSlot();
		client.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, hotbar, SlotActionType.SWAP, p);
	}

	/** The player screen's slot number for an inventory index (hotbar 0-8 is 36-44). */
	private static int invToScreenSlot(int index) {
		return index < 9 ? 36 + index : index;
	}

	private static void look(ClientPlayerEntity p, Vec3d at) {
		Vec3d d = at.subtract(p.getEyePos());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		p.setYaw((float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90);
		p.setPitch((float) -(MathHelper.atan2(d.y, flat) * 180 / Math.PI));
	}

	private static int[] bounds(BlockPos c, int r, int h) {
		return new int[] {c.getX() - r, c.getY() - h, c.getZ() - r, c.getX() + r, c.getY() + h, c.getZ() + r};
	}

	// ---------------------------------------------------------------- items

	/** What the loop may carry: everything else would have to go in the ender chest. */
	static boolean ours(ItemStack s) {
		return s.isOf(Items.OBSIDIAN) || isPick(s) || filler(s) || s.isOf(Items.EXPERIENCE_BOTTLE) || s.isOf(Items.ENDER_CHEST);
	}

	/** Blocks that fill the floor holes: solid, cheap, and what nether ground is made of. */
	static boolean filler(ItemStack s) {
		return s.isOf(Items.COBBLESTONE) || s.isOf(Items.NETHERRACK) || s.isOf(Items.COBBLED_DEEPSLATE) || s.isOf(Items.BLACKSTONE)
				|| s.isOf(Items.STONE) || s.isOf(Items.BASALT) || s.isOf(Items.DIRT);
	}

	static boolean isPick(ItemStack s) {
		return !s.isEmpty() && s.isIn(ItemTags.PICKAXES);
	}

	static boolean isBox(ItemStack s) {
		return !s.isEmpty() && Block.getBlockFromItem(s.getItem()) instanceof ShulkerBoxBlock;
	}

	static boolean emptyBox(ItemStack s) {
		if (!isBox(s)) return false;
		ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
		return c == null || c.streamNonEmpty().findAny().isEmpty();
	}

	/** Can mine obsidian (diamond or netherite) and has more than {@code min} uses left. */
	static boolean usable(ItemStack s, int min) {
		if (!isPick(s) || s.getMiningSpeedMultiplier(Blocks.OBSIDIAN.getDefaultState()) < 8 || !s.isSuitableFor(Blocks.OBSIDIAN.getDefaultState())) return false;
		return !s.isDamageable() || s.getMaxDamage() - s.getDamage() > min;
	}

	/** The fastest usable pickaxe for obsidian in the inventory, or null. */
	static ItemStack bestPick(PlayerInventory inv, int min) {
		ItemStack best = null;
		float speed = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (!usable(s, min)) continue;
			float sp = s.getMiningSpeedMultiplier(Blocks.OBSIDIAN.getDefaultState()) + efficiency(s) * 10;
			if (best == null || sp > speed) {
				best = s;
				speed = sp;
			}
		}
		return best;
	}

	private static int efficiency(ItemStack s) {
		for (RegistryEntry<Enchantment> e : s.getEnchantments().getEnchantments()) {
			if (e.matchesKey(Enchantments.EFFICIENCY)) return s.getEnchantments().getLevel(e);
		}
		return 0;
	}

	static boolean hasMending(ItemStack s) {
		for (RegistryEntry<Enchantment> e : s.getEnchantments().getEnchantments()) if (e.matchesKey(Enchantments.MENDING)) return true;
		return false;
	}

	static boolean needsMend(ItemStack s) {
		if (!s.isDamageable()) return false;
		int left = s.getMaxDamage() - s.getDamage();
		return left * 100 < s.getMaxDamage() * QuarryConfig.INSTANCE.mendBelow || left < QuarryConfig.INSTANCE.minDurability;
	}

	static int count(PlayerInventory inv, Predicate<ItemStack> what) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (what.test(inv.getStack(i))) n += inv.getStack(i).getCount();
		return n;
	}

	private static int slot(PlayerInventory inv, Predicate<ItemStack> what) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (!inv.getStack(i).isEmpty() && what.test(inv.getStack(i))) return i;
		return -1;
	}

	private static int indexOf(PlayerInventory inv, ItemStack s) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i) == s) return i;
		return -1;
	}

	private static boolean hasRoom(PlayerInventory inv, ItemStack s) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack t = inv.getStack(i);
			if (t.isEmpty() || ItemStack.areItemsAndComponentsEqual(t, s) && t.getCount() < t.getMaxCount()) return true;
		}
		return false;
	}

	private static boolean hasRoom(List<Slot> slots, ItemStack s) {
		for (Slot t : slots) {
			ItemStack st = t.getStack();
			if (st.isEmpty() && t.canInsert(s) || ItemStack.areItemsAndComponentsEqual(st, s) && st.getCount() < st.getMaxCount()) return true;
		}
		return false;
	}

	/** Nothing at all on us: inventory, armour, offhand. */
	static boolean emptyHanded(ClientPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (!inv.getStack(i).isEmpty()) return false;
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) continue;
			if (!p.getEquippedStack(slot).isEmpty()) return false;
		}
		return p.currentScreenHandler.getCursorStack().isEmpty();
	}

	/** The open container's own slots (not the player's). */
	private static List<Slot> containerSlots(ScreenHandler h, ClientPlayerEntity p) {
		List<Slot> out = new ArrayList<>();
		for (Slot s : h.slots) if (s.inventory != p.getInventory()) out.add(s);
		return out;
	}

	private static Slot playerSlot(ScreenHandler h, ClientPlayerEntity p, Predicate<ItemStack> what) {
		for (Slot s : h.slots) {
			if (s.inventory == p.getInventory() && s.getIndex() < PlayerInventory.MAIN_SIZE && !s.getStack().isEmpty() && what.test(s.getStack())) return s;
		}
		return null;
	}

	private static ItemEntity nearestDrop(ClientWorld world, ClientPlayerEntity p, double r, Predicate<ItemStack> what) {
		Box near = p.getBoundingBox().expand(r, 5, r);
		return world.getEntitiesByClass(ItemEntity.class, near, e -> what.test(e.getStack())).stream()
				.min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p))).orElse(null);
	}

	private static BlockPos nearest(ClientWorld world, BlockPos at, int r, Predicate<BlockState> what) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.iterate(at.add(-r, -r, -r), at.add(r, r, r))) {
			if (!what.test(world.getBlockState(q))) continue;
			double d = q.getSquaredDistance(at);
			if (d < bestD) {
				bestD = d;
				best = q.toImmutable();
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- reporting

	static double perHour() {
		long ms = System.currentTimeMillis() - startedAt;
		return ms < 60_000 ? 0 : stored * 3_600_000.0 / ms;
	}

	static BlockPos box() {
		return box;
	}

	private static void chat(ClientPlayerEntity p, Text t) {
		p.sendMessage(Text.literal("[Portal Quarry] ").formatted(Formatting.GOLD).append(t), false);
	}

	private static void log(String msg, Object... args) {
		if (Boolean.getBoolean("portalquarry.debug")) PortalQuarry.LOGGER.info("[quarry] " + msg, args);
	}
}
