package cz.forgebound.teams;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** No friendly fire, team chat, and the points behind the team leaderboard. */
public final class TeamListener implements Listener {

    /** Forgebound's item id on its weapons and relics ("weapon:echo_blade"). */
    private static final NamespacedKey FORGEBOUND_ID = new NamespacedKey("customcraft", "item_id");
    /** Forgebound's world boss. */
    private static final NamespacedKey FORGEBOUND_BOSS = new NamespacedKey("customcraft", "big_event_boss");

    private final ForgeboundTeams plugin;
    private final TeamStore store;
    /** players whose chat goes to the team */
    private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();
    /** "killer;victim" -> last time it gave points */
    private final Map<String, Long> recentKills = new HashMap<>();

    TeamListener(ForgeboundTeams plugin, TeamStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void start() {
        // every few minutes: points for the legends the team carries (Forgebound)
        long every = Math.max(1, plugin.getConfig().getLong("points.legend-every-minutes", 30)) * 60L * 20L;
        new BukkitRunnable() {
            @Override
            public void run() {
                legendPoints();
                long now = System.currentTimeMillis();
                recentKills.values().removeIf(t -> now - t > 3_600_000L);
            }
        }.runTaskTimer(plugin, every, every);
    }

    private long points(String key, long def) {
        return plugin.getConfig().getLong("points." + key, def);
    }

    // ================================================================ no friendly fire

    private static Player source(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            return p;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) {
            return p;
        }
        if (damager instanceof Tameable t && t.getOwner() instanceof Player p) {
            return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (plugin.getConfig().getBoolean("friendly-fire", false)) {
            return;
        }
        Player attacker = source(event.getDamager());
        if (attacker == null) {
            return;
        }
        UUID victim = event.getEntity() instanceof Player p ? p.getUniqueId()
                : event.getEntity() instanceof Tameable t && t.getOwner() != null ? t.getOwner().getUniqueId() : null;
        if (victim != null && store.friendly(attacker.getUniqueId(), victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        if (plugin.getConfig().getBoolean("friendly-fire", false) || !(potion.getShooter() instanceof Player thrower)) {
            return;
        }
        boolean harmful = false;
        for (PotionEffect e : potion.getEffects()) {
            String key = e.getType().getKey().getKey();
            if (Set.of("instant_damage", "poison", "weakness", "slowness", "wither", "blindness", "nausea",
                    "mining_fatigue", "hunger", "levitation", "darkness", "infested", "oozing", "weaving", "wind_charged")
                    .contains(key)) {
                harmful = true;
            }
        }
        if (!harmful) {
            return;
        }
        for (LivingEntity e : event.getAffectedEntities()) {
            if (e instanceof Player p && store.friendly(thrower.getUniqueId(), p.getUniqueId())) {
                event.setIntensity(e, 0);
            }
        }
    }

    // ================================================================ team chat

    boolean teamChatOn(Player p) {
        return teamChat.contains(p.getUniqueId());
    }

    void toggleChat(Player p) {
        if (store.of(p) == null) {
            ForgeboundTeams.tell(p, "&cYou are not in a team.");
            return;
        }
        if (teamChat.remove(p.getUniqueId())) {
            ForgeboundTeams.tell(p, "&7Your chat goes to &feveryone &7again.");
        } else {
            teamChat.add(p.getUniqueId());
            ForgeboundTeams.tell(p, "&7Your chat goes to your &ateam&7. Start a message with &f! &7to talk to everyone. &e/tc &7switches back.");
        }
    }

    /** /tc <message> and /ac <message>: as if typed into the chat with ! or @ */
    void teamMessage(Player p, String message, boolean allies) {
        Team t = store.of(p);
        if (t == null) {
            ForgeboundTeams.tell(p, "&cYou are not in a team.");
            return;
        }
        Component line = format(t, p, Component.text(message), allies);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (allies ? store.friendly(p.getUniqueId(), o.getUniqueId()) || o.equals(p) : store.mates(p.getUniqueId(), o.getUniqueId())) {
                o.sendMessage(line);
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    private static Component format(Team t, Player p, Component message, boolean allies) {
        return Component.text(allies ? "[Allies] " : "[Team] ", allies ? NamedTextColor.AQUA : NamedTextColor.GREEN)
                .append(Component.text(p.getName(), t.color)).append(Component.text(": ", NamedTextColor.GRAY))
                .append(message.color(allies ? NamedTextColor.AQUA : NamedTextColor.GREEN));
    }

    /** After the moderation (Guard runs at LOW): "!text" or team-chat mode goes to the team, "@text" to team + allies. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        Team t = store.of(p);
        if (t == null) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        String teamMark = plugin.getConfig().getString("chat.team-prefix", "!");
        String allyMark = plugin.getConfig().getString("chat.ally-prefix", "@");
        boolean mode = teamChat.contains(p.getUniqueId());
        boolean allies;
        String text;
        if (!allyMark.isEmpty() && plain.startsWith(allyMark) && plain.length() > allyMark.length()) {
            allies = true;
            text = plain.substring(allyMark.length()).trim();
        } else if (!teamMark.isEmpty() && plain.startsWith(teamMark) && plain.length() > teamMark.length()) {
            if (mode) {
                // in team-chat mode "!" means everyone
                event.message(Component.text(plain.substring(teamMark.length()).trim()));
                return;
            }
            allies = false;
            text = plain.substring(teamMark.length()).trim();
        } else if (mode) {
            allies = false;
            text = plain;
        } else {
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        UUID me = p.getUniqueId();
        event.viewers().removeIf(a -> a instanceof Player o
                && !(o.getUniqueId().equals(me) || (allies ? store.friendly(me, o.getUniqueId()) : store.mates(me, o.getUniqueId()))));
        Component message = Component.text(text);
        event.message(message);
        event.renderer(ChatRenderer.viewerUnaware((source, name, msg) -> format(t, p, msg, allies)));
    }

    // ================================================================ points

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        Team vt = store.of(victim);
        if (killer == null || killer.equals(victim) || store.friendly(killer.getUniqueId(), victim.getUniqueId())) {
            return;
        }
        if (vt != null) {
            vt.deaths++;
        }
        Team kt = store.of(killer);
        if (kt == null) {
            store.save();
            return;
        }
        kt.kills++;
        String pair = killer.getUniqueId() + ";" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = recentKills.get(pair);
        boolean war = vt != null && kt.enemies.containsKey(vt.id);
        if (war) {
            kt.enemies.merge(vt.id, 1, Integer::sum);
            String line = "&4⚔ &f" + killer.getName() + " &7killed &f" + victim.getName() + " &8(" + kt.tag + " "
                    + kt.enemies.get(vt.id) + " : " + vt.enemies.getOrDefault(kt.id, 0) + " " + vt.tag + ")";
            plugin.broadcastTeam(kt, line);
            plugin.broadcastTeam(vt, line);
        }
        if (last == null || now - last > plugin.getConfig().getLong("points.same-victim-minutes", 30) * 60_000L) {
            recentKills.put(pair, now);
            add(kt, points("player-kill", 3) + (war ? points("enemy-kill-bonus", 3) : 0),
                    killer.getName() + " killed " + victim.getName() + (war ? " (war)" : ""));
        }
        store.save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player) {
            return;
        }
        Team t = store.of(killer);
        if (t == null) {
            return;
        }
        long pts;
        if (event.getEntity().getPersistentDataContainer().has(FORGEBOUND_BOSS, PersistentDataType.BYTE)
                && event.getEntity().customName() != null) {
            pts = points("forge-colossus", 20);
        } else {
            pts = plugin.getConfig().getLong("points.bosses." + event.getEntity().getType().name(), 0);
        }
        if (pts > 0) {
            add(t, pts, killer.getName() + " killed " + event.getEntity().getType().name().toLowerCase(java.util.Locale.ROOT));
            store.save();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Team t = store.of(event.getPlayer());
        Advancement a = event.getAdvancement();
        if (t == null || a.getDisplay() == null || a.getKey().getKey().startsWith("recipes/")) {
            return;
        }
        boolean forgebound = !a.getKey().getNamespace().equals(NamespacedKey.MINECRAFT);
        long pts = forgebound ? points("forgebound-advancement", 3) : points("advancement", 1);
        if (pts > 0) {
            t.points += pts;
            store.save();
        }
    }

    private void legendPoints() {
        long per = points("per-legend", 1);
        if (per <= 0) {
            return;
        }
        boolean changed = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Team t = store.of(p);
            if (t == null) {
                continue;
            }
            int legends = 0;
            for (ItemStack s : p.getInventory().getContents()) {
                if (s != null && s.hasItemMeta()) {
                    String id = s.getPersistentDataContainer().get(FORGEBOUND_ID, PersistentDataType.STRING);
                    if (id != null && (id.startsWith("weapon:") || id.startsWith("relic:"))) {
                        legends++;
                    }
                }
            }
            if (legends > 0) {
                t.points += per * Math.min(legends, 3);
                changed = true;
            }
        }
        if (changed) {
            store.save();
        }
    }

    private void add(Team t, long pts, String why) {
        t.points += pts;
        plugin.getLogger().info("[Teams] " + t.name + " +" + pts + " points (" + why + ")");
    }

    // ================================================================ join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        store.refreshName(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teamChat.remove(event.getPlayer().getUniqueId());
    }
}
