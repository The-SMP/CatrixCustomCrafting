package me.cat6666.catrixcustomcrafting.gui;

import java.util.UUID;

/** Per-player, short-lived editor state. */
public final class EditorState {
    public final UUID recipeId;
    public boolean exactMatch;

    public EditorState(UUID recipeId, boolean exactMatch) {
        this.recipeId = recipeId;
        this.exactMatch = exactMatch;
    }
}
