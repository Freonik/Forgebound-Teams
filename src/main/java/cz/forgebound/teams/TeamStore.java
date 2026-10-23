package cz.forgebound.teams;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** All teams, who is in which, saving to teams.yml and the vanilla scoreboard teams (tag + color + no friendly fire). */
public final class TeamStore {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    static final Pattern TAG = Pattern.compile("[A-Za-z0-9]{2,4}");
    static final List<NamedTextColor> COLORS = List.of(NamedTextColor.RED, NamedTextColor.GOLD, NamedTextColor.YELLOW,
            NamedTextColor.GREEN, NamedTextColor.DARK_GREEN, NamedTextColor.AQUA, NamedTextColor.DARK_AQUA, NamedTextColor.BLUE,
            NamedTextColor.LIGHT_PURPLE, NamedTextColor.DARK_PURPLE, NamedTextColor.DARK_RED, NamedTextColor.WHITE, NamedTextColor.GRAY);

    private final ForgeboundTeams plugin;
    private final File file;
    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    /** player -> team id (read from the async chat thread too) */
    private final Map<UUID, String> byPlayer = new ConcurrentHashMap<>();

    TeamStore(ForgeboundTeams plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
    }

    // ================================================================ load / save

    void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("teams");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                Team t = new Team(id, s.getString("name", id), s.getString("tag", "?"), color(s.getString("color", "white")));
                ConfigurationSection m = s.getConfigurationSection("members");
                if (m != null) {
                    for (String uuid : m.getKeys(false)) {
                        try {
                            UUID u = UUID.fromString(uuid);
                            t.members.put(u, Team.Role.valueOf(m.getString(uuid + ".role", "MEMBER")));
                            t.names.put(u, m.getString(uuid + ".name", "?"));
                        } catch (IllegalArgumentException ignored) {
                            // broken entry
                        }
                    }
                }
                t.allies.addAll(s.getStringList("allies"));
                ConfigurationSection en = s.getConfigurationSection("enemies");
                if (en != null) {
                    for (String e : en.getKeys(false)) {
                        t.enemies.put(e, en.getInt(e));
                    }
                }
                t.kills = s.getInt("kills");
                t.deaths = s.getInt("deaths");
                t.points = s.getLong("points");
                t.created = s.getLong("created");
                if (!t.members.isEmpty()) {
                    teams.put(id, t);
                    t.members.keySet().forEach(u -> byPlayer.put(u, id));
                }
            }
        }
        // allies that no longer exist
        for (Team t : teams.values()) {
            t.allies.removeIf(a -> !teams.containsKey(a));
            t.enemies.keySet().removeIf(a -> !teams.containsKey(a));
        }
        syncAllScoreboard();
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Team t : teams.values()) {
            String b = "teams." + t.id + ".";
            y.set(b + "name", t.name);
            y.set(b + "tag", t.tag);
            y.set(b + "color", t.color.toString());
            for (Map.Entry<UUID, Team.Role> e : t.members.entrySet()) {
                y.set(b + "members." + e.getKey() + ".role", e.getValue().name());
                y.set(b + "members." + e.getKey() + ".name", t.names.getOrDefault(e.getKey(), "?"));
            }
            y.set(b + "allies", new ArrayList<>(t.allies));
            for (Map.Entry<String, Integer> e : t.enemies.entrySet()) {
                y.set(b + "enemies." + e.getKey(), e.getValue());
            }
            y.set(b + "kills", t.kills);
            y.set(b + "deaths", t.deaths);
            y.set(b + "points", t.points);
            y.set(b + "created", t.created);
        }
        plugin.saveYaml(y, file);
    }

    // ================================================================ queries

    static NamedTextColor color(String name) {
        NamedTextColor c = NamedTextColor.NAMES.value(name.toLowerCase(Locale.ROOT));
        return c == null ? NamedTextColor.WHITE : c;
    }

    public Team of(UUID player) {
        String id = byPlayer.get(player);
        return id == null ? null : teams.get(id);
    }

    public Team of(Player player) {
        return of(player.getUniqueId());
    }

    public Team byName(String name) {
        return name == null ? null : teams.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Team> all() {
        return teams.values();
    }

    public List<Team> ranked() {
        return teams.values().stream().sorted(Comparator.comparingLong(Team::points).reversed()
                .thenComparing(t -> -t.kills)).toList();
    }

    public boolean tagTaken(String tag, Team except) {
        return teams.values().stream().anyMatch(t -> t != except && t.tag.equalsIgnoreCase(tag));
    }

    /** Same team. */
    public boolean mates(UUID a, UUID b) {
        String ta = byPlayer.get(a);
        return ta != null && ta.equals(byPlayer.get(b));
    }

    /** Same team or allied teams. */
    public boolean friendly(UUID a, UUID b) {
        if (a.equals(b)) {
            return false;
        }
        String ta = byPlayer.get(a);
        String tb = byPlayer.get(b);
        if (ta == null || tb == null) {
            return false;
        }
        if (ta.equals(tb)) {
            return true;
        }
        Team t = teams.get(ta);
        return t != null && t.allies.contains(tb);
    }

    /** At war with each other. */
    public boolean enemies(UUID a, UUID b) {
        String ta = byPlayer.get(a);
        String tb = byPlayer.get(b);
        if (ta == null || tb == null || ta.equals(tb)) {
            return false;
        }
        Team t = teams.get(ta);
        return t != null && t.enemies.containsKey(tb);
    }

    // ================================================================ changes

    Team create(Player leader, String name, String tag) {
        String id = name.toLowerCase(Locale.ROOT);
        NamedTextColor color = COLORS.get(Math.floorMod(id.hashCode(), COLORS.size()));
        Team t = new Team(id, name, tag.toUpperCase(Locale.ROOT), color);
        t.created = System.currentTimeMillis();
        teams.put(id, t);
        join(t, leader.getUniqueId(), leader.getName(), Team.Role.LEADER);
        return t;
    }

    void join(Team t, UUID player, String name, Team.Role role) {
        t.members.put(player, role);
        t.names.put(player, name);
        byPlayer.put(player, t.id);
        syncScoreboard(t);
        save();
    }

    void leave(Team t, UUID player) {
        String name = t.names.remove(player);
        t.members.remove(player);
        byPlayer.remove(player);
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team vanilla = main.getTeam(t.scoreboardName());
        if (vanilla != null && name != null) {
            vanilla.removeEntry(name);
        }
        if (t.members.isEmpty()) {
            disband(t);
            return;
        }
        if (!t.members.containsValue(Team.Role.LEADER)) {
            // the oldest officer, else the oldest member leads now
            UUID next = t.members.entrySet().stream().filter(e -> e.getValue() == Team.Role.OFFICER).map(Map.Entry::getKey)
                    .findFirst().orElse(t.members.keySet().iterator().next());
            t.members.put(next, Team.Role.LEADER);
        }
        save();
    }

    void disband(Team t) {
        teams.remove(t.id);
        t.members.keySet().forEach(byPlayer::remove);
        for (Team other : teams.values()) {
            other.allies.remove(t.id);
            other.enemies.remove(t.id);
        }
        org.bukkit.scoreboard.Team vanilla = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(t.scoreboardName());
        if (vanilla != null) {
            vanilla.unregister();
        }
        save();
    }

    /** The player's current name (players can rename their accounts). */
    void refreshName(Player p) {
        Team t = of(p);
        if (t == null) {
            return;
        }
        String old = t.names.put(p.getUniqueId(), p.getName());
        if (old != null && !old.equals(p.getName())) {
            org.bukkit.scoreboard.Team vanilla = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(t.scoreboardName());
            if (vanilla != null) {
                vanilla.removeEntry(old);
            }
            save();
        }
        syncScoreboard(t);
    }

    // ================================================================ vanilla scoreboard teams

    void syncAllScoreboard() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (org.bukkit.scoreboard.Team v : new ArrayList<>(main.getTeams())) {
            if (v.getName().startsWith("fbt_") && !teams.containsKey(v.getName().substring(4))) {
                v.unregister();
            }
        }
        teams.values().forEach(this::syncScoreboard);
    }

    void syncScoreboard(Team t) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team v = main.getTeam(t.scoreboardName());
        if (v == null) {
            v = main.registerNewTeam(t.scoreboardName());
        }
        v.displayName(Component.text(t.name, t.color));
        v.prefix(Component.text("[" + t.tag + "] ", t.color));
        v.color(t.color);
        v.setAllowFriendlyFire(plugin.getConfig().getBoolean("friendly-fire", false));
        v.setCanSeeFriendlyInvisibles(true);
        for (String name : t.names.values()) {
            if (!v.hasEntry(name)) {
                v.addEntry(name);
            }
        }
    }

    static String nameOf(UUID id) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        return p.getName() == null ? "?" : p.getName();
    }
}
