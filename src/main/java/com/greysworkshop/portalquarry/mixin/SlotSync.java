package com.greysworkshop.portalquarry.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Tells the server which hotbar slot is held, at once. Vanilla only does it once a tick, or when an item is used; a
 * block that breaks on the first hit would otherwise be broken with the item held before (the wrong enchantment).
 * Only sends when the slot really changed, so no duplicate packets.
 */
@Mixin(ClientPlayerInteractionManager.class)
public interface SlotSync {
	@Invoker("syncSelectedSlot")
	void portalquarry$syncSelectedSlot();
}
