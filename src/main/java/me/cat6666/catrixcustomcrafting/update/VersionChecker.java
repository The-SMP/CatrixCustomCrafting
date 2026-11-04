package me.cat6666.catrixcustomcrafting.update;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class VersionChecker {
    @FunctionalInterface
    public interface Messages {
        String format(String key, Map<String, String> variables);
    }

    private enum State { AVAILABLE, CURRENT, DEVELOPMENT, FAILED }
    private record Result(State state, String tag, String error) {}
    private record Recipient(UUID playerId, CommandSender other) {
        static Recipient of(CommandSender sender) {
            return sender instanceof Player player
                    ? new Recipient(player.getUniqueId(), null) : new Recipient(null, sender);
        }
        CommandSender resolve() {
            return playerId == null ? other : Bukkit.getPlayer(playerId);
        }
    }

    private static final long CACHE_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private final JavaPlugin plugin;
    private final Executor executor;
    private final Messages messages;
    private final String current;
    private final Set<Recipient> requesters = new LinkedHashSet<>();
    private final Set<UUID> loginWaiters = new HashSet<>();
    private boolean checking;
    private boolean startupCheck;
    private long checkedAt;
    private String checkedRepository;
    private Result cached;

    public VersionChecker(JavaPlugin plugin, Executor executor, Messages messages) {
        this.plugin = plugin;
        this.executor = executor;
        this.messages = messages;
        current = plugin.getPluginMeta().getVersion();
    }

    public void checkAtStartup() {
        start(repository(), true);
    }

    public void checkFor(CommandSender sender) {
        String repo = repository();
        if (cached != null && repo.equals(checkedRepository) && System.currentTimeMillis() - checkedAt < CACHE_MILLIS) {
            send(sender, cached);
            return;
        }
        requesters.add(Recipient.of(sender));
        sender.sendMessage(messages.format("prefix", Map.of()) + messages.format("update-checking", Map.of()));
        if (!checking) start(repo, false);
    }

    public void remind(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !canCheck(player)) return;
        if (startupCheck && checking) {
            loginWaiters.add(id);
        } else if (cached != null && cached.state() == State.AVAILABLE && repository().equals(checkedRepository)) {
            send(player, cached);
        }
    }

    public void forget(UUID id) {
        loginWaiters.remove(id);
        requesters.remove(new Recipient(id, null));
    }

    public boolean canCheck(CommandSender sender) {
        return sender.hasPermission(plugin.getConfig().getString("permissions.version", "catrixcustomcrafting.version"));
    }

    private String repository() {
        return plugin.getConfig().getString("settings.github-repository", "The-SMP/CatrixCustomCrafting");
    }

    private void start(String repo, boolean startup) {
        checking = true;
        startupCheck = startup;
        executor.execute(() -> {
            Result result = fetch(repo);
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> complete(repo, result));
        });
    }

    private Result fetch(String repo) {
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            return new Result(State.FAILED, "", "expected owner/repository");
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CatrixCustomCrafting").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            var match = TAG.matcher(response.body());
            if (!match.find()) throw new IOException("tag_name missing");
            String tag = match.group(1);
            int order = compareVersions(current, tag);
            State state = order < 0 ? State.AVAILABLE : order > 0 ? State.DEVELOPMENT : State.CURRENT;
            return new Result(state, tag, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(State.FAILED, "", e.toString());
        } catch (IOException | IllegalArgumentException e) {
            return new Result(State.FAILED, "", e.toString());
        }
    }

    private void complete(String repo, Result result) {
        checking = false;
        cached = result;
        checkedRepository = repo;
        checkedAt = System.currentTimeMillis();
        if (startupCheck) {
            plugin.getLogger().info(messages.format(consoleKey(result.state()), variables(result)));
            if (result.state() == State.AVAILABLE && repo.equals(repository())) {
                for (UUID id : loginWaiters) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && canCheck(player)) send(player, result);
                }
            }
            loginWaiters.clear();
        }
        if (!repo.equals(repository()) && !requesters.isEmpty()) {
            start(repository(), false);
            return;
        }
        for (Recipient recipient : requesters) {
            CommandSender sender = recipient.resolve();
            if (sender != null && canCheck(sender)) send(sender, result);
        }
        requesters.clear();
    }

    private void send(CommandSender sender, Result result) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.format(consoleKey(result.state()), variables(result)));
            return;
        }
        String key = switch (result.state()) {
            case AVAILABLE -> "update-available";
            case CURRENT -> "update-current";
            case DEVELOPMENT -> "update-dev";
            case FAILED -> "update-failed";
        };
        sender.sendMessage(messages.format("prefix", Map.of()) + messages.format(key, variables(result)));
    }

    private String consoleKey(State state) {
        return switch (state) {
            case AVAILABLE -> "update-console-available";
            case CURRENT -> "update-console-current";
            case DEVELOPMENT -> "update-console-dev";
            case FAILED -> "update-console-failed";
        };
    }

    private Map<String, String> variables(Result result) {
        return Map.of("version", result.tag(), "current", current, "error", result.error());
    }

    private static int compareVersions(String left, String right) {
        String a = left.trim().replaceFirst("(?i)^v", "").split("[-+]", 2)[0];
        String b = right.trim().replaceFirst("(?i)^v", "").split("[-+]", 2)[0];
        try {
            String[] x = a.split("\\.");
            String[] y = b.split("\\.");
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                int p = i < x.length ? Integer.parseInt(x[i]) : 0;
                int q = i < y.length ? Integer.parseInt(y[i]) : 0;
                if (p != q) return Integer.compare(p, q);
            }
            return 0;
        } catch (NumberFormatException e) {
            return a.compareToIgnoreCase(b);
        }
    }
}
