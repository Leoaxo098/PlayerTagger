# PlayerTagger

PlayerTagger is a client-side Fabric mod that helps you recognize friends or teammates in a crowd. Tag players to give the visible parts of their model a colored outline with a white rim. Opaque blocks occlude the effect, and invisible players are excluded. Player tags are stored locally.

## Requirements and installation

- Minecraft 1.21.6, 1.21.7 or 1.21.8 and Java 21
- Fabric Loader 0.18.1 or newer
- Fabric API and the PlayerTagger JAR built for your exact Minecraft version

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

Run `./gradlew build` with Java 21 to build for Minecraft 1.21.6. Set
`-Pminecraft_version=1.21.7` or `-Pminecraft_version=1.21.8` to build for
the other targets. The JARs in `build/libs/` end in the target Minecraft version.
