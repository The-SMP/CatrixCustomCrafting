package me.cat6666.catrixcustomcrafting;

import me.cat6666.catrixcustomcrafting.data.RecipeData;
import me.cat6666.catrixcustomcrafting.update.VersionChecker;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

final class CraftCommand implements TabExecutor {
    private enum Action {
        CREATE("create", true), EDIT("create", true), LIST("use", false),
        OPEN("use", true), RELOAD("reload", false), VERSION("version", false);

        final String permission;
        final boolean playerOnly;

        Action(String permission, boolean playerOnly) {
            this.permission = permission;
            this.playerOnly = playerOnly;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Action parse(String label) {
            for (Action action : values()) {
                if (action.label().equalsIgnoreCase(label)) return action;
            }
            return null;
        }
    }

    private final CatrixCustomCrafting plugin;
    private final VersionChecker versions;

    CraftCommand(CatrixCustomCrafting plugin, VersionChecker versions) {
        this.plugin = plugin;
        this.versions = versions;
    }

    private boolean allowed(CommandSender sender, Action action) {
        if (action == Action.VERSION) return versions.canCheck(sender);
        String fallback = "catrixcustomcrafting." + action.permission;
        return sender.hasPermission(plugin.getConfig().getString("permissions." + action.permission, fallback));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) plugin.tell(sender, "player-only");
            else if (!allowed(sender, Action.OPEN)) plugin.tell(sender, "no-permission");
            else plugin.openMenu(player, 0);
            return true;
        }

        Action action = Action.parse(args[0]);
        if (action == null || args.length != (action == Action.EDIT || action == Action.OPEN ? 2 : 1)) {
            plugin.tell(sender, "usage");
            return true;
        }
        if (!allowed(sender, action)) {
            plugin.tell(sender, "no-permission");
            return true;
        }
        if (action.playerOnly && !(sender instanceof Player)) {
            plugin.tell(sender, "player-only");
            return true;
        }

        switch (action) {
            case CREATE -> {
                plugin.openEditor((Player) sender);
                plugin.tell(sender, "create-start");
            }
            case EDIT, OPEN -> open((Player) sender, args[1], action == Action.EDIT);
            case LIST -> list(sender);
            case RELOAD -> plugin.reloadPlugin(sender);
            case VERSION -> versions.checkFor(sender);
        }
        return true;
    }

    private void open(Player player, String input, boolean edit) {
        RecipeData recipe = null;
        if (input.length() == 36) {
            try {
                UUID id = UUID.fromString(input);
                if (id.toString().equalsIgnoreCase(input)) recipe = plugin.recipe(id);
            } catch (IllegalArgumentException ignored) {
                // The missing recipe message also covers invalid identifiers.
            }
        }
        if (recipe == null) {
            player.sendMessage(plugin.msg("prefix") + plugin.msg("recipe-missing", Map.of("id", input)));
        } else if (edit) {
            plugin.openEditor(player, recipe);
        } else {
            plugin.openRecipe(player, recipe);
        }
    }

    private void list(CommandSender sender) {
        if (plugin.recipes().isEmpty()) {
            plugin.tell(sender, "recipe-empty");
            return;
        }
        String ids = plugin.recipes().stream().map(recipe -> recipe.id.toString()).collect(Collectors.joining(", "));
        sender.sendMessage(plugin.msg("prefix") + plugin.msg("recipe-list", Map.of("ids", ids)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Arrays.stream(Action.values())
                    .filter(action -> (!action.playerOnly || sender instanceof Player) && allowed(sender, action))
                    .map(Action::label)
                    .filter(label -> label.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && sender instanceof Player) {
            Action action = Action.parse(args[0]);
            if ((action == Action.EDIT || action == Action.OPEN) && allowed(sender, action)) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return plugin.recipes().stream().map(recipe -> recipe.id.toString())
                        .filter(id -> id.startsWith(prefix)).toList();
            }
        }
        return List.of();
    }
}
