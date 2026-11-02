package cz.forgebound.teams;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/** What Forgebound shares with the teams: a player's bounty and new Bounty Tokens. Without Forgebound: 0 / nothing. */
final class FbBridge {

    private FbBridge() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T provider(Class type) {
        for (Object o : Bukkit.getServicesManager().getRegistrations(type)) {
            RegisteredServiceProvider<?> reg = (RegisteredServiceProvider<?>) o;
            if (reg.getPlugin().getName().equals("Forgebound") && reg.getPlugin().isEnabled()) {
                return (T) reg.getProvider();
            }
        }
        return null;
    }

    static boolean present() {
        return provider(IntFunction.class) != null;
    }

    static int bountyOf(UUID player) {
        ToIntFunction<UUID> f = provider(ToIntFunction.class);
        try {
            return f == null ? 0 : Math.max(0, f.applyAsInt(player));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** The team's bounty: the bounties of all its members together. */
    static int teamBounty(Team t) {
        int sum = 0;
        for (UUID u : t.members.keySet()) {
            sum += bountyOf(u);
        }
        return sum;
    }

    /** A stack of n Bounty Tokens (n <= 64), or null without Forgebound. */
    static ItemStack tokens(int n) {
        IntFunction<ItemStack> f = provider(IntFunction.class);
        try {
            return f == null || n <= 0 ? null : f.apply(Math.min(64, n));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Gives n tokens to a player (what doesn't fit drops at their feet). Returns false without Forgebound. */
    static boolean give(Player p, int n) {
        if (tokens(1) == null) {
            return false;
        }
        while (n > 0) {
            ItemStack s = tokens(Math.min(64, n));
            n -= s.getAmount();
            p.getInventory().addItem(s).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        return true;
    }
}
