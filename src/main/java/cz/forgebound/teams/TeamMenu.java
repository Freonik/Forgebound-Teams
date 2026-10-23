package cz.forgebound.teams;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Everything about teams, clickable: /team opens it. Names and tags are typed into a dialog window. */
public final class TeamMenu implements Listener {

    private final ForgeboundTeams plugin;
    private final TeamStore store;
    private final TeamListener listener;

    TeamMenu(ForgeboundTeams plugin, TeamStore store, TeamListener listener) {
        this.plugin = plugin;
        this.store = store;
        this.listener = listener;
    }

    // ================================================================ plumbing

    private static final class Menu implements InventoryHolder {
        final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static Menu menu(int rows, String title) {
        Menu m = new Menu();
        m.inventory = Bukkit.createInventory(m, rows * 9, ForgeboundTeams.color(title));
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemMeta meta = pane.getItemMeta();
        meta.setHideTooltip(true);
        pane.setItemMeta(meta);
        for (int i = 0; i < rows * 9; i++) {
            m.inventory.setItem(i, pane);
        }
        return m;
    }

    private static void set(Menu m, int slot, ItemStack stack, Consumer<Player> click) {
        m.inventory.setItem(slot, stack);
        if (click != null) {
            m.actions.put(slot, (p, type) -> click.accept(p));
        }
    }

    private static void set(Menu m, int slot, ItemStack stack, BiConsumer<Player, ClickType> click) {
        m.inventory.setItem(slot, stack);
        m.actions.put(slot, click);
    }

