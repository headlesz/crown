# Crown

A NeoForge server mod that runs the governance and retention systems for the Goku Black v2 SMP:
a rotating elected monarch, ceremonial powers that expire with the term, a sealed End that opens
once as a season finale, per-term resource packs, and an append-only prestige ledger.

**Minecraft 1.21.1 · NeoForge 21.1.248 · Java 21 · server-side only**

Crown registers no items, blocks, entities or custom packets, and ships no client classes.
Everything players see is built from vanilla chat components, screen titles, boss bars, books and
command trees. That means Crown can be hotfixed and redeployed without anyone reinstalling the
modpack.

---

## The idea

The server elects a monarch roughly every nine days, on the sole criterion of *who did the most
cool shit*. They hold the crown for one term, they cannot immediately succeed themselves, and
everything they declare dies when their term ends.

The power is real but entirely social. A monarch can name the term, publish decrees, schedule
events, grant titles, commission builds, dress the server in a resource pack, and call for the
season's finale. A monarch cannot change a gamerule, alter the difficulty, edit the whitelist,
kick anyone, move the world border, or touch a single block another player placed. That asymmetry
is the whole design — see [What Crown deliberately does not do](#what-crown-deliberately-does-not-do).

---

## Systems

### Term lifecycle

Three states, and exactly one code path (`TermManager.transition`) that moves between them.

| State | Meaning |
|---|---|
| `REIGN` | A monarch holds the term (default 9 days) |
| `ELECTION` | Voting is open. The sitting monarch keeps the role, but their powers freeze — no new decrees, events, titles or commissions. What they already declared stands until the term ends. |
| `INTERREGNUM` | No monarch: a failed election, a resignation, or a removal |

The succession election opens automatically 48 hours before the term ends and closes as the term
expires, so the handover is a single atomic transition rather than a gap.

That transition snapshots the entire state first and restores it if any step throws, so a
half-applied handover can never be persisted. It archives the outgoing term, revokes the outgoing
monarch, installs the incoming one, resets per-term state (pack, genre, term name, commissions),
then persists, re-exports and announces.

### Elections

Ballots are cast in-game and are **secret**. Announcements publish counts and nothing else — never
who voted for whom — and the JSON export stores only hashed voter IDs, so it survives being
screen-shared.

- **Who may vote:** anyone who logged in during the current term. (Config can widen this to every
  whitelisted player.)
- **Who may stand:** anyone on the roster except the sitting monarch. No back-to-back terms.
- **Self-votes:** disabled by default. The vote is a judgement of *other* people's work.
- **Quorum:** `ceil(0.5 × electorate)`, minimum 1. At 4–8 players that's 2–4 ballots.
- **Recasting:** allowed until the election closes. Last write wins; a voter never holds two ballots.

Ties resolve through four deterministic stages, in order:

1. Fewest career terms wins.
2. Still tied → the candidate whose last term is older wins.
3. Still tied (two people who have never reigned) → a 24-hour runoff between exactly those candidates.
4. Runoff still tied → a seeded draw, seeded on the term index and logged.

Stage 4 is seeded rather than random precisely so the result is auditable and **cannot be re-rolled**
by closing the election again.

If a tally fails for want of quorum, voting extends 24 hours, up to twice; after that the server
falls into interregnum and a fresh election opens immediately.

### The End gate and the season finale

The End is sealed for the entire season and opens exactly once, as the finale.

Crown gates *entry* only. It never edits `allow-end` or the level settings, so the window opens and
closes with no restart, and the dimension stays generated throughout. Both surfaces are covered:
walking into a live portal, and inserting an eye of ender into a portal frame. Non-player entities
are exempt, so thrown items and projectiles behave normally.

Opening it takes two keys:

1. The monarch proposes: `/crown endraid request <start-time> <duration-hours>`
2. An operator confirms: `/crown admin endraid confirm` (or `deny <reason>`)

When the window opens, everyone gets a title and a boss bar counting it down. When it closes,
entry is blocked again — but **nobody is teleported**. Players still inside walk out through the
return portal on their own. Crown also never deletes End loot, despawns shulkers or modifies
drops; post-raid abundance is accepted, because the season is ending anyway.

### Prestige ledger

An append-only log of reigns served, titles granted, and commissions issued and completed.
A commission is an open bounty — the monarch declares the build, anyone may deliver it, and the
delivery is credited either to one named player (it joins their `/laurels`) or to `everyone`
(it counts in the term's `/halloffame` digest instead).

Crown **never computes, displays or compares a score**. There is no leaderboard and no ranking,
because a ranking would reintroduce exactly the algorithmic-contribution failure mode the design
forbids. The ledger is institutional memory — a museum plaque, queryable in-game to back the
physical museum the group builds by hand.

Corrections are appended as admin notes. History is never rewritten.

### Per-term resource pack

Each monarch may dress the server for their term. Crown owns exactly one pack slot and drives it
with runtime push/pop packets — it never writes `server.properties`, so no restart is involved.

The URL is validated before it is accepted: an HTTP HEAD for a 2xx, a size check against the
configured cap, and a SHA-1 format check. Rejections come back with an actionable reason.

The pack is **never pushed as required**. Anyone can decline it and keep playing. A troll pack is
an acceptable outcome — it lasts at most one term — but a player locked out by a 100 MB download
on a bad connection is not.

### Announcements

Every event fans out to two sinks: in-game chat (plus a screen title for high-salience events) and
a Discord webhook.

It's a webhook, not a bot. Discord Integration already runs the bot and owns the chat bridge;
Crown only ever emits, and never ingests. Webhook calls are off-thread with a 5-second timeout and
no retries, and failures are logged at most once per event type per hour, so a dead webhook can
never spam the console or stall a tick. Leaving the URL empty disables the sink silently.

Eighteen event types are covered: term started/ended, election opened/extended/closed, interregnum,
decree, event scheduled/starting/cancelled, title granted, commission issued/completed, raid
requested/confirmed/opened/closed, and pack changed.

---

## Commands

### Anyone

| Command | Effect |
|---|---|
| `/crown` | Current term, monarch, standing decrees, time remaining |
| `/vote` | The ballot: every candidate as a clickable button |
| `/vote <player>` | Cast or change your ballot. Confirmed privately |
| `/vote status` | Ballots cast, quorum state, time remaining |
| `/laurels [player]` | A player's ledger history, most recent first |
| `/halloffame` | Per-term digest: name, monarch, decrees, events, commissions |

### Monarch

All of these expire at term end, and all freeze while the succession vote is open.

| Command | Effect |
|---|---|
| `/crown name <term-name>` | Name the term, e.g. "The Bridge Term" |
| `/crown genre <text>` | Set the term's mode label |
| `/crown decree <text>` | Publish a decree (max 3 standing). Text only — Crown enforces nothing |
| `/crown event create <name> <when> [description]` | Schedule a community event, with a countdown boss bar |
| `/crown event cancel <name>` | Call it off |
| `/crown title grant <player> <title>` | Grant a cosmetic title (max 3 per term) |
| `/crown commission <text>` | Publicly commission a build — an open bounty anyone may complete (max 3 standing) |
| `/crown commission complete <player>\|everyone [number]` | Mark it delivered — credited to that player, or to the whole group. `[number]` is the commission's position from `/crown`, optional while only one stands |
| `/crown pack set <url> <sha1>` | Set the term's resource pack |
| `/crown pack clear` | Revert to the default |
| `/crown endraid request <start-time> <hours>` | Propose the season finale |
| `/crown resign` | Abdicate (type twice within 30s) |

Times accept `2026-08-20T19:00` (server timezone) or `2026-08-20T19:00:00Z`.

### Operators (permission level 3+)

| Command | Effect |
|---|---|
| `/crown admin status` | Full state dump |
| `/crown admin remove-monarch <reason>` | Vacate the throne |
| `/crown admin election open\|close\|extend` | Drive the election manually |
| `/crown admin endraid confirm\|deny <reason>\|close-now` | The operator half of the two-key gate |
| `/crown admin pack force-clear` | Emergency: a pack is breaking clients |
| `/crown admin ledger adjust <text>` | Append a correction |
| `/crown admin reload` | Config reload confirmation |

---

## Configuration

`config/crown-server.toml`, generated on first boot. Defaults match the design; the ones worth
knowing:

```toml
[term]
term_length_days = 9
election_window_hours = 48
max_election_extensions = 2
monarch_afk_days = 5          # warns only, never auto-removes

[election]
quorum_fraction = 0.5
allow_self_vote = false

[powers]
max_active_decrees = 3
max_open_commissions = 3
titles_per_term = 3

[endgate]
gated_dimension = "minecraft:the_end"   # restart-only
max_raids_per_season = 1

[pack]
pack_forced = false           # leave this alone; see below
pack_max_bytes = 104857600

[integration]
luckperms_group = "monarch"

[discord]
webhook_url = ""              # empty disables the Discord sink silently
```

**Do not set `pack_forced = true`.** It exists only because the packet has the field. Turning it on
kicks anyone who declines the term's pack, which trades a player's ability to join for a cosmetic.

`gated_dimension` is restart-only. Everything else is picked up on config reload.

### Name display

Crown renders the crown's regalia natively, with no permissions or chat mod involved: the sitting
monarch's name carries a gold, bold `[Monarch]` prefix, and a titled player wears their most
recently granted title as a bracketed suffix (`Name [Dragonslayer]`) — in chat, the tab list,
death messages, and anywhere else the display name shows. Both update live at coronation,
deposition and title grant; no relog needed.

### LuckPerms (optional)

If LuckPerms is installed, Crown grants the configured group to the monarch as a **temporary node**
expiring an hour after the term ends, so a crash or a missed transition can never leave a stale
monarch permanently elevated. This is purely for permissions other mods might key off the group —
name display is handled natively by Crown, above.

Without LuckPerms, Crown falls back to checking the term record directly and skips group
assignment. Every authorization decision goes through one facade (`CrownPermissions`), so the
fallback is a single code path rather than a branch scattered across the command tree.
`/crown admin status` reports which mode is active.

---

## What Crown deliberately does not do

These are constraints, not gaps. Read them before adding anything.

- **No economy.** No currency item, no balances, no trading API. Nothing Crown issues is tradeable,
  droppable or stockpileable. If a proposed feature smells like money, it's out.
- **No hard power.** The monarch role never gates gamerules, difficulty, the whitelist, bans, the
  world border, or another player's blocks. The command tree is built so hard power is
  *unexpressable* — there is no `/crown gamerule`, no kick, no ban. The only world-state change in
  the entire mod is the End gate, and that requires an operator's confirmation. **A pull request
  adding hard power is a design violation, not a feature.**
- **No contribution metrics.** Crown never counts blocks placed, playtime, or any proxy for
  contribution. Election input is human votes and nothing else.
- **No chat bridge.** Discord Integration owns Minecraft⇄Discord chat. Crown only emits.
- **No teleportation.** No homes, warps, RTP or `/tp` wrappers. Distance is a design material on
  this server.

---

## Building

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

The jar lands in `build/libs/crown-1.0.0.jar`. Drop it in the server's `mods/` folder — server
only, no client install.

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew test
```

Tests cover the tally: quorum boundaries at 4/6/8 voters, recast-replaces, ineligible-candidate
filtering, all four tie-break stages, and 1000-run determinism checks proving the seeded draw and
the count are reproducible.

---

## Implementation notes

State lives in a single `SavedData` attachment on the overworld, plus a human-readable
`world/crown/export.json` regenerated on every transition for debugging and for the group's
records. The export is write-only — Crown never reads it back, so hand-editing it changes nothing.

Deadlines are wall-clock epoch millis, not game ticks, because terms are measured in real days and
the server restarts often. One coarse scheduler checks them once a second. On startup it
reconciles anything that came due while the server was down, in a defined order. If the server was
offline across an entire voting window, the election reopens rather than tallying a vote nobody
could have cast, and a backwards clock jump can never re-fire a transition that already happened.

Two deliberate departures from the original spec:

- **Persistence is hand-written NBT, not Codec-to-NBT.** A sealed ledger hierarchy plus nested
  per-term lists and UUID-keyed maps is cheap by hand and awkward as Codecs. `CrownState.dataVersion`
  and `CrownState.migrate` still provide the explicit, versioned migration path; add a case to
  `migrate` whenever the on-disk shape changes.
- **`clientSideOnly` is not set** in `neoforge.mods.toml` — that key doesn't exist in the NeoForge
  21.1 metadata format, it arrived later. `displayTest = "IGNORE_SERVER_VERSION"` is set as
  specified, and since Crown registers nothing networked, clients are never version-blocked.

### Still open

- May the outgoing monarch vote for their successor? Currently yes — only *standing* is barred.
- Announcement cadence: currently open, T-24h, close.
- Discord scheduled events: currently no, since creating them needs a bot token and would break the
  webhook-only constraint.
