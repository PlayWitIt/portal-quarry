package com.greysworkshop.portalquarry;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Portal Quarry: obsidian from the portals the game builds for you, on a loop. */
public final class PortalQuarry implements ClientModInitializer {
	public static final String MOD_ID = "portalquarry";
	static final Logger LOGGER = LoggerFactory.getLogger("portalquarry");
	static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		QuarryConfig.load();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.portalquarry.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));
		ClientTickEvents.END_CLIENT_TICK.register(PortalQuarry::tick);
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), Hud::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> Commands.register(dispatcher));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> Quarry.stop(client, "Stopped: disconnected.")));
		if (Boolean.getBoolean("portalquarry.practical")) {
			try {
				Class.forName("com.greysworkshop.portalquarry.PracticalTest").getDeclaredMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Practical test missing", e);
			}
		} else if (Boolean.getBoolean("portalquarry.selftest")) {
			try {
				Class.forName("com.greysworkshop.portalquarry.SelfTest").getDeclaredMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test missing", e);
			}
		}
	}

	private static void tick(MinecraftClient client) {
		while (toggleKey.wasPressed()) {
			if (client.currentScreen == null) Quarry.toggle(client);
		}
		Quarry.tick(client);
	}
}