    static ItemStack item(Material material, String name, String... lore) {
        ItemStack s = new ItemStack(material);
        ItemMeta meta = s.getItemMeta();
        meta.displayName(ForgeboundTeams.color(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            if (l != null) {
                lines.add(ForgeboundTeams.color(l));
            }
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.values());
        s.setItemMeta(meta);
        return s;
    }

    private static ItemStack head(UUID id, String name, String... lore) {
        ItemStack s = item(Material.PLAYER_HEAD, name, lore);
        SkullMeta meta = (SkullMeta) s.getItemMeta();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(id);
        meta.setOwningPlayer(owner);
        s.setItemMeta(meta);
        return s;
    }

    private static Material banner(NamedTextColor c) {
        String dye;
        if (c == NamedTextColor.RED || c == NamedTextColor.DARK_RED) {
            dye = c == NamedTextColor.RED ? "RED" : "BROWN";
        } else if (c == NamedTextColor.GOLD) {
            dye = "ORANGE";
        } else if (c == NamedTextColor.YELLOW) {
            dye = "YELLOW";
        } else if (c == NamedTextColor.GREEN) {
            dye = "LIME";
        } else if (c == NamedTextColor.DARK_GREEN) {
            dye = "GREEN";
        } else if (c == NamedTextColor.AQUA) {
            dye = "LIGHT_BLUE";
        } else if (c == NamedTextColor.DARK_AQUA) {
            dye = "CYAN";
        } else if (c == NamedTextColor.BLUE) {
            dye = "BLUE";
        } else if (c == NamedTextColor.LIGHT_PURPLE) {
            dye = "MAGENTA";
        } else if (c == NamedTextColor.DARK_PURPLE) {
            dye = "PURPLE";
        } else if (c == NamedTextColor.GRAY) {
            dye = "GRAY";
        } else {
            dye = "WHITE";
        }
        return Material.valueOf(dye + "_BANNER");
    }

    private static String legacy(NamedTextColor c) {
        String s = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .serialize(Component.text("x", c));
        return s.length() >= 2 ? "&" + s.charAt(1) : "&f";
    }

    private ItemStack teamItem(Team t, String... extra) {
        int rank = store.ranked().indexOf(t) + 1;
        long online = t.members.keySet().stream().filter(u -> Bukkit.getPlayer(u) != null).count();
        List<String> lore = new ArrayList<>(List.of(
                "&7Members: &f" + t.members.size() + "/" + plugin.maxSize() + " &8(" + online + " online)",
                "&7Points: &e" + t.points + " &8(#" + rank + ")",
                "&7Kills: &f" + t.kills + " &7Deaths: &f" + t.deaths + " &7K/D: &f" + t.kd()));
        lore.addAll(List.of(extra));
        return item(banner(t.color), legacy(t.color) + "&l[" + t.tag + "] " + t.name, lore.toArray(String[]::new));
    }

    private void show(Player p, Menu m) {
        p.openInventory(m.inventory);
    }

    /** Runs an action, then reopens a menu with fresh data. */
    private void then(Player p, Runnable action, Consumer<Player> reopen) {
        action.run();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                reopen.accept(p);
            }
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu m)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        BiConsumer<Player, ClickType> action = m.actions.get(event.getSlot());
        if (action != null) {
            action.accept(p, event.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private void confirm(Player p, String question, String detail, Runnable yes, Consumer<Player> back) {
        Menu m = menu(3, "&8" + question);
        set(m, 11, item(Material.LIME_CONCRETE, "&a&lYes", detail), (Consumer<Player>) pl -> then(pl, yes, this::open));
        set(m, 15, item(Material.RED_CONCRETE, "&c&lNo, go back"), (Consumer<Player>) back);
        show(p, m);
    }

    // ================================================================ main menu

    public void open(Player p) {
        Team t = store.of(p);
        if (t == null) {
            noTeam(p);
        } else {
            team(p, t);
        }
    }

    private void noTeam(Player p) {
        Menu m = menu(3, "&8Teams");
        set(m, 10, item(Material.NAME_TAG, "&a&lCreate a team", "&7Pick a name and a 2-4 letter tag.",
                "&7Up to &f" + plugin.maxSize() + " &7players per team.", "", "&eClick"), (Consumer<Player>) this::createDialog);
        List<String> invites = plugin.invitesOf(p.getUniqueId());
        set(m, 12, item(invites.isEmpty() ? Material.PAPER : Material.WRITABLE_BOOK,
                (invites.isEmpty() ? "&7" : "&e&l") + "Invites (" + invites.size() + ")",
                invites.isEmpty() ? "&7Nobody invited you yet." : "&eClick to see them"), (Consumer<Player>) this::invites);
        set(m, 14, item(Material.BOOKSHELF, "&f&lAll teams", "&7" + store.all().size() + " teams", "", "&eClick"),
                (Consumer<Player>) pl -> teams(pl, null));
        set(m, 16, item(Material.GOLD_INGOT, "&6&lLeaderboard", "&7The best teams by points", "", "&eClick"),
                (Consumer<Player>) this::leaderboard);
        show(p, m);
    }

    private void team(Player p, Team t) {
        UUID me = p.getUniqueId();
        boolean leader = t.role(me) == Team.Role.LEADER;
        Menu m = menu(6, legacy(t.color) + "[" + t.tag + "] " + t.name);
        set(m, 4, teamItem(t, "", "&7Your role: &f" + t.role(me).label()), (Consumer<Player>) null);
        // members
        List<UUID> members = new ArrayList<>(t.members.keySet());
        int size = plugin.maxSize();
        int width = Math.min(7, size);
        int start = 9 * 2 + 1 + (7 - width) / 2;
        for (int i = 0; i < width; i++) {
            int slot = start + i;
            if (i < members.size()) {
                UUID u = members.get(i);
                String name = t.names.getOrDefault(u, "?");
                boolean online = Bukkit.getPlayer(u) != null;
                Team.Role role = t.role(u);
                boolean canAct = !u.equals(me) && (leader || (t.role(me) == Team.Role.OFFICER && role == Team.Role.MEMBER));
                set(m, slot, head(u, (online ? "&a" : "&7") + name, "&7" + role.label() + (online ? " &a● online" : " &8● offline"),
                                canAct ? "" : null, canAct ? "&eClick to manage" : null),
                        canAct ? (Consumer<Player>) pl -> member(pl, u) : null);
            } else if (t.canManage(me)) {
                set(m, slot, item(Material.LIME_DYE, "&a&lInvite a player", "&7An empty place in the team.", "", "&eClick"),
                        (Consumer<Player>) this::invitePicker);
            } else {
                set(m, slot, item(Material.GRAY_DYE, "&7Free place", "&7The leader or an officer can invite."), (Consumer<Player>) null);
            }
        }
        // buttons
        boolean chat = listener.teamChatOn(p);
        set(m, 37, item(chat ? Material.WRITABLE_BOOK : Material.BOOK, "&a&lTeam chat: " + (chat ? "&aON" : "&7OFF"),
                "&7ON: everything you write goes to the team.", "&7Anytime: &f!message &7= team, &f@message &7= team + allies",
                "", "&eClick to switch"), (Consumer<Player>) pl -> then(pl, () -> listener.toggleChat(pl), this::open));
        int allyOffers = plugin.allyOffersTo(t).size();
        set(m, 38, item(Material.EMERALD, "&a&lAllies (" + t.allies.size() + ")",
                "&7Allies can't hurt each other.", allyOffers > 0 ? "&e" + allyOffers + " offer(s) waiting!" : "", "&eClick"),
                (Consumer<Player>) pl -> allies(pl));
        int peace = plugin.peaceOffersTo(t).size();
        set(m, 39, item(Material.IRON_SWORD, "&c&lWars (" + t.enemies.size() + ")",
                "&7Kills against enemies count extra.", peace > 0 ? "&e" + peace + " peace offer(s) waiting!" : "", "&eClick"),
                (Consumer<Player>) pl -> wars(pl));
        set(m, 40, item(Material.GOLD_INGOT, "&6&lLeaderboard", "&7The best teams by points", "", "&eClick"),
                (Consumer<Player>) this::leaderboard);
        set(m, 41, item(Material.BOOKSHELF, "&f&lAll teams", "&7" + store.all().size() + " teams", "", "&eClick"),
                (Consumer<Player>) pl -> teams(pl, null));
        if (leader) {
            set(m, 42, item(Material.COMPARATOR, "&e&lSettings", "&7Color and tag", "", "&eClick"), (Consumer<Player>) this::settings);
        } else {
            set(m, 42, item(Material.GRAY_DYE, "&7Settings", "&7Only the leader can change them."), (Consumer<Player>) null);
        }
        if (leader) {
            set(m, 43, item(Material.BARRIER, "&4&lDisband the team", t.members.size() > 1 ? "&7Hand the team over to leave it," : "",
                    t.members.size() > 1 ? "&7or disband it for everyone." : "", "", "&cClick"),
                    (Consumer<Player>) pl -> confirm(pl, "Disband " + t.name + "?", "&7Its points are lost.",
                            () -> plugin.disband(pl, true), this::open));
        } else {
            set(m, 43, item(Material.OAK_DOOR, "&c&lLeave the team", "", "&cClick"),
                    (Consumer<Player>) pl -> confirm(pl, "Leave " + t.name + "?", "", () -> plugin.leave(pl), this::open));
        }
        show(p, m);
    }

    // ================================================================ members

    private void member(Player p, UUID target) {
        Team t = store.of(p);
        if (t == null || !t.members.containsKey(target)) {
            open(p);
            return;
        }
        String name = t.names.getOrDefault(target, "?");
        Team.Role role = t.role(target);
        boolean leader = t.role(p.getUniqueId()) == Team.Role.LEADER;
        Menu m = menu(3, "&8" + name);
        set(m, 4, head(target, "&f" + name, "&7" + role.label()), (Consumer<Player>) null);
        if (leader) {
            if (role == Team.Role.MEMBER) {
                set(m, 10, item(Material.GOLDEN_HELMET, "&e&lMake officer", "&7Officers can invite and kick members.", "", "&eClick"),
                        (Consumer<Player>) pl -> then(pl, () -> plugin.rank(pl, "promote", name), q -> member(q, target)));
            } else {
                set(m, 10, item(Material.LEATHER_HELMET, "&7&lMake member", "&7Takes the officer rights away.", "", "&eClick"),
                        (Consumer<Player>) pl -> then(pl, () -> plugin.rank(pl, "demote", name), q -> member(q, target)));
            }
            set(m, 13, item(Material.GOLDEN_APPLE, "&6&lMake leader", "&7You become an officer.", "", "&eClick"),
                    (Consumer<Player>) pl -> confirm(pl, "Make " + name + " the leader?", "&7You become an officer.",
                            () -> plugin.rank(pl, "leader", name), q -> member(q, target)));
        }
        set(m, 16, item(Material.IRON_DOOR, "&c&lKick", "", "&cClick"),
                (Consumer<Player>) pl -> confirm(pl, "Kick " + name + "?", "", () -> plugin.kick(pl, name), q -> member(q, target)));
        set(m, 22, item(Material.ARROW, "&7Back"), (Consumer<Player>) this::open);
        show(p, m);
    }

    private void invitePicker(Player p) {
        Team t = store.of(p);
        Menu m = menu(6, "&8Invite a player");
        int slot = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) {
                break;
            }
            if (store.of(o) == null && !o.equals(p)) {
                String name = o.getName();
                set(m, slot++, head(o.getUniqueId(), "&f" + name, "&eClick to invite"),
                        (Consumer<Player>) pl -> then(pl, () -> plugin.invite(pl, name), this::open));
            }
        }
        if (slot == 0) {
            set(m, 22, item(Material.BARRIER, "&7Nobody to invite", "&7Everyone online is in a team already."), (Consumer<Player>) null);
        }
        set(m, 49, item(Material.ARROW, "&7Back"), (Consumer<Player>) this::open);
        show(p, m);
    }

