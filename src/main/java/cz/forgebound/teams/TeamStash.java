package cz.forgebound.teams;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * The team stash: one shared chest per team that only takes Bounty Tokens (Forgebound items).
 * Every member can put tokens in and take them out; everyone looks at the same live inventory.
 */
public final class TeamStash implements Listener {

    static final int SIZE = 27;
    private static final NamespacedKey TOKEN = new NamespacedKey("customcraft", "bounty_token");

    private final ForgeboundTeams plugin;
    private final TeamStore store;
    private final Map<String, Holder> open = new HashMap<>();

    TeamStash(ForgeboundTeams plugin, TeamStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    static final class Holder implements InventoryHolder {
        final String team;
        Inventory inventory;
        int before;

        Holder(String team) {
            this.team = team;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static ItemStack[] copy(ItemStack[] c) {
        ItemStack[] out = new ItemStack[c.length];
        for (int i = 0; i < c.length; i++) {
            out[i] = c[i] == null || c[i].isEmpty() ? null : c[i].clone();
        }
        return out;
    }

    static boolean isToken(ItemStack s) {
        return s != null && !s.isEmpty() && s.hasItemMeta()
                && s.getItemMeta().getPersistentDataContainer().has(TOKEN, PersistentDataType.BYTE);
    }

    static int count(ItemStack[] items) {
        int n = 0;
        if (items != null) {
            for (ItemStack s : items) {
                if (isToken(s)) {
                    n += s.getAmount();
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- tokens in and out (war pots, weekly rewards)

    private ItemStack[] contents(Team t) {
        Holder h = open.get(t.id);
        if (h != null) {
            return h.inventory.getContents();
        }
        return t.stash == null ? new ItemStack[SIZE] : java.util.Arrays.copyOf(t.stash, SIZE);
    }

    private void setContents(Team t, ItemStack[] c) {
        Holder h = open.get(t.id);
        if (h != null) {
            h.inventory.setContents(c);
            h.before = count(c);
        }
        t.stash = copy(c);
        store.save();
    }

    /** All the tokens the team has in its stash. */
    int tokens(Team t) {
        return count(contents(t)) + t.owed;
    }

    /** Takes n tokens out of the stash; false (and nothing taken) if there aren't that many. */
    boolean take(Team t, int n) {
        if (n <= 0) {
            return true;
        }
        if (tokens(t) < n) {
            return false;
        }
        int fromOwed = Math.min(t.owed, n);
        t.owed -= fromOwed;
        n -= fromOwed;
        ItemStack[] c = contents(t);
        for (int i = c.length - 1; i >= 0 && n > 0; i--) {
            if (isToken(c[i])) {
                int use = Math.min(n, c[i].getAmount());
                n -= use;
                if (use == c[i].getAmount()) {
                    c[i] = null;
                } else {
                    c[i].setAmount(c[i].getAmount() - use);
                }
            }
        }
        setContents(t, c);
        return true;
    }

    /** Puts n new tokens into the stash (what doesn't fit is owed and comes in when there's room). */
    void give(Team t, int n) {
        if (n <= 0) {
            return;
        }
        ItemStack[] c = contents(t);
        ItemStack sample = FbBridge.tokens(1);
        if (sample != null) {
            for (int i = 0; i < c.length && n > 0; i++) {
                if (isToken(c[i]) && c[i].isSimilar(sample) && c[i].getAmount() < c[i].getMaxStackSize()) {
                    int add = Math.min(n, c[i].getMaxStackSize() - c[i].getAmount());
                    c[i].setAmount(c[i].getAmount() + add);
                    n -= add;
                }
            }
            for (int i = 0; i < c.length && n > 0; i++) {
                if (c[i] == null || c[i].isEmpty()) {
                    c[i] = FbBridge.tokens(Math.min(64, n));
                    n -= c[i].getAmount();
                }
            }
        }
        t.owed += n;
        setContents(t, c);
    }

    void open(Player p) {
        Team t = store.of(p);
        if (t == null) {
            ForgeboundTeams.tell(p, "&cYou're not in a team.");
            return;
        }
        Holder h = open.computeIfAbsent(t.id, id -> {
            Holder nh = new Holder(id);
            nh.inventory = Bukkit.createInventory(nh, SIZE, ForgeboundTeams.color("&8Team stash &7- Bounty Tokens"));
            if (t.stash != null) {
                nh.inventory.setContents(java.util.Arrays.copyOf(t.stash, SIZE));
            }
            nh.before = count(nh.inventory.getContents());
            return nh;
        });
        if (t.owed > 0 && FbBridge.present()) {
            int owed = t.owed;
            t.owed = 0;
            give(t, owed);
        }
        p.openInventory(h.inventory);
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.1f);
    }

    /** Writes an open stash back into its team (and teams.yml). */
    private void flush(Holder h) {
        Team t = store.byId(h.team);
        if (t == null) {
            return;
        }
        t.stash = copy(h.inventory.getContents());
        int now = count(t.stash);
        if (now != h.before) {
            plugin.getLogger().info("[Teams] " + t.name + " stash: " + h.before + " -> " + now + " tokens");
            h.before = now;
        }
        store.save();
    }

    /** Saves and closes every open stash (plugin disable, team disband). */
    void closeAll() {
        for (Holder h : open.values()) {
            flush(h);
            for (HumanEntity v : new java.util.ArrayList<>(h.inventory.getViewers())) {
                v.closeInventory();
            }
        }
        open.clear();
    }

    void close(Team t) {
        Holder h = open.remove(t.id);
        if (h != null) {
            flush(h);
            for (HumanEntity v : new java.util.ArrayList<>(h.inventory.getViewers())) {
                v.closeInventory();
            }
        }
    }

    // ---------------------------------------------------------------- only tokens go in

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        Team t = store.byId(h.team);
        if (t == null || !(event.getWhoClicked() instanceof Player p) || store.of(p) != t) {
            event.setCancelled(true);                    // kicked from the team while looking at it
            return;
        }
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        InventoryAction a = event.getAction();
        if (top) {
            switch (a) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                    if (!isToken(event.getCursor())) {
                        deny(event);
                    }
                }
                case HOTBAR_SWAP -> {
                    ItemStack hot = event.getHotbarButton() >= 0 ? p.getInventory().getItem(event.getHotbarButton())
                            : p.getInventory().getItemInOffHand();
                    if (hot != null && !hot.isEmpty() && !isToken(hot)) {
                        deny(event);
                    }
                }
                default -> {
                }
            }
        } else if (a == InventoryAction.MOVE_TO_OTHER_INVENTORY && !isToken(event.getCurrentItem())) {
            deny(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean intoTop = event.getRawSlots().stream().anyMatch(s -> s < topSize);
        if (intoTop && !isToken(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    private static void deny(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player p) {
            p.sendActionBar(ForgeboundTeams.color("&cOnly Bounty Tokens go into the team stash."));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder h)) {
            return;
        }
        flush(h);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (h.inventory.getViewers().isEmpty()) {
                open.remove(h.team, h);
            }
        });
    }
}
