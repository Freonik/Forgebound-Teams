package cz.forgebound.teams;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One team: a leader, officers and members, a tag in front of their names, allies and stats. */
public final class Team {

    public enum Role {
        LEADER, OFFICER, MEMBER;

        public String label() {
            return switch (this) {
                case LEADER -> "Leader";
                case OFFICER -> "Officer";
                case MEMBER -> "Member";
            };
        }
    }

    final String id;
    String name;
    String tag;
    TextColor color;
    /** the team's own banner (set from a banner in the leader's hand), null = a banner in the team color */
    ItemStack banner;
    /** the team stash (Bounty Tokens only), null = empty */
    ItemStack[] stash;
    /** tokens the stash owes (didn't fit in, or Forgebound wasn't there to make them) - added when there's room */
    int owed;
    /** enemy team id -> how many tokens this team staked on that war */
    final Map<String, Integer> stakes = new LinkedHashMap<>();
    /** enemy team id -> when the war started (ms) */
    final Map<String, Long> warStart = new LinkedHashMap<>();
    /** points since the last weekly reward */
    long weekPoints;
    final Map<UUID, Role> members = new LinkedHashMap<>();
    final Map<UUID, String> names = new LinkedHashMap<>();
    final Set<String> allies = new LinkedHashSet<>();
    /** enemy team id -> our kills against them in this war */
    final Map<String, Integer> enemies = new LinkedHashMap<>();
    int kills;
    int deaths;
    long points;
    long created;

    Team(String id, String name, String tag, TextColor color) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String tag() {
        return tag;
    }

    public TextColor color() {
        return color;
    }

    public long points() {
        return points;
    }

    public UUID leader() {
        for (Map.Entry<UUID, Role> e : members.entrySet()) {
            if (e.getValue() == Role.LEADER) {
                return e.getKey();
            }
        }
        return members.isEmpty() ? null : members.keySet().iterator().next();
    }

    public Role role(UUID player) {
        return members.get(player);
    }

    /** Leader and officers can invite and kick (officers only plain members). */
    public boolean canManage(UUID player) {
        Role r = members.get(player);
        return r == Role.LEADER || r == Role.OFFICER;
    }

    public String scoreboardName() {
        return "fbt_" + id;
    }

    /** "1.5" style kills per death */
    public String kd() {
        return deaths == 0 ? String.valueOf(kills) : String.format(java.util.Locale.ROOT, "%.2f", kills / (double) deaths);
    }
}