    private void invites(Player p) {
        Menu m = menu(3, "&8Your invites");
        int slot = 10;
        for (String id : plugin.invitesOf(p.getUniqueId())) {
            Team t = store.byName(id);
            if (t == null || slot > 16) {
                continue;
            }
            set(m, slot++, teamItem(t, "", "&aLeft-click: join", "&cRight-click: decline"), (pl, click) -> {
                if (click.isRightClick()) {
                    then(pl, () -> plugin.deny(pl, t.name), this::invites);
                } else {
                    then(pl, () -> plugin.accept(pl, t.name), this::open);
                }
            });
        }
        if (slot == 10) {
            set(m, 13, item(Material.PAPER, "&7No invites", "&7Ask a team leader to invite you."), (Consumer<Player>) null);
        }
        set(m, 22, item(Material.ARROW, "&7Back"), (Consumer<Player>) this::open);
        show(p, m);
    }

    // ================================================================ teams, leaderboard

    private void teams(Player p, String unused) {
        Menu m = menu(6, "&8All teams");
        int slot = 0;
        for (Team t : store.ranked()) {
            if (slot >= 45) {
                break;
            }
            set(m, slot++, teamItem(t, "", "&eClick for the members"), (Consumer<Player>) pl -> teamInfo(pl, t, this::open));
        }
        set(m, 49, item(Material.ARROW, "&7Back"), (Consumer<Player>) this::open);
        show(p, m);
    }

