package com.greysworkshop.portalquarry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Portal Quarry's settings and lifetime totals, in config/portalquarry.json. */
final class QuarryConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static QuarryConfig INSTANCE = new QuarryConfig();
	private static Path file = FabricLoader.getInstance().getConfigDir().resolve("portalquarry.json");

	/** The command that kills you in the nether, without the slash. */
	String killCommand = "kill";
	/** Stop when another player comes within {@link #playerRange} blocks. */
	boolean stopNearPlayers = true;
	int playerRange = 48;
	/** Mend the pickaxe with XP bottles at the bed when it drops below {@link #mendBelow} percent. */
	boolean autoMend = true;
	int mendBelow = 25;
	/**
	 * Never take a pickaxe into the nether with fewer uses left than this: a cycle takes about 20 (fewer with
	 * Unbreaking), so it can't break mid-cycle.
	 */
	int minDurability = 60;
	/** Mine the portal's floor too (4 more obsidian a cycle) and fill the holes with cobblestone or netherrack. */
	boolean mineFloor = true;
	boolean hud = true;
	long totalObsidian;
	long totalCycles;

	static void load() {
		try {
			if (Files.exists(file)) {
				QuarryConfig c = GSON.fromJson(Files.readString(file), QuarryConfig.class);
				if (c != null) INSTANCE = c;
			}
		} catch (IOException | RuntimeException e) {
			PortalQuarry.LOGGER.warn("Could not read {}", file, e);
		}
		QuarryConfig c = INSTANCE;
		if (c.killCommand == null || c.killCommand.isBlank()) c.killCommand = "kill";
		c.killCommand = c.killCommand.strip();
		if (c.killCommand.startsWith("/")) c.killCommand = c.killCommand.substring(1);
		c.playerRange = Math.max(8, Math.min(256, c.playerRange));
		c.mendBelow = Math.max(5, Math.min(95, c.mendBelow));
		c.minDurability = Math.max(25, Math.min(500, c.minDurability));
	}

	static void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(INSTANCE));
		} catch (IOException e) {
			PortalQuarry.LOGGER.warn("Could not save {}", file, e);
		}
	}

	/** For the self-test: settings in a file of its own. */
	static void useFile(Path f) {
		file = f;
		INSTANCE = new QuarryConfig();
	}
}
