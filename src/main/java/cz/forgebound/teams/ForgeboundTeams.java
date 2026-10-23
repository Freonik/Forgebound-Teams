package cz.forgebound.teams;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forgebound Teams: small teams (3 players by default) with a tag and color in front of the names, team chat,
 * allies, no friendly fire and a team leaderboard. Forgebound sees the teams by itself (vanilla scoreboard teams
 * + the "same side" check registered as a service), so its abilities, bounties and trackers spare teammates and allies.
 */
public final class ForgeboundTeams extends JavaPlugin {

    private TeamStore store;
    private TeamListener listener;
    private Updater updater;
    /** invitee -> team id -> expires (ms) */
    private final Map<UUID, Map<String, Long>> invites = new HashMap<>();
    /** target team -> requesting team -> expires (ms) */
    private final Map<String, Map<String, Long>> allyRequests = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ForgeboundTeams-IO");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        store = new TeamStore(this);
        store.load();
        listener = new TeamListener(this, store);
        getServer().getPluginManager().registerEvents(listener, this);
        listener.start();
        updater = new Updater(this);
        getServer().getPluginManager().registerEvents(updater, this);
        updater.start();
        // Forgebound asks this to know who is on the same side (teammates and allies)
        BiPredicate<Player, Player> sameSide = (a, b) -> store.friendly(a.getUniqueId(), b.getUniqueId());
        getServer().getServicesManager().register(BiPredicate.class, sameSide, this, ServicePriority.Normal);
        registerCommands();
        for (Player p : Bukkit.getOnlinePlayers()) {
            store.refreshName(p);
        }
        getLogger().info(store.all().size() + " teams loaded (max " + maxSize() + " players each).");
    }

    @Override
    public void onDisable() {
        if (updater != null) {
            updater.stop();
        }
        if (store != null) {
            store.save();
        }
        io.shutdown();
        try {
            io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public TeamStore store() {
        return store;
    }

    int maxSize() {
        return Math.max(1, getConfig().getInt("max-members", 3));
    }

    public static Component color(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    static Component prefix() {
        return color("&8[&6Teams&8] ");
    }

    static void tell(CommandSender to, String legacy) {
        to.sendMessage(prefix().append(color(legacy)));
    }

    static Component teamName(Team t) {
        return Component.text("[" + t.tag + "] " + t.name, t.color)
                .hoverEvent(HoverEvent.showText(color("&7Click for the team's info")))
                .clickEvent(ClickEvent.runCommand("/team info " + t.name));
    }

    /** Writes a yaml file off the main thread (atomic replace). */
    void saveYaml(YamlConfiguration yaml, File file) {
        String text = yaml.saveToString();
        Runnable write = () -> {
            try {
                Path target = file.toPath();
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                getLogger().warning("Could not save " + file.getName() + ": " + ex.getMessage());
            }
        };
        if (io.isShutdown()) {
            write.run();
        } else {
            io.execute(write);
        }
    }

    File jarFile() {
        return getFile();
    }

    // ================================================================ commands

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands c = event.registrar();
            c.register(teamTree().build(), "Your team: create, invite, allies, team chat, leaderboard", List.of("clan"));
            c.register(Commands.literal("tc").executes(ctx -> player(ctx, p -> listener.toggleChat(p)))
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> player(ctx, p -> listener.teamMessage(p,
                                    StringArgumentType.getString(ctx, "message"), false))))
                    .build(), "Team chat (without a message: switch your chat to the team)", List.of());
            c.register(Commands.literal("ac")
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> player(ctx, p -> listener.teamMessage(p,
                                    StringArgumentType.getString(ctx, "message"), true))))
                    .build(), "Message your team and your allies", List.of());
        });
    }

    private static int player(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getSender() instanceof Player p) {
            action.accept(p);
        } else {
            ctx.getSource().getSender().sendMessage(color("&cOnly players can do that."));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static SuggestionProvider<CommandSourceStack> suggest(Supplier<Collection<String>> values) {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (String v : values.get()) {
                if (v.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(v);
                }
            }
            return builder.buildFuture();
        };
    }

    private Collection<String> teamNames() {
        return store.all().stream().map(Team::name).toList();
    }

    private Collection<String> online() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private Collection<String> myMembers(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player p && store.of(p) != null) {
            return new ArrayList<>(store.of(p).names.values());
        }
        return List.of();
    }

    private LiteralArgumentBuilder<CommandSourceStack> teamTree() {
        var root = Commands.literal("team").executes(ctx -> player(ctx, this::info));
        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("tag", StringArgumentType.word())
                                .executes(ctx -> player(ctx, p -> create(p, StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "tag")))))));
        root.then(Commands.literal("invite")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(suggest(this::online))
                        .executes(ctx -> player(ctx, p -> invite(p, StringArgumentType.getString(ctx, "player"))))));
        root.then(Commands.literal("accept").executes(ctx -> player(ctx, p -> accept(p, null)))
                .then(Commands.argument("team", StringArgumentType.word()).suggests(suggest(this::teamNames))
                        .executes(ctx -> player(ctx, p -> accept(p, StringArgumentType.getString(ctx, "team"))))));
        root.then(Commands.literal("deny")
                .then(Commands.argument("team", StringArgumentType.word()).suggests(suggest(this::teamNames))
                        .executes(ctx -> player(ctx, p -> deny(p, StringArgumentType.getString(ctx, "team"))))));
        root.then(Commands.literal("leave").executes(ctx -> player(ctx, this::leave)));
        root.then(Commands.literal("kick")
                .then(Commands.argument("player", StringArgumentType.word()).suggests((ctx, b) -> suggest(() -> myMembers(ctx))
                                .getSuggestions(ctx, b))
                        .executes(ctx -> player(ctx, p -> kick(p, StringArgumentType.getString(ctx, "player"))))));
        for (String sub : List.of("promote", "demote", "leader")) {
            root.then(Commands.literal(sub)
                    .then(Commands.argument("player", StringArgumentType.word()).suggests((ctx, b) -> suggest(() -> myMembers(ctx))
                                    .getSuggestions(ctx, b))
                            .executes(ctx -> player(ctx, p -> rank(p, sub, StringArgumentType.getString(ctx, "player"))))));
        }
        root.then(Commands.literal("disband").executes(ctx -> player(ctx, p -> disband(p, false)))
                .then(Commands.literal("confirm").executes(ctx -> player(ctx, p -> disband(p, true)))));
        root.then(Commands.literal("color")
                .then(Commands.argument("color", StringArgumentType.word())
                        .suggests(suggest(() -> TeamStore.COLORS.stream().map(NamedTextColor::toString).toList()))
                        .executes(ctx -> player(ctx, p -> setColor(p, StringArgumentType.getString(ctx, "color"))))));
        root.then(Commands.literal("tag")
                .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(ctx -> player(ctx, p -> setTag(p, StringArgumentType.getString(ctx, "tag"))))));
        root.then(Commands.literal("ally")
                .then(Commands.argument("team", StringArgumentType.word()).suggests(suggest(this::teamNames))
                        .executes(ctx -> player(ctx, p -> ally(p, StringArgumentType.getString(ctx, "team"))))));
        root.then(Commands.literal("unally")
                .then(Commands.argument("team", StringArgumentType.word()).suggests(suggest(this::teamNames))
                        .executes(ctx -> player(ctx, p -> unally(p, StringArgumentType.getString(ctx, "team"))))));
        root.then(Commands.literal("info").executes(ctx -> player(ctx, this::info))
                .then(Commands.argument("team", StringArgumentType.word()).suggests(suggest(this::teamNames))
                        .executes(ctx -> {
                            info(ctx.getSource().getSender(), store.byName(StringArgumentType.getString(ctx, "team")));
                            return Command.SINGLE_SUCCESS;
                        })));
        root.then(Commands.literal("list").executes(ctx -> {
            list(ctx.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        }));
        root.then(Commands.literal("top").executes(ctx -> {
            top(ctx.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        }));
        root.then(Commands.literal("chat").executes(ctx -> player(ctx, p -> listener.toggleChat(p))));
        root.then(Commands.literal("help").executes(ctx -> {
            help(ctx.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        }));
        var admin = Commands.literal("admin").requires(s -> s.getSender().hasPermission("teams.admin"));
        admin.then(Commands.literal("disband")
                .then(Commands.argument("team", StringArgumentType.word()).suggests(suggest(this::teamNames))
                        .executes(ctx -> {
                            Team t = store.byName(StringArgumentType.getString(ctx, "team"));
                            if (t == null) {
                                tell(ctx.getSource().getSender(), "&cNo such team.");
                            } else {
                                broadcastTeam(t, "&cYour team was disbanded by an admin.");
                                store.disband(t);
                                getLogger().info("[Teams] " + ctx.getSource().getSender().getName() + " disbanded " + t.name + " (admin)");
                                tell(ctx.getSource().getSender(), "&7Disbanded &f" + t.name + "&7.");
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
        admin.then(Commands.literal("reload").executes(ctx -> {
            reloadConfig();
            store.syncAllScoreboard();
            tell(ctx.getSource().getSender(), "&aConfig reloaded.");
            return Command.SINGLE_SUCCESS;
        }));
        admin.then(Commands.literal("update").executes(ctx -> {
            updater.command(ctx.getSource().getSender(), "now");
            return Command.SINGLE_SUCCESS;
        }));
        root.then(admin);
        return root;
    }

    // ================================================================ actions

    private void help(CommandSender s) {
        tell(s, "&6Team commands:");
        for (String line : List.of(
                "&e/team create <name> <tag> &7- found a team (tag: 2-4 letters)",
                "&e/team invite <player> &7- invite someone (max " + maxSize() + " in a team)",
                "&e/team accept [team] &7/ &e/team deny <team>",
                "&e/team leave &7/ &e/team kick <player> &7/ &e/team disband",
                "&e/team promote|demote <player> &7- officers can invite and kick",
                "&e/team leader <player> &7- hand over the team",
                "&e/team color <color> &7/ &e/team tag <tag>",
                "&e/team ally <team> &7/ &e/team unally <team> &7- allies don't hurt each other",
                "&e/team info [team] &7/ &e/team list &7/ &e/team top",
                "&e/tc [message] &7- team chat (or start a message with &f!&7), &e/ac <message> &7- team + allies (&f@&7)")) {
            s.sendMessage(color(line));
        }
    }

    private void create(Player p, String name, String tag) {
        if (store.of(p) != null) {
            tell(p, "&cYou are already in a team. &7/team leave first.");
            return;
        }
        if (!TeamStore.NAME.matcher(name).matches()) {
            tell(p, "&cThe name must be 3-16 letters, digits or _.");
            return;
        }
        if (!TeamStore.TAG.matcher(tag).matches()) {
            tell(p, "&cThe tag must be 2-4 letters or digits.");
            return;
        }
        if (store.byName(name) != null) {
            tell(p, "&cThat name is taken.");
            return;
        }
        if (store.tagTaken(tag, null) || getConfig().getStringList("blocked-words").stream()
                .anyMatch(w -> name.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT))
                        || tag.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT)))) {
            tell(p, "&cThat tag is taken or not allowed.");
            return;
        }
        Team t = store.create(p, name, tag);
        getLogger().info("[Teams] " + p.getName() + " created " + t.name + " [" + t.tag + "]");
        Bukkit.broadcast(prefix().append(color("&f" + p.getName() + " &7founded the team ")).append(teamName(t)).append(color("&7.")));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        tell(p, "&7Invite friends with &e/team invite <player>&7. Team chat: &e/tc &7or start a message with &f!");
    }

    private Team mine(Player p) {
        Team t = store.of(p);
        if (t == null) {
            tell(p, "&cYou are not in a team. &7/team create <name> <tag>");
        }
        return t;
    }

    private void invite(Player p, String targetName) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        if (!t.canManage(p.getUniqueId())) {
            tell(p, "&cOnly the leader and officers can invite.");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            tell(p, "&cThat player isn't online.");
            return;
        }
        if (store.of(target) != null) {
            tell(p, "&c" + target.getName() + " is already in a team.");
            return;
        }
        if (t.members.size() >= maxSize()) {
            tell(p, "&cYour team is full (" + maxSize() + " players).");
            return;
        }
        invites.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>()).put(t.id, System.currentTimeMillis() + 300_000L);
        tell(p, "&7Invited &f" + target.getName() + "&7 (valid for 5 minutes).");
        target.sendMessage(prefix().append(color("&f" + p.getName() + " &7invites you to ")).append(teamName(t))
                .append(color("&7. ")).append(color("&a&l[ACCEPT]").clickEvent(ClickEvent.runCommand("/team accept " + t.name))
                        .hoverEvent(HoverEvent.showText(color("&aJoin " + t.name))))
                .append(color(" ")).append(color("&c[DENY]").clickEvent(ClickEvent.runCommand("/team deny " + t.name))));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
    }

    private void accept(Player p, String teamName) {
        if (store.of(p) != null) {
            tell(p, "&cYou are already in a team.");
            return;
        }
        Map<String, Long> mine = invites.getOrDefault(p.getUniqueId(), Map.of());
        long now = System.currentTimeMillis();
        String id = teamName != null ? teamName.toLowerCase(Locale.ROOT)
                : mine.entrySet().stream().filter(e -> e.getValue() > now).map(Map.Entry::getKey).findFirst().orElse(null);
        Long until = id == null ? null : mine.get(id);
        Team t = store.byName(id);
        if (until == null || until < now || t == null) {
            tell(p, "&cYou have no valid invite" + (teamName != null ? " from that team." : "."));
            return;
        }
        if (t.members.size() >= maxSize()) {
            tell(p, "&cThat team is full now.");
            return;
        }
        invites.remove(p.getUniqueId());
        store.join(t, p.getUniqueId(), p.getName(), Team.Role.MEMBER);
        getLogger().info("[Teams] " + p.getName() + " joined " + t.name);
        broadcastTeam(t, "&a" + p.getName() + " joined the team!");
    }

    private void deny(Player p, String teamName) {
        Map<String, Long> mine = invites.get(p.getUniqueId());
        if (mine != null && mine.remove(teamName.toLowerCase(Locale.ROOT)) != null) {
            tell(p, "&7Invite declined.");
            Team t = store.byName(teamName);
            if (t != null) {
                broadcastTeam(t, "&7" + p.getName() + " declined the invite.");
            }
        }
    }

    private void leave(Player p) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        if (t.role(p.getUniqueId()) == Team.Role.LEADER && t.members.size() > 1) {
            tell(p, "&cYou lead the team. &7Hand it over first (&e/team leader <player>&7) or &e/team disband&7.");
            return;
        }
        store.leave(t, p.getUniqueId());
        getLogger().info("[Teams] " + p.getName() + " left " + t.name + (t.members.isEmpty() ? " (disbanded)" : ""));
        tell(p, "&7You left &f" + t.name + "&7.");
        broadcastTeam(t, "&7" + p.getName() + " left the team.");
    }

    private void kick(Player p, String targetName) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        UUID target = t.names.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(targetName))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (target == null) {
            tell(p, "&cNo such member.");
            return;
        }
        Team.Role mineRole = t.role(p.getUniqueId());
        Team.Role theirs = t.role(target);
        if (target.equals(p.getUniqueId()) || !(mineRole == Team.Role.LEADER || (mineRole == Team.Role.OFFICER && theirs == Team.Role.MEMBER))) {
            tell(p, "&cYou can't kick them.");
            return;
        }
        String name = t.names.get(target);
        store.leave(t, target);
        getLogger().info("[Teams] " + p.getName() + " kicked " + name + " from " + t.name);
        broadcastTeam(t, "&c" + name + " was kicked from the team.");
        Player kicked = Bukkit.getPlayer(target);
        if (kicked != null) {
            tell(kicked, "&cYou were kicked from &f" + t.name + "&c.");
        }
    }

    private void rank(Player p, String what, String targetName) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        if (t.role(p.getUniqueId()) != Team.Role.LEADER) {
            tell(p, "&cOnly the leader can do that.");
            return;
        }
        UUID target = t.names.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(targetName))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (target == null || target.equals(p.getUniqueId())) {
            tell(p, "&cNo such member.");
            return;
        }
        String name = t.names.get(target);
        switch (what) {
            case "promote" -> {
                t.members.put(target, Team.Role.OFFICER);
                broadcastTeam(t, "&a" + name + " is an officer now.");
            }
            case "demote" -> {
                t.members.put(target, Team.Role.MEMBER);
                broadcastTeam(t, "&7" + name + " is a member again.");
            }
            default -> {
                t.members.put(p.getUniqueId(), Team.Role.OFFICER);
                t.members.put(target, Team.Role.LEADER);
                broadcastTeam(t, "&6" + name + " leads the team now.");
                getLogger().info("[Teams] " + p.getName() + " handed " + t.name + " to " + name);
            }
        }
        store.save();
    }

    private void disband(Player p, boolean confirmed) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        if (t.role(p.getUniqueId()) != Team.Role.LEADER) {
            tell(p, "&cOnly the leader can disband the team.");
            return;
        }
        if (!confirmed) {
            p.sendMessage(prefix().append(color("&cReally disband &f" + t.name + "&c? Its points are lost. "))
                    .append(color("&4&l[DISBAND]").clickEvent(ClickEvent.runCommand("/team disband confirm"))));
            return;
        }
        broadcastTeam(t, "&cThe team was disbanded.");
        store.disband(t);
        getLogger().info("[Teams] " + p.getName() + " disbanded " + t.name);
        Bukkit.broadcast(prefix().append(color("&7The team &f" + t.name + " &7is no more.")));
    }

    private void setColor(Player p, String colorName) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        NamedTextColor c = NamedTextColor.NAMES.value(colorName.toLowerCase(Locale.ROOT));
        if (c == null || !TeamStore.COLORS.contains(c)) {
            tell(p, "&cColors: " + String.join(", ", TeamStore.COLORS.stream().map(NamedTextColor::toString).toList()));
            return;
        }
        if (t.role(p.getUniqueId()) != Team.Role.LEADER) {
            tell(p, "&cOnly the leader can do that.");
            return;
        }
        t.color = c;
        store.syncScoreboard(t);
        store.save();
        broadcastTeam(t, "&7The team color is now " + "&" + legacyCode(c) + colorName.toLowerCase(Locale.ROOT) + "&7.");
    }

    private static char legacyCode(NamedTextColor c) {
        String s = LegacyComponentSerializer.legacyAmpersand().serialize(Component.text("x", c));
        return s.length() >= 2 ? s.charAt(1) : 'f';
    }

    private void setTag(Player p, String tag) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        if (t.role(p.getUniqueId()) != Team.Role.LEADER) {
            tell(p, "&cOnly the leader can do that.");
            return;
        }
        if (!TeamStore.TAG.matcher(tag).matches() || store.tagTaken(tag, t)) {
            tell(p, "&cThe tag must be 2-4 letters or digits and not taken.");
            return;
        }
        t.tag = tag.toUpperCase(Locale.ROOT);
        store.syncScoreboard(t);
        store.save();
        broadcastTeam(t, "&7The team tag is now &f[" + t.tag + "]&7.");
    }

    private void ally(Player p, String otherName) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        if (t.role(p.getUniqueId()) != Team.Role.LEADER) {
            tell(p, "&cOnly the leader can make alliances.");
            return;
        }
        Team other = store.byName(otherName);
        if (other == null || other == t) {
            tell(p, "&cNo such team.");
            return;
        }
        if (t.allies.contains(other.id)) {
            tell(p, "&7You are allies already.");
            return;
        }
        int max = getConfig().getInt("max-allies", 1);
        long now = System.currentTimeMillis();
        Long asked = allyRequests.getOrDefault(t.id, Map.of()).get(other.id);
        if (asked != null && asked > now) {
            // they asked us first: this is the yes
            if (t.allies.size() >= max || other.allies.size() >= max) {
                tell(p, "&cOne of the teams already has the most allies (" + max + ").");
                return;
            }
            allyRequests.get(t.id).remove(other.id);
            t.allies.add(other.id);
            other.allies.add(t.id);
            store.save();
            getLogger().info("[Teams] " + t.name + " and " + other.name + " are allies");
            Bukkit.broadcast(prefix().append(teamName(t)).append(color(" &7and ")).append(teamName(other))
                    .append(color(" &7are now &aallies&7.")));
            return;
        }
        if (t.allies.size() >= max) {
            tell(p, "&cYou already have the most allies (" + max + "). &7/team unally first.");
            return;
        }
        allyRequests.computeIfAbsent(other.id, k -> new HashMap<>()).put(t.id, now + 300_000L);
        tell(p, "&7Alliance offered to &f" + other.name + "&7. Their leader has 5 minutes to accept.");
        UUID leader = other.leader();
        Player l = leader == null ? null : Bukkit.getPlayer(leader);
        if (l != null) {
            l.sendMessage(prefix().append(teamName(t)).append(color(" &7offers an alliance. "))
                    .append(color("&a&l[ACCEPT]").clickEvent(ClickEvent.runCommand("/team ally " + t.name))));
        } else {
            tell(p, "&7(their leader isn't online right now)");
        }
    }

    private void unally(Player p, String otherName) {
        Team t = mine(p);
        if (t == null) {
            return;
        }
        Team other = store.byName(otherName);
        if (t.role(p.getUniqueId()) != Team.Role.LEADER || other == null || !t.allies.contains(other.id)) {
            tell(p, "&cYou can't do that.");
            return;
        }
        t.allies.remove(other.id);
        other.allies.remove(t.id);
        store.save();
        getLogger().info("[Teams] " + t.name + " ended the alliance with " + other.name);
        Bukkit.broadcast(prefix().append(teamName(t)).append(color(" &7ended the alliance with ")).append(teamName(other))
                .append(color("&7.")));
    }

    private void info(Player p) {
        Team t = store.of(p);
        if (t == null) {
            help(p);
            return;
        }
        info(p, t);
    }

    private void info(CommandSender s, Team t) {
        if (t == null) {
            tell(s, "&cNo such team.");
            return;
        }
        int rank = store.ranked().indexOf(t) + 1;
        s.sendMessage(Component.text("━━━━ ", NamedTextColor.DARK_GRAY).append(Component.text("[" + t.tag + "] " + t.name, t.color)
                .decorate(TextDecoration.BOLD)).append(Component.text(" ━━━━", NamedTextColor.DARK_GRAY)));
        for (Map.Entry<UUID, Team.Role> e : t.members.entrySet()) {
            boolean on = Bukkit.getPlayer(e.getKey()) != null;
            s.sendMessage(color((on ? "&a● " : "&8● ") + "&f" + t.names.getOrDefault(e.getKey(), "?") + " &7" + e.getValue().label()));
        }
        s.sendMessage(color("&7Points: &e" + t.points + " &8(#" + rank + ")  &7Kills: &f" + t.kills + "  &7Deaths: &f" + t.deaths
                + "  &7K/D: &f" + t.kd()));
        if (!t.allies.isEmpty()) {
            Component line = color("&7Allies: ");
            for (String a : t.allies) {
                Team ally = store.byName(a);
                if (ally != null) {
                    line = line.append(teamName(ally)).append(color(" "));
                }
            }
            s.sendMessage(line);
        }
    }

    private void list(CommandSender s) {
        if (store.all().isEmpty()) {
            tell(s, "&7No teams yet. &e/team create <name> <tag>");
            return;
        }
        tell(s, "&6Teams (" + store.all().size() + "):");
        for (Team t : store.ranked()) {
            long on = t.members.keySet().stream().filter(u -> Bukkit.getPlayer(u) != null).count();
            s.sendMessage(color("&7- ").append(teamName(t)).append(color(" &8" + t.members.size() + "/" + maxSize()
                    + " &7(" + on + " online)")));
        }
    }

    private void top(CommandSender s) {
        List<Team> ranked = store.ranked();
        if (ranked.isEmpty()) {
            tell(s, "&7No teams yet.");
            return;
        }
        tell(s, "&6Team leaderboard:");
        for (int i = 0; i < Math.min(10, ranked.size()); i++) {
            Team t = ranked.get(i);
            String medal = i == 0 ? "&6" : i == 1 ? "&f" : i == 2 ? "&c" : "&7";
            s.sendMessage(color(medal + (i + 1) + ". ").append(teamName(t)).append(color(" &e" + t.points + " points &8- "
                    + t.kills + " kills, K/D " + t.kd())));
        }
        if (s instanceof Player p && store.of(p) != null && ranked.indexOf(store.of(p)) >= 10) {
            Team t = store.of(p);
            s.sendMessage(color("&7...").append(color(" &7" + (ranked.indexOf(t) + 1) + ". ")).append(teamName(t))
                    .append(color(" &e" + t.points + " points")));
        }
    }

    void broadcastTeam(Team t, String legacy) {
        for (UUID u : t.members.keySet()) {
            Player m = Bukkit.getPlayer(u);
            if (m != null) {
                m.sendMessage(Component.text("[" + t.tag + "] ", t.color).append(color(legacy)));
            }
        }
    }

    static String offlineName(UUID id) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        return p.getName() == null ? "?" : p.getName();
    }
}