    private void teamInfo(Player p, Team t, Consumer<Player> back) {
        Menu m = menu(3, legacy(t.color) + "[" + t.tag + "] " + t.name);
        set(m, 4, teamItem(t), (Consumer<Player>) null);
        int slot = 10;
        for (Map.Entry<UUID, Team.Role> e : t.members.entrySet()) {
            if (slot > 16) {
                break;
            }
            boolean online = Bukkit.getPlayer(e.getKey()) != null;
            set(m, slot++, head(e.getKey(), (online ? "&a" : "&7") + t.names.getOrDefault(e.getKey(), "?"),
                    "&7" + e.getValue().label() + (online ? " &a● online" : " &8● offline")), (Consumer<Player>) null);
        }
        List<String> rel = new ArrayList<>();
        for (String a : t.allies) {
            Team ally = store.byName(a);
            if (ally != null) {
                rel.add("&a+ ally " + legacy(ally.color) + "[" + ally.tag + "] " + ally.name);
            }
        }
        for (Map.Entry<String, Integer> e : t.enemies.entrySet()) {
            Team enemy = store.byName(e.getKey());
            if (enemy != null) {
                rel.add("&c⚔ war " + legacy(enemy.color) + "[" + enemy.tag + "] " + enemy.name + " &8(" + e.getValue() + " : "
                        + enemy.enemies.getOrDefault(t.id, 0) + ")");
            }
        }
        if (!rel.isEmpty()) {
            set(m, 22, item(Material.MAP, "&f&lAllies and wars", rel.toArray(String[]::new)), (Consumer<Player>) null);
        }
        set(m, 18, item(Material.ARROW, "&7Back"), (Consumer<Player>) back);
        show(p, m);
    }

    private void leaderboard(Player p) {
        Menu m = menu(3, "&8Team leaderboard");
        List<Team> ranked = store.ranked();
        int[] slots = {4, 12, 14, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < Math.min(slots.length, ranked.size()); i++) {
            Team t = ranked.get(i);
            String medal = i == 0 ? "&6&l#1 " : i == 1 ? "&f&l#2 " : i == 2 ? "&c&l#3 " : "&7#" + (i + 1) + " ";
            ItemStack s = teamItem(t, "", "&eClick for the members");
            ItemMeta meta = s.getItemMeta();
            meta.displayName(ForgeboundTeams.color(medal).append(meta.displayName()));
            s.setItemMeta(meta);
            if (i == 0) {
                s.setType(Material.GOLD_BLOCK);
            }
            set(m, slots[i], s, (Consumer<Player>) pl -> teamInfo(pl, t, this::leaderboard));
        }
        if (ranked.isEmpty()) {
            set(m, 13, item(Material.PAPER, "&7No teams yet"), (Consumer<Player>) null);
        }
        set(m, 18, item(Material.ARROW, "&7Back"), (Consumer<Player>) this::open);
        show(p, m);
    }

