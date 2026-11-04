package me.cat6666.catrixcustomcrafting.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;

public final class RecipeStore {
    private record Change(RecipeData recipe) {}
    private final JavaPlugin plugin;
    private final Path directory;
    private final Map<UUID, Change> pending = new LinkedHashMap<>();
    private final Map<UUID, Change> queued = new HashMap<>();

    public RecipeStore(JavaPlugin plugin) {
        this.plugin = plugin;
        directory = plugin.getDataFolder().toPath().resolve("custom");
    }

    public Map<UUID, RecipeData> load() {
        Map<UUID, RecipeData> recipes = new LinkedHashMap<>();
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.yml")) {
                for (Path file : files) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        String name = file.getFileName().toString();
                        UUID id = UUID.fromString(name.substring(0, name.length() - 4));
                        if (!name.equalsIgnoreCase(id + ".yml")) continue;
                        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                        ItemStack[] ingredients = new ItemStack[9];
                        boolean hasIngredient = false;
                        for (int i = 0; i < ingredients.length; i++) {
                            ItemStack item = yaml.getItemStack("ingredients." + i);
                            if (item == null || item.getType().isAir()) continue;
                            ingredients[i] = item.asOne();
                            hasIngredient = true;
                        }
                        ItemStack result = yaml.getItemStack("result");
                        if (hasIngredient && result != null && !result.getType().isAir()) {
                            recipes.put(id, new RecipeData(id, ingredients, result.asOne(), yaml.getBoolean("exact-match", true)));
                        }
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("略過無效配方檔 " + file.getFileName());
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("無法讀取配方目錄：" + e.getMessage());
        }
        return recipes;
    }

    public synchronized void markChanged(UUID id, RecipeData recipe) {
        pending.put(id, new Change(recipe));
    }

    public synchronized boolean hasPendingChanges() {
        return !pending.isEmpty();
    }

    public synchronized void flush(Executor io) {
        Map<UUID, Change> batch = new LinkedHashMap<>();
        pending.forEach((id, change) -> {
            if (queued.get(id) != change) {
                batch.put(id, change);
                queued.put(id, change);
            }
        });
        if (!batch.isEmpty()) io.execute(() -> batch.forEach(this::write));
    }

    private void write(UUID id, Change change) {
        boolean saved = false;
        Path target = directory.resolve(id + ".yml");
        try {
            if (change.recipe() == null) {
                Files.deleteIfExists(target);
            } else {
                RecipeData recipe = change.recipe();
                YamlConfiguration yaml = new YamlConfiguration();
                for (int i = 0; i < recipe.ingredients.length; i++) {
                    if (recipe.ingredients[i] != null) yaml.set("ingredients." + i, recipe.ingredients[i]);
                }
                yaml.set("result", recipe.result);
                yaml.set("exact-match", recipe.exactMatch);
                Path temporary = Files.createTempFile(directory, id.toString(), ".tmp");
                try {
                    yaml.save(temporary.toFile());
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            saved = true;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("配方保存失敗：" + id + " " + e.getMessage());
        } finally {
            synchronized (this) {
                if (queued.get(id) == change) queued.remove(id);
                if (saved && pending.get(id) == change) pending.remove(id);
            }
        }
    }
}
