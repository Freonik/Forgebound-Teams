# Forgebound Teams

Small teams for **Paper 1.21.11** (Java 21): a tag and a color in front of the names, team chat, allies,
no friendly fire and a team leaderboard. Works on its own, and works together with
[Forgebound](https://github.com/Freonik/Forgebound): its abilities, bounties, trackers and quests spare
teammates and allies.

## Installation

1. Download `ForgeboundTeams.jar` from the [latest release](../../releases/latest).
2. Put it into `plugins/` and restart. New versions are downloaded on their own (used after the next restart).

## Teams

**`/team` opens a menu where everything can be clicked**: create a team (name and tag are typed into a window),
answer invites, invite online players, manage members (officer, leader, kick), team chat on/off, allies and wars
(offer, accept, end), the team color and tag, all teams and the leaderboard. The commands below do the same.

- Up to **3 players** per team (`max-members`), one **leader**, **officers** who can invite and kick members.
- The **tag** (2-4 letters) and the team **color** show in front of the members' names: in the tab list, in chat
  and above their heads.
- Teammates and allies **can't hurt each other**: hits, arrows, tamed wolves, harmful splash potions.
- Each team can have **one ally** (`max-allies`). Leaders offer an alliance with `/team ally <team>`, the other
  leader accepts with the same command.
  When an alliance ends, kills between the two teams pay **no bounty for 15 minutes** (they can fight)
  and they can't become allies again for **24 hours** (`alliance` in the config).
- **Colors:** 13 basic colors, 18 more (pink, orange, mint, teal, lavender...) or any `#rrggbb`. The tag and chat
  show the exact color, the name above the head the closest of the 16 basic ones.
- **Team banner:** make any banner at a loom, hold it and run `/team banner` (or click it in the settings). It shows
  in the menus and lists. `/team banner reset` goes back to the plain one.
- **Team stash:** `/team stash` (or the ender chest in the menu) is a chest the whole team shares - only
  **Bounty Tokens** go in. Everyone can put in and take out. When the last member leaves or the leader disbands,
  the tokens go to them.
- **Together:** members within 16 blocks of each other get **Regeneration I** (`together` in the config).

## Team bounty

The bounties of the members (Forgebound) add up to the **team bounty** (in `/team top`, `/team info` and the menus).
Whoever kills a member also gets **10 % of the bounties of the rest of the team** in tokens (max 32,
`team-bounty`), on top of the victim's own bounty.

## Weekly rewards

Every **Sunday at 20:00** (`weekly-rewards`) the three teams with the most points **that week** get Bounty Tokens
into their stash: **30 / 20 / 10**. The weekly points start from 0 again; the leaderboard points stay.

## Wars

A leader can **declare war** on another team with `/team enemy <team>` - on both sides at once, the whole server
hears it. Kills between teams at war give **+3 extra points** and both teams see the score after each kill
(`/team info` too). Peace needs both leaders: `/team peace <team>`, the other leader accepts with the same
command. After peace the same two teams can't go to war again for 24 hours. Up to 3 wars at a time; allies
have to break the alliance before they can fight.

**Stakes:** `/team enemy <team> <tokens>` (or the menu asks) - both teams put that many Bounty Tokens from their
stash into the pot (the other side matches it with what its stash has). When the war ends, the team with **more
kills takes the whole pot** into its stash; a draw gives the stakes back, a team that disbands loses. A war with a
stake ends by itself after **48 hours** (`war-stakes`).

## Team chat

| How | Who reads it |
|---|---|
| `!message` or `/tc <message>` | your team |
| `@message` or `/ac <message>` | your team and your allies |
| `/tc` | switches your chat to the team (then `!message` goes to everyone) |

Team chat goes through the same chat moderation as everything else (Forgebound Guard).

## Leaderboard

`/team top` ranks the teams by points:

| Points | For |
|---|---|
| 3 | killing a player who isn't a teammate or an ally (the same victim again after 30 minutes) |
| +3 | on top of that if your teams are at war |
| 1 / 3 | a new advancement / one of Forgebound's own advancements |
| 1 per legend | every 30 minutes, for each Forgebound legend an online member carries (max 3 per player) |
| 5 - 20 | killing a boss: the Elder Guardian, the Wither, the Warden, the Ender Dragon, Forgebound's Forge Colossus |

`/team info [team]` shows the members, points, kills, deaths and K/D.

## Commands

| Command | Who | Description |
|---|---|---|
| `/team` | everyone | the team menu |
| `/team create <name> <tag>` | everyone | found a team |
| `/team invite <player>` | leader, officers | invite a player (valid for 5 minutes) |
| `/team accept [team]` / `/team deny <team>` | everyone | answer an invite (or click it in chat) |
| `/team leave` | members | leave the team (the leader hands it over first, or disbands) |
| `/team kick <player>` | leader, officers | kick a member (officers only plain members) |
| `/team promote` / `demote <player>` | leader | make a member an officer, or back |
| `/team leader <player>` | leader | hand the team over |
| `/team disband` | leader | disband the team (asks first) |
| `/team color <color\|#rrggbb>` / `/team tag <tag>` | leader | change the color or the tag |
| `/team banner [reset]` | leader | the banner in your hand becomes the team banner |
| `/team stash` | members | the shared chest for Bounty Tokens |
| `/team ally <team>` / `/team unally <team>` | leader | offer or accept an alliance / end it |
| `/team enemy <team> [stake]` / `/team peace <team>` | leader | declare war (with a stake) / offer or accept peace |
| `/team info [team]`, `/team list`, `/team top` | everyone | team info, all teams, the leaderboard |
| `/tc [message]`, `/ac <message>` | members | team chat, team + allies chat |
| `/team admin disband <team>` | `teams.admin` | disband any team |
| `/team admin reload`, `/team admin update` | `teams.admin` | reload the config, check for a new version |

Alias: `/clan`.

## Configuration

During Forgebound's big events the teams are **corrupted**: teammates and allies can hurt and kill each other until the
event ends (`corrupt-during-big-events`).

Everything is in `plugins/ForgeboundTeams/config.yml`: team size, allies, friendly fire, blocked words for names and
tags, the chat prefixes, the points and auto-update.

## License

All rights reserved. See [LICENSE](LICENSE).