    // ================================================================ allies and wars

    private void allies(Player p) {
        Team t = store.of(p);
        if (t == null) {
            open(p);
            return;
        }
        boolean leader = t.role(p.getUniqueId()) == Team.Role.LEADER;
        Menu m = menu(4, "&8Allies");
        int slot = 10;
        for (String a : t.allies) {
            Team ally = store.byName(a);
            if (ally == null || slot > 16) {
                continue;
            }
            set(m, slot++, teamItem(ally, "", "&aAlly", leader ? "&cClick to end the alliance" : ""), (Consumer<Player>) pl -> {
                if (leader) {
                    confirm(pl, "End the alliance?", "&7with " + ally.name, () -> plugin.unally(pl, ally.name), this::allies);
                }
            });
        }
        for (String id : plugin.allyOffersTo(t)) {
            Team from = store.byName(id);
            if (from == null || slot > 16) {
                continue;
            }
            set(m, slot++, teamItem(from, "", "&eOffers you an alliance!", leader ? "&aClick to accept" : "&7The leader can accept."),
                    (Consumer<Player>) pl -> {
                        if (leader) {
                            then(pl, () -> plugin.ally(pl, from.name), this::allies);
                        }
                    });
        }
        if (slot == 10) {
            set(m, 13, item(Material.PAPER, "&7No allies yet"), (Consumer<Player>) null);
        }
        if (leader) {
            set(m, 31, item(Material.EMERALD_BLOCK, "&a&lOffer an alliance", "&7Max " + plugin.getConfig().getInt("max-allies", 1)
                    + " ally.", "", "&eClick to pick a team"), (Consumer<Player>) pl -> teamPicker(pl, "Offer an alliance to...",
                    other -> !t.allies.contains(other.id) && !t.enemies.containsKey(other.id),
                    (q, other) -> then(q, () -> plugin.ally(q, other.name), this::allies), this::allies));
        }
        set(m, 27, item(Material.ARROW, "&7Back"), (Consumer<Player>) this::open);
        show(p, m);
    }

    private void wars(Player p) {
        Team t = store.of(p);
        if (t == null) {
            open(p);
            return;
        }
        boolean leader = t.role(p.getUniqueId()) == Team.Role.LEADER;
        List<String> offers = plugin.peaceOffersTo(t);
        Menu m = menu(4, "&8Wars");
        int slot = 10;
        for (Map.Entry<String, Integer> e : t.enemies.entrySet()) {
            Team enemy = store.byName(e.getKey());
            if (enemy == null || slot > 16) {
                continue;
            }
            boolean offered = offers.contains(enemy.id);
            set(m, slot++, teamItem(enemy, "", "&c⚔ At war &8- score &f" + e.getValue() + " : " + enemy.enemies.getOrDefault(t.id, 0),
                    offered ? "&eThey offer peace!" : "",
                    leader ? (offered ? "&aClick to accept peace" : "&eClick to offer peace") : "&7The leader can make peace."),
                    (Consumer<Player>) pl -> {
                        if (leader) {
                            then(pl, () -> plugin.peace(pl, enemy.name), this::wars);
                        }
                    });
        }
        if (slot == 10) {
            set(m, 13, item(Material.PAPER, "&7No wars", "&7Peaceful... for now."), (Consumer<Player>) null);
        }
        if (leader) {
            set(m, 31, item(Material.NETHERITE_SWORD, "&4&lDeclare war", "&7Kills against them count &f+"
                    + plugin.getConfig().getLong("points.enemy-kill-bonus", 3) + " &7extra.", "&7The whole server hears it.", "",
                    "&eClick to pick a team"), (Consumer<Player>) pl -> teamPicker(pl, "Declare war on...",
                    other -> !t.allies.contains(other.id) && !t.enemies.containsKey(other.id),
                    (q, other) -> confirm(q, "War on " + other.name + "?", "&7Both teams are at war at once.",
                            () -> plugin.enemy(q, other.name), this::wars), this::wars));
        }
        set(m, 27, item(Material.ARROW, "&7Back"), (Consumer<Player>) this::open);
        show(p, m);
    }

