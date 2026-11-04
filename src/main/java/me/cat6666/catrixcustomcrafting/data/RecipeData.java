package me.cat6666.catrixcustomcrafting.data;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Immutable recipe definition kept in memory and persisted to one UUID YAML file. */
public final class RecipeData {
    public final UUID id;
    public final ItemStack[] ingredients;
    public final ItemStack result;
    public final boolean exactMatch;

    public RecipeData(UUID id, ItemStack[] ingredients, ItemStack result, boolean exactMatch) {
        this.id = id;
        this.ingredients = ingredients;
        this.result = result;
        this.exactMatch = exactMatch;
    }
}
