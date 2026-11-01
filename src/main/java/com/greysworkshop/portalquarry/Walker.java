package com.greysworkshop.portalquarry;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Gets the player where the quarry needs them: finds a path over the blocks that are there (stepping up one, dropping
 * up to five, more than three only if there's no other way, never into lava or fire), pillaring up where it must, and
 * walks it by pressing the movement keys. In creative it flies instead: up above what's in the way, across, and down.
 */
final class Walker {
	private static final int MAX_NODES = 40_000;

	private List<BlockPos> path = List.of();
	private int at;
	private Vec3d flyTo;
	private int stuckTicks;
	private boolean moving;

	boolean moving() {
		return moving;
	}

	List<BlockPos> path() {
		return path.subList(Math.min(at, path.size()), path.size());
	}

	/** A cell the player's body can be in. */
	static boolean passable(ClientWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		if (dangerous(s)) return false;
		return s.getCollisionShape(world, pos).isEmpty();
	}

	/** A cell the player can stand in: anything up to half a block high (carpet, snow, slabs) counts as floor. */
	private static boolean lowFloor(ClientWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		if (dangerous(s)) return false;
		VoxelShape shape = s.getCollisionShape(world, pos);
		return !shape.isEmpty() && shape.getMax(net.minecraft.util.math.Direction.Axis.Y) <= 0.5;
	}

	private static boolean dangerous(BlockState s) {
		return s.isOf(Blocks.LAVA) || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.MAGMA_BLOCK)
				|| s.isOf(Blocks.CACTUS) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.COBWEB)
				|| s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE) || s.isOf(Blocks.WITHER_ROSE) || s.isOf(Blocks.POINTED_DRIPSTONE);
	}

	/** Whether the player can stand with their feet in {@code feet}. */
	static boolean standable(ClientWorld world, BlockPos feet) {
		boolean feetOk = passable(world, feet) || lowFloor(world, feet);
		if (!feetOk || !passable(world, feet.up())) return false;
		if (lowFloor(world, feet)) return true;
		BlockPos below = feet.down();
		BlockState s = world.getBlockState(below);
		VoxelShape shape = s.getCollisionShape(world, below);
		// Not over a carpet: that's standing in the carpet's cell.
		return !dangerous(s) && !shape.isEmpty() && shape.getMax(net.minecraft.util.math.Direction.Axis.Y) > 0.5;
	}

	/** Where the player's feet are, as a cell: the carpet they stand on, or the air above a block. */
	static BlockPos feet(ClientPlayerEntity player) {
		return BlockPos.ofFloored(player.getX(), player.getY() + 0.45, player.getZ());
	}

	/**
	 * Finds a path from where the player is to any cell {@code goal} accepts, inside {@code bounds} (minX, minY, minZ,
	 * maxX, maxY, maxZ). False if there's none.
	 */
	boolean plan(ClientWorld world, ClientPlayerEntity player, Predicate<BlockPos> goal, int[] bounds) {
		return plan(world, player, goal, bounds, null, null);
	}

	/**
	 * As above; with {@code tower} (null for none), it may also pillar up: jump and place a block under itself, in cells
	 * {@code tower} allows. That's how it reaches blocks higher than anything it can walk up to.
	 */
	boolean plan(ClientWorld world, ClientPlayerEntity player, Predicate<BlockPos> goal, int[] bounds, Predicate<BlockPos> tower,
			Predicate<BlockPos> dig) {
		flyTo = null;
		towerCell = null;
		BlockPos start = feet(player);
		if (goal.test(start)) {
			path = List.of();
			at = 0;
			return true;
		}
		record Node(BlockPos pos, double g, double f) {}
		Map<BlockPos, Double> best = new HashMap<>();
		Map<BlockPos, BlockPos> from = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		open.add(new Node(start, 0, 0));
		best.put(start, 0.0);
		int expanded = 0;
		BlockPos found = null;
		// Cells it would stand in on top of a block it placed itself.
		java.util.Set<BlockPos> raised = new java.util.HashSet<>();
		while (!open.isEmpty() && expanded < MAX_NODES) {
			Node n = open.poll();
			if (n.g > best.getOrDefault(n.pos, Double.MAX_VALUE)) continue;
			expanded++;
			if (goal.test(n.pos) && (standable(world, n.pos) || raised.contains(n.pos))) {
				found = n.pos;
				break;
			}
			// Dig down one: break the block we stand on (the top of a pillar, say), where dig allows it.
			BlockPos down = n.pos.down();
			if (dig != null && down.getY() >= bounds[1] && dig.test(down) && !passable(world, down)) {
				double g = n.g + 3;
				if (g < best.getOrDefault(down, Double.MAX_VALUE)) {
					best.put(down, g);
					from.put(down, n.pos);
					open.add(new Node(down, g, g));
				}
			}
			// Pillar up one: the cell we're in gets a block, we stand on it.
			BlockPos up = n.pos.up();
			if (tower != null && up.getY() <= bounds[4] && tower.test(n.pos) && passable(world, n.pos) && passable(world, up)
					&& passable(world, up.up())) {
				double g = n.g + 4;
				if (g < best.getOrDefault(up, Double.MAX_VALUE)) {
					best.put(up, g);
					from.put(up, n.pos);
					raised.add(up);
					open.add(new Node(up, g, g));
				}
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					for (int dy = 1; dy >= -5; dy--) {
						BlockPos next = n.pos.add(dx, dy, dz);
						if (next.getX() < bounds[0] || next.getY() < bounds[1] || next.getZ() < bounds[2]
								|| next.getX() > bounds[3] || next.getY() > bounds[4] || next.getZ() > bounds[5]) continue;
						if (!canMove(world, n.pos, next, dx, dy, dz)) continue;
						// Drops over three blocks hurt a little: only when nothing else will do.
							double cost = (dx != 0 && dz != 0 ? 1.414 : 1) + (dy > 0 ? 0.6 : dy < -3 ? 8 : dy < 0 ? -dy * 0.25 : 0);
						if (!world.getBlockState(next).getFluidState().isEmpty()) cost += 3;
						double g = n.g + cost;
						if (g < best.getOrDefault(next, Double.MAX_VALUE)) {
							best.put(next, g);
							from.put(next, n.pos);
							open.add(new Node(next, g, g));
						}
						break;
					}
				}
			}
		}
		if (found == null) {
			path = List.of();
			return false;
		}
		List<BlockPos> list = new ArrayList<>();
		for (BlockPos p = found; p != null && !p.equals(start); p = from.get(p)) list.add(0, p);
		path = list;
		at = 0;
		stuckTicks = 0;
		return true;
	}

	/** One step, from a cell to a neighbouring one up to one higher or five lower. Takes the first dy that works. */
	private static boolean canMove(ClientWorld world, BlockPos a, BlockPos b, int dx, int dy, int dz) {
		if (!standable(world, b)) return false;
		if (dy > 0 && !passable(world, a.up(2))) return false;
		// A drop: room to fall into the landing cell from our height.
		if (dy < 0) {
			for (int y = b.getY() + 2; y <= a.getY() + 1; y++) if (!passable(world, new BlockPos(b.getX(), y, b.getZ()))) return false;
		}
		if (dx != 0 && dz != 0) {
			// No cutting corners.
			for (BlockPos side : new BlockPos[] {a.add(dx, 0, 0), a.add(0, 0, dz)}) {
				BlockPos s2 = side.up(Math.max(0, dy));
				if (!passable(world, s2) || !passable(world, s2.up())) return false;
			}
		}
		return true;
	}

	/** Where flight has to rise to before heading across: just over everything in the way. */
	private double cruise;

	/**
	 * Flies (creative) to a point: straight up (or down) to just over whatever is between here and there, across, then
	 * down. {@code top}: no block in the way is higher than this.
	 */
	void flyTo(ClientWorld world, ClientPlayerEntity player, Vec3d target, int top) {
		path = List.of();
		flyTo = target;
		stuckTicks = 0;
		Vec3d from = player.getEntityPos();
		double clear = Math.max(target.y, from.y);
		double dist = Math.hypot(target.x - from.x, target.z - from.z);
		int steps = (int) Math.ceil(dist * 3) + 1;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		// The highest block under the player's body anywhere along the way.
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			double x = from.x + (target.x - from.x) * t, z = from.z + (target.z - from.z) * t;
			for (double ox = -0.35; ox <= 0.35; ox += 0.7) {
				for (double oz = -0.35; oz <= 0.35; oz += 0.7) {
					int bx = MathHelper.floor(x + ox), bz = MathHelper.floor(z + oz);
					for (int y = top; y >= (int) Math.floor(Math.min(from.y, target.y)) - 1; y--) {
						pos.set(bx, y, bz);
						if (!passable(world, pos)) {
							clear = Math.max(clear, y + 1.05);
							break;
						}
					}
				}
			}
		}
		cruise = clear;
	}

	boolean arrived() {
		return path.isEmpty() && flyTo == null || flyTo == null && at >= path.size();
	}

	/** Moves the player one tick along the path. False when stuck. */
	boolean tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) return false;
		if (flyTo != null) return fly(client, player);
		if (at >= path.size()) {
			stop(client);
			return true;
		}
		BlockPos next = path.get(at);
		BlockPos here = feet(player);
		if (next.getX() == here.getX() && next.getZ() == here.getZ() && next.getY() == here.getY() + 1 && !standable(client.world, next)
				|| towerCell != null && towerCell.equals(next.down()) && passable(client.world, towerCell)) {
			if (towerCell == null && Boolean.getBoolean("portalquarry.debug")) PortalQuarry.LOGGER.info("[walker] pillaring up from {}", next.down());
			towerCell = next.down();
			return tower(client, player, towerCell);
		}
		towerCell = null;
		if (next.getX() == here.getX() && next.getZ() == here.getZ() && next.getY() == here.getY() - 1 && !passable(client.world, next)) {
			// Digging down: stand still over it and break it.
			release(client);
			player.setVelocity(player.getVelocity().multiply(0.3, 1, 0.3));
			breakUnder.test(next);
			moving = true;
			return checkStuck(client, player, Vec3d.ofBottomCenter(next));
		}
		Vec3d aim = Vec3d.ofBottomCenter(next);
		double dx = aim.x - player.getX(), dz = aim.z - player.getZ();
		double flat = Math.sqrt(dx * dx + dz * dz);
		BlockPos feet = feet(player);
		if (flat < 0.3 && Math.abs(feet.getY() - next.getY()) <= 1 || feet.equals(next) && flat < 0.45) {
			at++;
			if (at >= path.size()) {
				stop(client);
				return true;
			}
			return tick(client);
		}
		face(player, aim);
		press(client, true, next.getY() > feet.getY() && (player.isOnGround() || player.horizontalCollision), false);
		// Slow down onto a narrow last step so it doesn't overshoot.
		client.options.sneakKey.setPressed(at == path.size() - 1 && flat < 1.2);
		moving = true;
		return checkStuck(client, player, aim);
	}

	/** Flying speed, blocks per tick: about creative's own. */
	private static final double FLY_SPEED = 0.55, CLIMB_SPEED = 0.45;

	/** Steers flight by setting the player's velocity: up to the cruising height, across, then down, slowing to a stop. */
	private boolean fly(MinecraftClient client, ClientPlayerEntity player) {
		if (!player.getAbilities().allowFlying) {
			flyTo = null;
			return false;
		}
		if (!player.getAbilities().flying) {
			player.getAbilities().flying = true;
			player.sendAbilitiesUpdate();
		}
		release(client);
		Vec3d pos = player.getEntityPos();
		double dx = flyTo.x - pos.x, dz = flyTo.z - pos.z, flat = Math.sqrt(dx * dx + dz * dz);
		boolean over = flat <= 0.15;
		double wantY = over ? flyTo.y : cruise;
		double dy = wantY - pos.y;
		if (over && Math.abs(dy) < 0.1) {
			player.setVelocity(Vec3d.ZERO);
			flyTo = null;
			moving = false;
			return true;
		}
		double vy = Math.max(-CLIMB_SPEED, Math.min(CLIMB_SPEED, dy * 0.5));
		double vx = 0, vz = 0;
		// Across only once high enough (going up and across at once near the top is fine).
		if (!over && pos.y >= cruise - 0.6) {
			double speed = Math.min(FLY_SPEED, flat * 0.45);
			vx = dx / flat * speed;
			vz = dz / flat * speed;
			face(player, new Vec3d(flyTo.x, player.getEyeY(), flyTo.z));
		}
		player.setVelocity(vx, vy, vz);
		moving = true;
		return checkStuck(client, player, flyTo == null ? pos : flyTo);
	}

	private static void release(MinecraftClient client) {
		client.options.forwardKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
		client.options.sprintKey.setPressed(false);
	}

	boolean towering() {
		return towerCell != null;
	}

	/** Where a pillar-up step puts its block, while one's under way. */
	private BlockPos towerCell;
	/** Places a block in a cell (against the block under it); true if it did. Set by the builder. */
	Predicate<BlockPos> placeUnder = p -> false;
	/** Starts breaking the block in a cell (the one under the player). Set by the builder. */
	Predicate<BlockPos> breakUnder = p -> false;

	/** One pillar-up step: centre over the cell, look down, jump, and put a block under at the top of the jump. */
	private boolean tower(MinecraftClient client, ClientPlayerEntity player, BlockPos cell) {
		release(client);
		Vec3d centre = Vec3d.ofBottomCenter(cell);
		double dx = centre.x - player.getX(), dz = centre.z - player.getZ();
		Vec3d v = player.getVelocity();
		player.setVelocity(dx * 0.4, v.y, dz * 0.4);
		player.setPitch(90);
		if (player.isOnGround() && player.getY() < cell.getY() + 0.5) client.options.jumpKey.setPressed(true);
		if (player.getY() >= cell.getY() + 1.0 && passable(client.world, cell)) placeUnder.test(cell);
		moving = true;
		return checkStuck(client, player, centre.add(0, 1, 0));
	}

	/** Where the current step is headed, how close it has got, and how long since it got closer. */
	private Vec3d heading;
	private double closest;

	/**
	 * Stuck: no closer to where this step is headed for three seconds (jumping on the spot against a wall doesn't
	 * count as getting anywhere).
	 */
	private boolean checkStuck(MinecraftClient client, ClientPlayerEntity player, Vec3d towards) {
		double d = player.getEntityPos().distanceTo(towards);
		if (heading == null || heading.squaredDistanceTo(towards) > 0.01) {
			heading = towards;
			closest = d;
			stuckTicks = 0;
		} else if (d < closest - 0.05) {
			closest = d;
			stuckTicks = 0;
		} else {
			stuckTicks++;
		}
		if (stuckTicks > 60) {
			heading = null;
			stop(client);
			path = List.of();
			flyTo = null;
			return false;
		}
		return true;
	}

	private static void face(ClientPlayerEntity player, Vec3d target) {
		double dx = target.x - player.getX(), dz = target.z - player.getZ();
		float yaw = (float) (MathHelper.atan2(dz, dx) * 180 / Math.PI) - 90;
		player.setYaw(yaw);
		player.setHeadYaw(yaw);
	}

	private static void press(MinecraftClient client, boolean forward, boolean jump, boolean sprint) {
		client.options.forwardKey.setPressed(forward);
		client.options.jumpKey.setPressed(jump);
		client.options.sprintKey.setPressed(sprint);
	}

	void stop(MinecraftClient client) {
		if (moving) {
			client.options.forwardKey.setPressed(false);
			client.options.jumpKey.setPressed(false);
			client.options.sneakKey.setPressed(false);
			client.options.sprintKey.setPressed(false);
		}
		moving = false;
	}

	void clear(MinecraftClient client) {
		stop(client);
		path = List.of();
		flyTo = null;
	}
}
