package cz.forgebound.teams;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What a team is good for besides the tag:
 * - team bounty: killing a member pays extra tokens from the bounties of the rest of the team
 * - weekly rewards: the best teams of the week get tokens into their stash
 * - together: members close to each other get a small effect (Regeneration I by default)
 * - wars with a stake end by themselves after a while
 */
final class TeamPerks implements Listener {

    private final ForgeboundTeams plugin;
    private final TeamStore store;
    private final Map<String, Long> recentBonus = new HashMap<>();

    TeamPerks(ForgeboundTeams plugin, TeamStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::together, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            weekly();
            plugin.endOldWars();
            long now = System.currentTimeMillis();
            recentBonus.values().removeIf(t -> now - t > 6 * 3_600_000L);
        }, 20L * 30, 20L * 60);
    }

    // ---------------------------------------------------------------- team bounty

    /** Before Forgebound pays (and clears) the victim's own bounty: the killer also gets a share of the rest of the team's. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("team-bounty.enabled", true)) {
            return;
        }
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        Team vt = store.of(victim);
        if (vt == null || killer == null || killer.equals(victim) || store.safe(killer.getUniqueId(), victim.getUniqueId())) {
            return;
        }
        String pair = killer.getUniqueId() + ";" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = recentBonus.get(pair);
        if (last != null && now - last < plugin.getConfig().getLong("points.same-victim-minutes", 30) * 60_000L) {
            return;
        }
        int rest = 0;
        for (UUID u : vt.members.keySet()) {
            if (!u.equals(victim.getUniqueId())) {
                rest += FbBridge.bountyOf(u);
            }
        }
        int bonus = (int) Math.min(plugin.getConfig().getInt("team-bounty.max-bonus", 32),
                Math.floor(rest * plugin.getConfig().getDouble("team-bounty.kill-percent", 10) / 100.0));
        if (bonus <= 0 || !FbBridge.give(killer, bonus)) {
            return;
        }
        recentBonus.put(pair, now);
        plugin.getLogger().info("[Teams] " + killer.getName() + " got " + bonus + " tokens of the team bounty of " + vt.name);
        Bukkit.broadcast(ForgeboundTeams.prefix().append(ForgeboundTeams.color("&c☠ &f" + killer.getName()
                + " &7took &6" + bonus + " tokens &7of the team bounty of ")).append(ForgeboundTeams.teamName(vt)).append(ForgeboundTeams.color("&7!")));
    }

    // ---------------------------------------------------------------- weekly rewards

    private void weekly() {
        if (!plugin.getConfig().getBoolean("weekly-rewards.enabled", true)) {
            return;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(plugin.getConfig().getString("weekly-rewards.timezone", "Europe/Prague"));
        } catch (RuntimeException ex) {
            zone = ZoneId.systemDefault();
        }
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(plugin.getConfig().getString("weekly-rewards.day", "SUNDAY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            day = DayOfWeek.SUNDAY;
        }
        int hour = Math.max(0, Math.min(23, plugin.getConfig().getInt("weekly-rewards.hour", 20)));
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime due = LocalDateTime.of(now.toLocalDate(), java.time.LocalTime.of(hour, 0))
                .with(TemporalAdjusters.previousOrSame(day)).atZone(zone);
        if (due.isAfter(now)) {
            due = due.minusWeeks(1);
        }
        long dueMs = due.toInstant().toEpochMilli();
        if (store.weeklyPaid == 0) {
            store.weeklyPaid = System.currentTimeMillis();   // the first week starts now
            store.save();
            return;
        }
        if (store.weeklyPaid >= dueMs) {
            return;
        }
        store.weeklyPaid = System.currentTimeMillis();
        List<Integer> rewards = plugin.getConfig().getIntegerList("weekly-rewards.tokens");
        if (rewards.isEmpty()) {
            rewards = List.of(30, 20, 10);
        }
        List<Team> best = new ArrayList<>(store.all().stream().filter(t -> t.weekPoints > 0).toList());
        best.sort(Comparator.comparingLong((Team t) -> t.weekPoints).reversed());
        if (!best.isEmpty()) {
            Bukkit.broadcast(ForgeboundTeams.prefix().append(ForgeboundTeams.color("&6&lThe teams of the week:")));
        }
        for (int i = 0; i < Math.min(rewards.size(), best.size()); i++) {
            Team t = best.get(i);
            int tokens = rewards.get(i);
            plugin.stash.give(t, tokens);
            String medal = i == 0 ? "&6" : i == 1 ? "&f" : "&c";
            Bukkit.broadcast(ForgeboundTeams.color(medal + (i + 1) + ". ").append(ForgeboundTeams.teamName(t))
                    .append(ForgeboundTeams.color(" &e" + t.weekPoints + " points &7→ &6" + tokens + " tokens &7into the stash")));
            plugin.getLogger().info("[Teams] weekly reward #" + (i + 1) + ": " + t.name + " (" + t.weekPoints + " points) +" + tokens + " tokens");
        }
        store.all().forEach(t -> t.weekPoints = 0);
        store.save();
    }

    // ---------------------------------------------------------------- together

    private void together() {
        if (!plugin.getConfig().getBoolean("together.enabled", true)) {
            return;
        }
        double r = plugin.getConfig().getDouble("together.radius", 16);
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(
                plugin.getConfig().getString("together.effect", "regeneration").toLowerCase(Locale.ROOT)));
        if (type == null) {
            return;
        }
        int amp = Math.max(0, plugin.getConfig().getInt("together.amplifier", 0));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!playing(p)) {
                continue;
            }
            Team t = store.of(p);
            if (t == null || t.members.size() < 2) {
                continue;
            }
            for (UUID u : t.members.keySet()) {
                Player m = Bukkit.getPlayer(u);
                if (m != null && !m.equals(p) && playing(m) && m.getWorld().equals(p.getWorld())
                        && m.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                    PotionEffect have = p.getPotionEffect(type);
                    if (have == null || have.getAmplifier() <= amp && have.getDuration() < 60) {
                        p.addPotionEffect(new PotionEffect(type, 70, amp, true, false, true));
                    }
                    break;
                }
            }
        }
    }

    private static boolean playing(Player p) {
        return !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }
}
