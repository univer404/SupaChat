package com.supachat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupaChat extends JavaPlugin implements Listener {
    private static final Pattern HEX_LEGACY = Pattern.compile("&##([a-fA-F0-9]{6})");
    private static final Pattern HEX_MINI = Pattern.compile("<##([a-fA-F0-9]{6})>");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§]([0-9a-fk-or])");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Set<UUID> chatDisabled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> auctionSearch = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> bountySearch = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SupaChat enabled.");
    }

    @Override
    public void onDisable() {
        chatDisabled.clear();
        auctionSearch.clear();
        bountySearch.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        UUID id = sender.getUniqueId();

        // Search modes consume the next message, just like the original Skript.
        if (bountySearch.remove(id)) {
            event.setCancelled(true);
            String query = plainText(event.message());
            Bukkit.getScheduler().runTask(this, () ->
                    runSearchCommand(sender, getConfig().getString("chat.commands.bounty", "bounty"), query));
            return;
        }
        if (auctionSearch.remove(id)) {
            event.setCancelled(true);
            String query = plainText(event.message());
            Bukkit.getScheduler().runTask(this, () ->
                    runSearchCommand(sender, getConfig().getString("chat.commands.auction", "market search"), query));
            return;
        }

        // Allow staff-chat plugins/Skript handlers to process their own messages.
        if (isSkriptFlagSet(sender, "staffchattoggle")) {
            return;
        }

        // Always cancel custom chat immediately, then perform PlaceholderAPI and Bukkit
        // operations on the main server thread (AsyncChatEvent can run asynchronously).
        event.setCancelled(true);
        if (chatDisabled.contains(id)) {
            return;
        }

        String message = plainText(event.message());
        Bukkit.getScheduler().runTask(this, () -> {
            if (!sender.isOnline() || chatDisabled.contains(id)) {
                return;
            }
            Component formatted = formatChat(sender, message);
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                if (!chatDisabled.contains(recipient.getUniqueId())) {
                    recipient.sendMessage(formatted);
                }
            }
        });
    }

    private Component formatChat(Player player, String message) {
        String prefix = placeholder(player, "%luckperms_meta_legacy-prefix%");
        String chatColor = placeholder(player, "%luckperms_meta_chat-color%");
        String format = getConfig().getString("chat.format", "%prefix%%player%%chat_color%: %message%")
                .replace("%prefix%", prefix)
                .replace("%player%", player.getName())
                .replace("%chat_color%", chatColor)
                .replace("%message%", MINI.escapeTags(message));

        String tooltipText = getConfig().getString("chat.stats-tooltip", "%prefix%%player%")
                .replace("%prefix%", prefix)
                .replace("%player%", player.getName());
        tooltipText = expandPlaceholders(player, tooltipText);
        Component hover = parse(tooltipText);

        String statsCommand = getConfig().getString("chat.stats-command", "/stats %player%")
                .replace("%player%", player.getName());

        // Build the default format as components so prefixes containing legacy colors or
        // MiniMessage tags do not break the clickable-name detection.
        String defaultFormat = "%prefix%%player%%chat_color%: %message%";
        if (getConfig().getString("chat.format", defaultFormat).equals(defaultFormat)) {
            Component name = parse(prefix).append(Component.text(player.getName())
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                    .hoverEvent(HoverEvent.showText(hover))
                    .clickEvent(ClickEvent.runCommand(statsCommand)));
            Component body = parse(chatColor + ": " + MINI.escapeTags(message));
            return name.append(body).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }

        // Custom formats remain configurable; use a safe marker to attach click/hover
        // behavior even when the prefix includes formatting codes.
        String marker = "SUPACHAT_PLAYER_NAME_MARKER";
        String custom = getConfig().getString("chat.format", defaultFormat)
                .replace("%prefix%", prefix)
                .replace("%player%", marker)
                .replace("%chat_color%", chatColor)
                .replace("%message%", message);
        Component result = Component.empty();
        String[] pieces = custom.split(Pattern.quote(marker), 2);
        if (pieces.length == 2) {
            Component clickableName = Component.text(player.getName())
                    .hoverEvent(HoverEvent.showText(hover))
                    .clickEvent(ClickEvent.runCommand(statsCommand));
            result = parse(pieces[0]).append(clickableName).append(parse(pieces[1]));
        } else {
            result = parse(custom).hoverEvent(HoverEvent.showText(hover))
                    .clickEvent(ClickEvent.runCommand(statsCommand));
        }
        return result.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private String placeholder(Player player, String text) {
        return expandPlaceholders(player, text);
    }

    private String expandPlaceholders(Player player, String text) {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            } catch (RuntimeException exception) {
                getLogger().warning("A PlaceholderAPI expansion failed: " + exception.getMessage());
            }
        }
        return text;
    }

    private Component parse(String input) {
        String normalized = HEX_LEGACY.matcher(input).replaceAll("<#$1>");
        normalized = HEX_MINI.matcher(normalized).replaceAll("<#$1>");
        // Convert traditional & / § color codes into MiniMessage tags. This allows
        // LuckPerms legacy prefixes and MiniMessage config text to render together.
        Matcher matcher = LEGACY_CODE.matcher(normalized);
        StringBuffer converted = new StringBuffer();
        while (matcher.find()) {
            String tag = legacyCodeToTag(matcher.group(1).toLowerCase());
            matcher.appendReplacement(converted, Matcher.quoteReplacement(tag));
        }
        matcher.appendTail(converted);
        try {
            return MINI.deserialize(converted.toString())
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        } catch (RuntimeException ignored) {
            return LEGACY.deserialize(normalized)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }
    }

    private String legacyCodeToTag(String code) {
        return switch (code) {
            case "0" -> "<black>";
            case "1" -> "<dark_blue>";
            case "2" -> "<dark_green>";
            case "3" -> "<dark_aqua>";
            case "4" -> "<dark_red>";
            case "5" -> "<dark_purple>";
            case "6" -> "<gold>";
            case "7" -> "<gray>";
            case "8" -> "<dark_gray>";
            case "9" -> "<blue>";
            case "a" -> "<green>";
            case "b" -> "<aqua>";
            case "c" -> "<red>";
            case "d" -> "<light_purple>";
            case "e" -> "<yellow>";
            case "f" -> "<white>";
            case "k" -> "<obfuscated>";
            case "l" -> "<bold>";
            case "m" -> "<strikethrough>";
            case "n" -> "<underlined>";
            case "o" -> "<italic>";
            case "r" -> "<reset>";
            default -> "";
        };
    }

    private String plainText(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void runSearchCommand(Player player, String configuredCommand, String query) {
        if (!player.isOnline()) {
            return;
        }
        String command = configuredCommand.replace("%player%", player.getName())
                + (query.isBlank() ? "" : " " + query);
        player.performCommand(command);
    }

    private boolean isSkriptFlagSet(Player player, String key) {
        // Skript variables are not part of Bukkit's public API. Until an explicit
        // Skript integration is installed, let Skript/staff-chat plugins handle this.
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        if (args.length == 0) {
            sendConfigured(player, "chat.unknown-mode");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "toggle" -> {
                if (chatDisabled.remove(player.getUniqueId())) {
                    sendConfigured(player, "chat.toggle-on");
                } else {
                    chatDisabled.add(player.getUniqueId());
                    sendConfigured(player, "chat.toggle-off");
                }
            }
            case "auction" -> {
                auctionSearch.add(player.getUniqueId());
                bountySearch.remove(player.getUniqueId());
                sendConfigured(player, "chat.auction-mode");
            }
            case "bounty" -> {
                bountySearch.add(player.getUniqueId());
                auctionSearch.remove(player.getUniqueId());
                sendConfigured(player, "chat.bounty-mode");
            }
            case "normal" -> {
                auctionSearch.remove(player.getUniqueId());
                bountySearch.remove(player.getUniqueId());
                sendConfigured(player, "chat.normal-mode");
            }
            default -> sendConfigured(player, "chat.unknown-mode");
        }
        return true;
    }

    private void sendConfigured(Player player, String path) {
        player.sendMessage(parse(getConfig().getString(path, "<gray>ᴅᴏɴᴇ.")));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        chatDisabled.remove(id);
        auctionSearch.remove(id);
        bountySearch.remove(id);
    }
}
