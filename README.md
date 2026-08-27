# PlayerTagger

A **client-side only** Fabric utility mod for Minecraft **1.21+** (1.21 – 1.21.11, Java 21). Tag
specific players and see them with Minecraft's own vanilla glowing outline —
purely on your own screen. Nothing is sent to the server, nothing touches
scoreboard teams, and no other player can ever tell you tagged them.

## Requirements

| Component      | Version                      |
|-----------------|------------------------------|
| Minecraft        | 1.21+ (1.21 – 1.21.11)       |
| Fabric Loader    | 0.18.1+                      |
| Fabric API       | 0.100.0+                     |
| Java             | 21                           |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21+.
2. Download **PlayerTagger** and [Fabric API](https://modrinth.com/mod/fabric-api).
3. Place both `.jar` files into your `.minecraft/mods` folder.
4. Launch the game.

## Building from Source

```bash
./gradlew build
```

The built jar will be located in `build/libs/playertagger-1.0.0.jar`.

## Commands

All commands are **client commands** (they work in singleplayer, LAN, and on
any multiplayer server, and never touch the network):

```
/playertagger toggle              Enable/disable the whole mod
/playertagger add <player>        Tag a player
/playertagger remove <player>     Untag a player
/playertagger list [page]         List tagged players, paginated
```

`add`/`remove` support tab completion (online players for `add`, currently
tagged players for `remove`).

## How the glow works

Vanilla Minecraft already has a "glowing outline" render pass — the same one
used by the Glowing status effect. It's driven by two small, stable engine
hooks: `Entity#isGlowing()` (should this entity get an outline?) and
`Entity#getTeamColorValue()` (what color?). PlayerTagger uses two focused
Mixins to answer both of those questions locally for tagged players, instead
of writing to the entity's real (server-synced) data or to a scoreboard
team:

- **`EntityGlowMixin`** — overrides `isGlowing()`'s return value to `true`
  for tagged players when the mod is enabled. This never writes to the
  entity's data tracker, so it's invisible over the network.
- **`EntityOutlineColorMixin`** — overrides `getTeamColorValue()`'s return
  value with the player's assigned color, without creating or touching any
  scoreboard team.

Both mixins are documented in-file (why they exist, exactly what they touch,
and why Fabric API alone doesn't expose an equivalent hook).

Because glow state is computed **fresh every frame** from
`PlayerTagManager` (a simple, cheap `HashMap` lookup) rather than cached,
every "must update instantly" scenario in the spec — toggling the mod,
adding/removing a tag, joining/leaving a world or server, changing
dimension, respawning — is handled automatically. There is no cache to
invalidate.

Since the vanilla outline pass ignores the depth buffer by design, it
naturally shows through walls and still outlines invisible entities — which
is exactly the vanilla Glowing effect's behavior. The two toggles in
`settings` (`showThroughWalls`, `showInvisiblePlayers`) gate this behavior;
see `GlowRenderer` for exactly how.

**Known engine-imposed limits** (not implementation gaps): a dead player has
no entity in the world to render, so nothing can be tagged-highlighted on
the respawn screen; and vanilla itself hides most spectator entities from
non-spectating clients, a policy this mod doesn't attempt to override.

## Architecture

```
net.playertagger
├── PlayerTaggerClient        Client mod entrypoint; wires everything together
├── command/
│   └── CommandRegistrar      Registers /playertagger and its subcommands
├── config/
│   ├── ConfigManager         Load/save/repair config/playertagger.json
│   └── PlayerTaggerConfig    Plain data model matching the JSON schema
├── manager/
│   └── PlayerTagManager      Single source of truth: tagged players + enabled state
├── render/
│   ├── GlowRenderer          Pure decision logic used by the mixins
│   └── GlowColorManager      Named color palette <-> ARGB resolution
├── storage/
│   └── TaggedPlayer          Immutable (uuid, username, color) record
├── util/
│   └── PlayerLookupUtil      Username -> UUID resolution, online player queries
└── mixin/
    ├── EntityGlowMixin           Forces the outline pass on for tagged players
    └── EntityOutlineColorMixin   Supplies each tagged player's custom color
```

`PlayerTagManager` is the only class that talks to `ConfigManager`; commands,
the renderer, and any future GUI all go through it. That single choke point
is what keeps persistence bugs from spreading across the codebase, and it's
where a friends list, groups, or per-player settings would hook in later
without touching the renderer at all.

## Config

`config/playertagger.json` (auto-created on first run):

```json
{
  "enabled": true,
  "players": [
    { "uuid": "...", "username": "Steve", "color": "yellow" }
  ],
  "settings": {
    "showThroughWalls": true,
    "showInvisiblePlayers": true
  }
}
```

- Created automatically if missing, loaded automatically on startup, and
  saved automatically after every mutation.
- If the file is corrupted or unparsable, it's backed up next to itself
  (`playertagger.json.corrupted-<timestamp>.bak`) and replaced with a fresh
  default rather than crashing the game.
- A single malformed entry inside `players` is skipped individually instead
  of discarding the whole list.
- Unrecognized top-level JSON fields are preserved across saves, so a config
  hand-edited (or edited by a future version of the mod) doesn't lose data.
