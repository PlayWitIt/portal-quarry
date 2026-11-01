package com.greysworkshop.portalquarry;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** A few lines at the top left while the loop runs. */
final class Hud {
	private Hud() {}

	static void render(DrawContext context, RenderTickCounter counter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!QuarryConfig.INSTANCE.hud || client.player == null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
		if (!Quarry.running()) return;
		int y = 4;
		for (Text line : lines(client)) {
			int w = client.textRenderer.getWidth(line);
			context.fill(2, y - 2, 6 + w, y + 9, 0x90000000);
			context.drawTextWithShadow(client.textRenderer, line, 4, y, 0xFFFFFFFF);
			y += 11;
		}
	}

	static List<Text> lines(MinecraftClient client) {
		List<Text> lines = new ArrayList<>();
		lines.add(Text.literal("Portal Quarry · ").formatted(Formatting.LIGHT_PURPLE).append(Quarry.status.copy().formatted(Formatting.WHITE)));
		lines.add(Text.literal("Cycles " + Quarry.cycles + " · stored " + Quarry.stored + " obsidian · " + Math.round(Quarry.perHour()) + "/hour")
				.formatted(Formatting.GRAY));
		ItemStack pick = Quarry.bestPick(client.player.getInventory(), 0);
		if (pick != null && pick.isDamageable()) {
			int left = pick.getMaxDamage() - pick.getDamage();
			Formatting f = left < QuarryConfig.INSTANCE.minDurability * 2 ? Formatting.RED : Quarry.needsMend(pick) ? Formatting.YELLOW : Formatting.GRAY;
			lines.add(Text.literal("Pickaxe " + left + "/" + pick.getMaxDamage() + (Quarry.hasMending(pick) ? " · Mending" : "")).formatted(f));
		}
		return lines;
	}
}
