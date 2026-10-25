# SupaChat

A Paper/Purpur chat-formatting plugin converted from the supplied Skript behavior.

## Features
- Custom chat formatting using PlaceholderAPI placeholders.
- Hover tooltip with balance, kills, playtime, deaths, and shards.
- Clicking the sender's name runs `/stats <player>`.
- Per-session chat visibility toggle.
- Auction and bounty chat-search modes.
- Configurable MiniMessage formatting and hex colors.

## Requirements
- Paper/Purpur 1.21.x
- Java 21
- PlaceholderAPI recommended
- LuckPerms and the relevant economy/statistics PlaceholderAPI expansions for the tooltip values

## Commands
- `/chat toggle` — toggle receiving normal chat.
- `/chat auction` — route the next chat message to `/market search <message>`.
- `/chat bounty` — route the next chat message to `/bounty <message>`.
- `/chat normal` — leave search mode.

## Build
Run `mvn package`. The output jar is `target/SupaChat.jar`.

## Important integration note
Skript's variables (including `{staffchattoggle::*}`, `{togglechat::*}`, `{searchauction::*}`, and `{searchbounty::*}`) are stored by Skript and are not automatically shared with a Java plugin. SupaChat currently provides its own `/chat` mode and chat-toggle state. If you want existing Skript commands to control the same state, they must be migrated or an explicit Skript integration must be added. Search mode is session-only and resets when the player leaves.
