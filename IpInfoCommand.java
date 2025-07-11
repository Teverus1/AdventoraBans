package org.teverus.adventoraBans.commands;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.util.IPUtils; // Используем IPUtils для валидации IP
import org.teverus.adventoraBans.util.PlayerUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class IpInfoCommand extends BaseCommand {

    public IpInfoCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.ipinfo", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            // Предполагаем, что ipinfo_usage уже есть или будет добавлен в messages.yml
            // Если нет, это вернется к дефолтному сообщению о неправильном использовании команды
            sender.sendMessage(plugin.getMessageManager().getMessage("prefix") + " " + plugin.getCommand("ipinfo").getUsage());
            return;
        }

        final String identifier = args[0];

        if (IPUtils.isValidIpAddress(identifier)) {
            getIpInfoByIp(sender, identifier);
        } else {
            PlayerUtil.getOfflinePlayerAsync(identifier)
                    .thenAccept(playerOptional -> {
                        if (playerOptional.isEmpty()) {
                            sender.sendMessage(plugin.getMessageManager().getMessage("player_not_found", Map.of("player", identifier)));
                            return;
                        }
                        final OfflinePlayer player = playerOptional.get();
                        getIpInfoByPlayer(sender, player);
                    })
                    .exceptionally(ex -> {
                        sender.sendMessage(plugin.getMessageManager().getMessage("error_command_execution", Map.of("error", "Player resolution error.")));
                        plugin.getLogger().log(Level.SEVERE, "Ошибка при разрешении игрока " + identifier + " для ipinfo: " + ex.getMessage(), ex);
                        return null;
                    });
        }
    }

    private void getIpInfoByPlayer(CommandSender sender, OfflinePlayer player) {
        final String playerNameForDisplay = (player.getName() != null && !player.getName().isEmpty()) ? player.getName() : player.getUniqueId().toString();

        CompletableFuture<Optional<String>> lastIpFuture = plugin.getDatabaseManager().getLastKnownIp(player.getUniqueId());

        lastIpFuture
                .thenAccept(lastIpOptional -> {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("target_identifier", playerNameForDisplay);

                    sender.sendMessage(plugin.getMessageManager().getMessage("ipinfo_header", placeholders));

                    if (lastIpOptional.isPresent()) {
                        placeholders.put("last_known_ip", IPUtils.normalizeIpAddress(lastIpOptional.get()));
                        sender.sendMessage(plugin.getMessageManager().getMessage("ipinfo_last_ip", placeholders));
                    } else {
                        // Используем сообщение из messages.yml
                        sender.sendMessage(plugin.getMessageManager().getMessage("ipinfo_no_last_ip_found"));
                    }
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при получении IP-информации для игрока " + playerNameForDisplay + ": " + ex.getMessage(), ex);
                    return null;
                });
    }

    private void getIpInfoByIp(CommandSender sender, String ipAddress) {
        final String normalizedIpAddress = IPUtils.normalizeIpAddress(ipAddress);

        CompletableFuture<Optional<UUID>> lastUuidFuture = plugin.getDatabaseManager().getLastKnownUuid(normalizedIpAddress);

        lastUuidFuture
                .thenCompose(lastUuidOptional -> {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("target_identifier", normalizedIpAddress);

                    sender.sendMessage(plugin.getMessageManager().getMessage("ipinfo_header", placeholders));

                    if (lastUuidOptional.isPresent()) {
                        UUID lastKnownUuid = lastUuidOptional.get();
                        // Асинхронно получаем OfflinePlayer по UUID
                        return PlayerUtil.getOfflinePlayerAsync(lastKnownUuid)
                                .thenAccept(offlinePlayerOptional -> {
                                    String lastKnownPlayerNameForDisplay;
                                    if (offlinePlayerOptional.isPresent() && offlinePlayerOptional.get().getName() != null && !offlinePlayerOptional.get().getName().isEmpty()) {
                                        lastKnownPlayerNameForDisplay = offlinePlayerOptional.get().getName();
                                    } else {
                                        // Если OfflinePlayer.getName() null/пусто, используем UUID.
                                        lastKnownPlayerNameForDisplay = lastKnownUuid.toString();
                                    }
                                    // Используем сообщение из messages.yml
                                    Map<String, String> playerPlaceholders = Map.of("player_name", lastKnownPlayerNameForDisplay);
                                    sender.sendMessage(plugin.getMessageManager().getMessage("ipinfo_last_known_player", playerPlaceholders));
                                });
                    } else {
                        // Используем сообщение из messages.yml
                        sender.sendMessage(plugin.getMessageManager().getMessage("ipinfo_no_last_known_player"));
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при получении IP-информации для IP " + normalizedIpAddress + ": " + ex.getMessage(), ex);
                    return null;
                });
    }
}