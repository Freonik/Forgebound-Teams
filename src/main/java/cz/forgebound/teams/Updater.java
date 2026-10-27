package cz.forgebound.teams;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-update from the GitHub releases: a newer ForgeboundTeams.jar is downloaded into plugins/update/
 * (Paper swaps it in on the next restart). Nothing is replaced while the server runs.
 * Admins (guard.admin) are told what happened.
 */
public final class Updater implements Listener {

    private final ForgeboundTeams plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private BukkitTask task;
    /** what admins should hear about on join (e.g. "restart to update") */
    private volatile String pending;
    private volatile String downloaded;
    /** why the last check didn't work, or null */
    private volatile String lastError;

    public Updater(ForgeboundTeams plugin) {
        this.plugin = plugin;
    }

    private String cfg(String key, String def) {
        return plugin.getConfig().getString("auto-update." + key, def);
    }

    private boolean on(String key, boolean def) {
        return plugin.getConfig().getBoolean("auto-update." + key, def);
    }

    public void start() {
        stop();
        if (!on("enabled", true)) {
            return;
        }
        long every = Math.max(1, plugin.getConfig().getInt("auto-update.check-hours", 6)) * 3600L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L * 60, every);
    }

    /** /team admin update */
    public void command(org.bukkit.command.CommandSender sender, String what) {
        switch (what) {
            case "on", "off" -> {
                plugin.getConfig().set("auto-update.enabled", what.equals("on"));
                plugin.saveConfig();
                start();
                sender.sendMessage(ForgeboundTeams.color(what.equals("on")
                        ? "&aAuto-update on &7(checks every " + plugin.getConfig().getInt("auto-update.check-hours", 6) + " h)."
                        : "&7Auto-update off. &8Nothing will be downloaded."));
            }
            default -> {
                sender.sendMessage(ForgeboundTeams.color("&7Checking for a new version..."));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String before = pending;
                    check();
                    String after = pending;
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ForgeboundTeams.color(
                            after != null && !after.equals(before) ? "&8[&6Update&8] " + after
                                    : downloaded != null ? "&aVersion &f" + downloaded + " &ais already downloaded, restart to use it."
                                    : lastError != null ? "&cCouldn't check for updates: &7" + lastError
                                    : "&7You have the newest version &8(" + plugin.getPluginMeta().getVersion() + ")")));
                });
            }
        }
    }

    public boolean enabled() {
        return on("enabled", true);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * For Forgebound's nightly restart (registered as a Callable service): checks right now (blocking, off the main
     * thread) and returns the version waiting in plugins/update, or null if there's nothing new.
     */
    public String checkNow() {
        if (on("enabled", true)) {
            check();
        }
        return downloaded;
    }

    /** Where the releases are published (the code repository is private); old configs still name the code repo. */
    static final String RELEASES = "Freonik/Forgebound-releases";

    private String releasesRepo() {
        String repo = cfg("repo", RELEASES);
        return repo.equalsIgnoreCase("Freonik/Forgebound-Teams") ? RELEASES : repo;
    }

    /** Off the main thread. The newest versions are in latest.json of the releases repository (no API rate limit). */
    private void check() {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://raw.githubusercontent.com/"
                            + releasesRepo() + "/main/latest.json?t=" + System.currentTimeMillis() / 60_000))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", "ForgeboundTeams").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                fail("latest.json: HTTP " + res.statusCode());
                return;
            }
            JsonObject all = JsonParser.parseString(res.body()).getAsJsonObject();
            if (!all.has("ForgeboundTeams")) {
                fail("latest.json has no ForgeboundTeams entry");
                return;
            }
            lastError = null;
            JsonObject me = all.getAsJsonObject("ForgeboundTeams");
            String latest = me.has("version") ? version(me.get("version").getAsString()) : null;
            if (latest != null && compare(latest, plugin.getPluginMeta().getVersion()) > 0 && me.has("jar")) {
                JsonObject release = new JsonObject();
                com.google.gson.JsonArray assets = new com.google.gson.JsonArray();
                JsonObject a = new JsonObject();
                a.addProperty("name", "ForgeboundTeams.jar");
                a.addProperty("browser_download_url", me.get("jar").getAsString());
                assets.add(a);
                release.add("assets", assets);
                update(releasesRepo(), me.has("tag") ? me.get("tag").getAsString() : latest, latest, release);
            }
        } catch (Exception ex) {
            fail(ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
        }
    }

    /** Logs a failed check once (not every hour the same thing). */
    private void fail(String why) {
        if (!why.equals(lastError)) {
            plugin.getLogger().info("[Update] Couldn't check for a new version: " + why);
        }
        lastError = why;
    }

    private void update(String repo, String tag, String version, JsonObject release) throws IOException, InterruptedException {
        if (version.equals(downloaded)) {
            return;
        }
        if (!on("download", true)) {
            tell("&eForgebound Teams &f" + version + " &eis out &8(auto-update.download is off)");
            return;
        }
        String url = asset(release, "ForgeboundTeams.jar");
        if (url == null) {
            return;
        }
        File folder = Bukkit.getUpdateFolderFile();
        Files.createDirectories(folder.toPath());
        // the update folder replaces the jar with the same file name
        File target = new File(folder, plugin.jarFile().getName());
        Path tmp = target.toPath().resolveSibling(target.getName() + ".part");
        HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "ForgeboundTeams").GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (res.statusCode() != 200 || Files.size(tmp) < 10_000) {
            Files.deleteIfExists(tmp);
            return;
        }
        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        downloaded = version;
        tell("&aForgebound Teams &f" + version + " &ais downloaded"
                + "&a. &7It's used after the next &frestart&7.");
    }


    private static String asset(JsonObject release, String name) {
        if (!release.has("assets")) {
            return null;
        }
        for (JsonElement e : release.getAsJsonArray("assets")) {
            JsonObject a = e.getAsJsonObject();
            if (name.equals(a.get("name").getAsString())) {
                return a.get("browser_download_url").getAsString();
            }
        }
        return null;
    }

    /** "v2.7.0-build.65" -> "2.7.0" */
    static String version(String tag) {
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(tag);
        return m.find() ? m.group() : null;
    }

    /** semantic compare of "x.y.z" (anything after the numbers is ignored) */
    static int compare(String a, String b) {
        String va = version(a);
        String vb = version(b);
        if (va == null || vb == null) {
            return 0;
        }
        String[] x = va.split("\\.");
        String[] y = vb.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private void tell(String legacy) {
        pending = legacy;
        plugin.getLogger().info(PlainText.of(legacy));
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("teams.admin")) {
                    p.sendMessage(ForgeboundTeams.color("&8[&6Update&8] " + legacy));
                }
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String msg = pending;
        if (msg != null && event.getPlayer().hasPermission("teams.admin")) {
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    event.getPlayer().sendMessage(ForgeboundTeams.color("&8[&6Update&8] " + msg)), 60L);
        }
    }

    /** "&aText" -> "Text" for the console */
    private static final class PlainText {
        static String of(String legacy) {
            return legacy.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        }
    }
}