    private void teamPicker(Player p, String title, java.util.function.Predicate<Team> filter,
                            BiConsumer<Player, Team> pick, Consumer<Player> back) {
        Team mine = store.of(p);
        Menu m = menu(6, "&8" + title);
        int slot = 0;
        for (Team t : store.ranked()) {
            if (slot >= 45) {
                break;
            }
            if (t != mine && filter.test(t)) {
                set(m, slot++, teamItem(t, "", "&eClick"), (Consumer<Player>) pl -> pick.accept(pl, t));
            }
        }
        if (slot == 0) {
            set(m, 22, item(Material.BARRIER, "&7No teams to pick"), (Consumer<Player>) null);
        }
        set(m, 49, item(Material.ARROW, "&7Back"), (Consumer<Player>) back);
        show(p, m);
    }

    // ================================================================ settings

    private void settings(Player p) {
        Team t = store.of(p);
        if (t == null || t.role(p.getUniqueId()) != Team.Role.LEADER) {
            open(p);
            return;
        }
        Menu m = menu(4, "&8Team settings");
        int slot = 10;
        for (NamedTextColor c : TeamStore.COLORS) {
            if (slot == 17) {
                slot = 19;
            }
            String name = c.toString();
            boolean current = c == t.color;
            ItemStack s = item(banner(c), legacy(c) + "&l" + name.replace('_', ' '), current ? "&a✔ current color" : "&eClick");
            set(m, slot++, s, (Consumer<Player>) pl -> then(pl, () -> plugin.setColor(pl, name), this::settings));
        }
        set(m, 31, item(Material.NAME_TAG, "&f&lChange the tag", "&7Now: " + legacy(t.color) + "[" + t.tag + "]", "", "&eClick"),
                (Consumer<Player>) this::tagDialog);
        set(m, 27, item(Material.ARROW, "&7Back"), (Consumer<Player>) this::open);
        show(p, m);
    }

    // ================================================================ dialogs (typing)

    private DialogAction action(BiConsumer<Player, io.papermc.paper.dialog.DialogResponseView> then) {
        ClickCallback.Options options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        return DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player player) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        then.accept(player, view);
                    }
                });
            }
        }, options);
    }

    private void form(Player p, String title, String text, List<? extends DialogInput> inputs, ActionButton ok) {
        p.closeInventory();
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(ForgeboundTeams.color(title)).canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(ForgeboundTeams.color(text), 260))).inputs(inputs).build())
                .type(DialogType.confirmation(ok, ActionButton.builder(ForgeboundTeams.color("&7Cancel")).width(150).build())));
        p.showDialog(dialog);
    }

    private void createDialog(Player p) {
        var name = DialogInput.text("name", ForgeboundTeams.color("&7Team name &8(3-16 letters, digits, _)")).maxLength(16).width(220).build();
        var tag = DialogInput.text("tag", ForgeboundTeams.color("&7Tag &8(2-4 letters or digits)")).maxLength(4).width(120).build();
        ActionButton ok = ActionButton.builder(ForgeboundTeams.color("&a&lCreate")).width(150)
                .action(action((pl, view) -> {
                    String n = view.getText("name") == null ? "" : view.getText("name").trim();
                    String g = view.getText("tag") == null ? "" : view.getText("tag").trim();
                    plugin.create(pl, n, g);
                    if (store.of(pl) != null) {
                        open(pl);
                    }
                })).build();
        form(p, "&6&lCreate a team", "&7The tag shows in front of your names: &f[TAG] " + p.getName(), List.of(name, tag), ok);
    }

    private void tagDialog(Player p) {
        Team t = store.of(p);
        if (t == null) {
            return;
        }
        var tag = DialogInput.text("tag", ForgeboundTeams.color("&7New tag &8(2-4 letters or digits)")).initial(t.tag)
                .maxLength(4).width(120).build();
        ActionButton ok = ActionButton.builder(ForgeboundTeams.color("&a&lSave")).width(150)
                .action(action((pl, view) -> {
                    plugin.setTag(pl, view.getText("tag") == null ? "" : view.getText("tag").trim());
                    settings(pl);
                })).build();
        form(p, "&f&lChange the tag", "&7Now: [" + t.tag + "]", List.of(tag), ok);
    }
}
