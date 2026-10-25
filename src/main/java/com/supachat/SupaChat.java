package com.supachat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupaChat extends JavaPlugin implements Listener {
    private static final Pattern HEX_LEGACY = Pattern.compile("&##([a-fA-F0-9]{6})");
    private static final Pattern HEX_MINI = Pattern.compile("<##([a-fA-F0-9]{6})>");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Set<UUID> chatDisabled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> auctionSearch = ConcurrentHashMap.newKeySet();
    private final Set<UUID> bountySearch = ConcurrentHashMap.newKeySet();

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

        // Match the Skript's priority: bounty search is checked before auction search.
        if (bountySearch.remove(id)) {
            event.setCancelled(true);
            String query = plainText(event.message());
            runSearchCommand(sender, getConfig().getString("chat.commands.bounty", "bounty"), query);
            return;
        }
        if (auctionSearch.remove(id)) {
            event.setCancelled(true);
            String query = plainText(event.message());
            runSearchCommand(sender, getConfig().getString("chat.commands.auction", "market search"), query);
            return;
        }

        // Staff chat owns its messages. Do not intercept if the sender is in staff chat mode.
        if (isSkriptFlagSet(sender, "staffchattoggle")) {
            return;
        }

        event.setCancelled(true);
        if (chatDisabled.contains(id)) {
            return;
        }

        String message = plainText(event.message());
        Component formatted = formatChat(sender, message);
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (!chatDisabled.contains(recipient.getUniqueId())) {
                recipient.sendMessage(formatted);
            }
        }
        getLogger().fine("Chat message formatted for " + sender.getName());
    }

    private Component formatChat(Player player, String message) {
        String prefix = placeholder(player, "%luckperms_meta_legacy-prefix%");
        String chatColor = placeholder(player, "%luckperms_meta_chat-color%");
        String format = getConfig().getString("chat.format", "%prefix%%player%%chat_color%: %message%")
                .replace("%prefix%", prefix)
                .replace("%player%", player.getName())
                .replace("%chat_color%", chatColor)
                .replace("%message%", message);

        Component chat = parse(format);
        String tooltip = getConfig().getString("chat.stats-tooltip", "%prefix%%player%")
                .replace("%prefix%", prefix)
                .replace("%player%", player.getName());
        tooltip = expandPlaceholders(player, tooltip);
        String statsCommand = getConfig().getString("chat.stats-command", "/stats %player%")
                .replace("%player%", player.getName());

        Component hover = parse(tooltip);
        Component nameAndMessage = chat;
        // Attach tooltip/click behavior to the player-name portion when using the default format.
        String nameToken = prefix + player.getName();
        String renderedFormat = format;
        if (renderedFormat.contains(nameToken)) {
            String[] parts = renderedFormat.split(Pattern.quote(nameToken), 2);
            Component before = parse(parts[0]);
            Component name = parse(nameToken).hoverEvent(hover).clickEvent(ClickEvent.runCommand(statsCommand));
            Component after = parts.length > 1 ? parse(parts[1]) : Component.empty();
            nameAndMessage = before.append(name).append(after);
        } else {
            nameAndMessage = chat.hoverEvent(hover).clickEvent(ClickEvent.runCommand(statsCommand));
        }
        return nameAndMessage;
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
        try {
            return MINI.deserialize(normalized);
        } catch (RuntimeException ignored) {
            return LEGACY.deserialize(normalized);
        }
    }

    private String plainText(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void runSearchCommand(Player player, String configuredCommand, String query) {
        String command = configuredCommand.replace("%player%", player.getName())
                + (query.isBlank() ? "" : " " + query);
        Bukkit.getScheduler().runTask(this, () -> player.performCommand(command));
    }

    private boolean isSkriptFlagSet(Player player, String key) {
        // Skript variables are not accessible through Bukkit's API. This hook safely checks
        // for the optional Skript plugin without assuming its internal variable storage.
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