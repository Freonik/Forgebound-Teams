package cz.forgebound.teams;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.inventory.ItemStack;
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
    /** extra colors (names show them in the tag and chat; the name above the head uses the nearest of the 16 basic ones) */
    static final Map<String, TextColor> PRESETS = new java.util.LinkedHashMap<>();

    static {
        PRESETS.put("dark_blue", NamedTextColor.DARK_BLUE);
        PRESETS.put("dark_gray", NamedTextColor.DARK_GRAY);
        PRESETS.put("black", NamedTextColor.BLACK);
        PRESETS.put("pink", TextColor.color(0xFF6EC7));
        PRESETS.put("rose", TextColor.color(0xE8467C));
        PRESETS.put("crimson", TextColor.color(0xC0143C));
        PRESETS.put("coral", TextColor.color(0xFF7F50));
        PRESETS.put("orange", TextColor.color(0xFF8C1A));
        PRESETS.put("amber", TextColor.color(0xFFBF00));
        PRESETS.put("brown", TextColor.color(0x8B5A2B));
        PRESETS.put("olive", TextColor.color(0x9AA33C));
        PRESETS.put("lime", TextColor.color(0x9EF01A));
        PRESETS.put("mint", TextColor.color(0x7DFFB3));
        PRESETS.put("teal", TextColor.color(0x1FB5A8));
        PRESETS.put("sky", TextColor.color(0x7CC8FF));
        PRESETS.put("navy", TextColor.color(0x2B4C9B));
        PRESETS.put("lavender", TextColor.color(0xB79CFF));
        PRESETS.put("violet", TextColor.color(0x8A2BE2));
    }

    /** every color name a team can pick (the basic ones first) */
    static List<String> colorNames() {
        List<String> list = new ArrayList<>(COLORS.stream().map(NamedTextColor::toString).toList());
        list.addAll(PRESETS.keySet());
        return list;
    }

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
        weeklyPaid = y.getLong("weekly-paid");
        for (String sec : new String[]{"truce", "ally-cooldown"}) {
            ConfigurationSection cs = y.getConfigurationSection(sec);
            if (cs != null) {
                for (String k : cs.getKeys(false)) {
                    (sec.equals("truce") ? truce : allyCooldown).put(k, cs.getLong(k));
                }
            }
        }
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
                t.owed = s.getInt("stash-owed");
                t.weekPoints = s.getLong("week-points");
                ConfigurationSection st = s.getConfigurationSection("stakes");
                if (st != null) {
                    st.getKeys(false).forEach(k -> t.stakes.put(k, st.getInt(k)));
                }
                ConfigurationSection ws = s.getConfigurationSection("war-start");
                if (ws != null) {
                    ws.getKeys(false).forEach(k -> t.warStart.put(k, ws.getLong(k)));
                }
                if (s.isString("banner")) {
                    t.banner = fromBase64(s.getString("banner"));
                }
                List<String> stash = s.getStringList("stash");
                if (!stash.isEmpty()) {
                    t.stash = new ItemStack[TeamStash.SIZE];
                    for (String line : stash) {
                        int c = line.indexOf(':');
                        try {
                            int slot = Integer.parseInt(line.substring(0, c));
                            ItemStack item = fromBase64(line.substring(c + 1));
                            if (slot >= 0 && slot < TeamStash.SIZE && item != null) {
                                t.stash[slot] = item;
                            }
                        } catch (RuntimeException ignored) {
                            // broken entry
                        }
                    }
                }
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
            t.stakes.keySet().removeIf(a -> !t.enemies.containsKey(a));
            t.warStart.keySet().removeIf(a -> !t.enemies.containsKey(a));
        }
        syncAllScoreboard();
    }

    /** when the weekly team rewards were last paid (ms) */
    long weeklyPaid;

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("weekly-paid", weeklyPaid);
        long nowMs = System.currentTimeMillis();
        truce.forEach((k, v) -> {
            if (v > nowMs) {
                y.set("truce." + k, v);
            }
        });
        allyCooldown.forEach((k, v) -> {
            if (v > nowMs) {
                y.set("ally-cooldown." + k, v);
            }
        });
        for (Team t : teams.values()) {
            String b = "teams." + t.id + ".";
            y.set(b + "name", t.name);
            y.set(b + "tag", t.tag);
            y.set(b + "color", colorName(t.color));
            if (t.banner != null) {
                y.set(b + "banner", toBase64(t.banner));
            }
            if (t.owed > 0) {
                y.set(b + "stash-owed", t.owed);
            }
            for (Map.Entry<String, Integer> e : t.stakes.entrySet()) {
                y.set(b + "stakes." + e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Long> e : t.warStart.entrySet()) {
                y.set(b + "war-start." + e.getKey(), e.getValue());
            }
            y.set(b + "week-points", t.weekPoints);
            if (t.stash != null) {
                List<String> stash = new ArrayList<>();
                for (int i = 0; i < t.stash.length; i++) {
                    if (t.stash[i] != null && !t.stash[i].isEmpty()) {
                        stash.add(i + ":" + toBase64(t.stash[i]));
                    }
                }
                if (!stash.isEmpty()) {
                    y.set(b + "stash", stash);
                }
            }
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

    static TextColor color(String name) {
        TextColor c = parseColor(name);
        return c == null ? NamedTextColor.WHITE : c;
    }

    /** "red", "pink" or "#ff8800" -> the color, null if it isn't one */
    static TextColor parseColor(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        NamedTextColor named = NamedTextColor.NAMES.value(n);
        if (named != null) {
            return named;
        }
        if (PRESETS.containsKey(n)) {
            return PRESETS.get(n);
        }
        if (!n.startsWith("#")) {
            n = "#" + n;
        }
        return n.matches("#[0-9a-f]{6}") ? TextColor.fromHexString(n) : null;
    }

    /** the name a color is saved and shown as ("red", "pink", "#ff8800") */
    static String colorName(TextColor c) {
        if (c instanceof NamedTextColor named) {
            return named.toString();
        }
        for (Map.Entry<String, TextColor> e : PRESETS.entrySet()) {
            if (e.getValue().value() == c.value()) {
                return e.getKey();
            }
        }
        return c.asHexString();
    }

    private static String toBase64(ItemStack s) {
        return java.util.Base64.getEncoder().encodeToString(s.serializeAsBytes());
    }

    private static ItemStack fromBase64(String s) {
        try {
            return ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(s));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public Team byId(String id) {
        return id == null ? null : teams.get(id);
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

    // ---------------------------------------------------------------- truce after an alliance ends

    /** "teamA;teamB" (sorted) -> until when (ms) the two can't hurt each other after their alliance ended */
    final Map<String, Long> truce = new ConcurrentHashMap<>();
    /** "teamA;teamB" (sorted) -> until when (ms) they can't become allies again */
    final Map<String, Long> allyCooldown = new ConcurrentHashMap<>();

    static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + ";" + b : b + ";" + a;
    }

    /** Teams that just ended their alliance: for a while kills between them pay no bounty (they can fight). */
    public boolean truce(UUID a, UUID b) {
        String ta = byPlayer.get(a);
        String tb = byPlayer.get(b);
        if (ta == null || tb == null || ta.equals(tb)) {
            return false;
        }
        Long until = truce.get(pair(ta, tb));
        return until != null && until > System.currentTimeMillis();
    }

    /** On the same side (teammates, allies) or in a truce after an alliance: kills don't pay a team bounty. */
    public boolean safe(UUID a, UUID b) {
        return friendly(a, b) || truce(a, b);
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
        TextColor color = COLORS.get(Math.floorMod(id.hashCode(), COLORS.size()));
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

    /** called before a team disappears (war pots) */
    java.util.function.Consumer<Team> beforeDisband;

    void disband(Team t) {
        if (beforeDisband != null) {
            beforeDisband.accept(t);
        }
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
        v.color(t.color instanceof NamedTextColor n ? n : NamedTextColor.nearestTo(t.color));
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
