package me.cat6666.catrixcustomcrafting.gui;

import me.cat6666.catrixcustomcrafting.CatrixCustomCrafting;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Holder signature used to identify plugin-owned inventories. */
public final class GuiHolder implements InventoryHolder {
    public final CatrixCustomCrafting plugin;
    public Inventory inventory;

    public GuiHolder(CatrixCustomCrafting plugin) {
        this.plugin = plugin;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
