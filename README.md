# PlayerTagger

PlayerTagger is a client-side Fabric mod for Minecraft 1.21.4 that helps you recognize friends or teammates in a crowd. Tag players to give the visible parts of their model a colored outline with a white rim. Opaque blocks occlude the effect, and invisible players are excluded. Player tags are stored locally.

## Requirements and installation

- Minecraft 1.21.4 and Java 21
- Fabric Loader 0.18.1 or newer
- Fabric API for Minecraft 1.21.4 (tested with `0.119.2+1.21.4`)

Install Fabric Loader and place PlayerTagger and Fabric API in your `.minecraft/mods` folder. No server component is required.

## Commands

All commands are client-side and work in singleplayer and multiplayer:

```text
/playertagger toggle              Enable or disable outlines
/playertagger add <player>        Tag a player
/playertagger remove <player>     Remove a tag
/playertagger list [page]         List tagged players
```

The add and remove commands support tab completion. Tagged players receive colors from a palette; their UUIDs, names, and colors persist across sessions and servers.

## Visibility

Tagged players are outlined only while the mod is enabled and the player is not invisible. The glow is drawn in screen pixels and fills the visible silhouette as the player recedes. World depth prevents the glow and its white rim from drawing over foreground blocks. Vanilla name tags and glowing effects are unchanged.

## Configuration

`config/playertagger.json` stores `enabled` and the tagged players and their colors. Existing tags, assigned colors, and the enabled setting carry forward from version 1.0.0. The upgrade retires the `showThroughWalls` and `showInvisiblePlayers` settings. Unrecognized top-level fields are retained on save. Corrupt configuration files are backed up before defaults are created.

## Building

Run `./gradlew build` with Java 21. The jar is written to `build/libs/`.
