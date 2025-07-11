package org.teverus.adventoraBans.commands;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;
import org.teverus.adventoraBans.util.IPUtils;
import org.teverus.adventoraBans.util.PlayerUtil;
import org.teverus.adventoraBans.util.TimeUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.TimeZone;

public class IpTempBanCommand extends BaseCommand {

    public IpTempBanCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.iptempban", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 3) { // ip/player, duration, reason
            // Использование сообщения из messages.yml для usage
            sender.sendMessage(plugin.getMessageManager().getMessage("iptempban_usage"));
            return;
        }

        final String targetIdentifier = args[0];
        final String durationString = args[1];
        final String reason = getReason(args, 2);
        final UUID moderatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        final String moderatorName = (sender instanceof Player) ? sender.getName() : "CONSOLE";

        final long durationMillis = TimeUtil.parseDuration(durationString);
        if (durationMillis <= 0) { // Для временного бана durationMillis должен быть > 0
            sender.sendMessage(plugin.getMessageManager().getMessage("duration_format_error"));
            return;
        }

        // Определяем, является ли идентификатор IP-адресом или ником игрока
        if (IPUtils.isValidIpAddress(targetIdentifier)) {
            // Это IP-адрес
            processIpTempBan(sender, targetIdentifier, moderatorUuid, moderatorName, reason, durationMillis);
        } else {
            // Это ник игрока, пытаемся получить его последний IP
            PlayerUtil.getOfflinePlayerAsync(targetIdentifier)
                    .thenCompose(targetOptional -> {
                        if (targetOptional.isEmpty()) {
                            sender.sendMessage(plugin.getMessageManager().getMessage("player_not_found", Map.of("player", targetIdentifier)));
                            return CompletableFuture.completedFuture(Optional.empty());
                        }
                        final OfflinePlayer target = targetOptional.get();

                        // Проверяем, не банит ли модератор самого себя
                        if (sender instanceof Player && target.getUniqueId().equals(((Player) sender).getUniqueId())) {
                            sender.sendMessage(plugin.getMessageManager().getMessage("player_self_punish"));
                            return CompletableFuture.completedFuture(Optional.empty());
                        }

                        // Получаем последний известный IP игрока
                        return plugin.getDatabaseManager().getLastKnownIp(target.getUniqueId())
                                .thenApply(ipOptional -> {
                                    if (ipOptional.isEmpty()) {
                                        final String playerNameToDisplay;
                                        if (target.getName() != null && !target.getName().isEmpty()) {
                                            playerNameToDisplay = target.getName();
                                        } else {
                                            playerNameToDisplay = targetIdentifier;
                                        }
                                        sender.sendMessage(plugin.getMessageManager().getMessage("player_ip_not_found_for_ipunban", Map.of("player_name", playerNameToDisplay)));
                                        return Optional.empty();
                                    }
                                    return ipOptional;
                                });
                    })
                    .thenAccept(ipOptional -> {
                        if (ipOptional.isPresent()) {
                            String ipToBan = (String) ipOptional.get();
                            processIpTempBan(sender, ipToBan, moderatorUuid, moderatorName, reason, durationMillis);
                        }
                    })
                    .exceptionally(ex -> {
                        sender.sendMessage(plugin.getMessageManager().getMessage("error_command_execution", Map.of("error", "Player/IP resolution error.")));
                        plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при разрешении игрока/IP для временного IP-бана " + targetIdentifier + ": " + ex.getMessage()), ex);
                        return null;
                    });
        }
    }

    private void processIpTempBan(CommandSender sender, String ipToBan, UUID moderatorUuid, String moderatorName, String reason, long durationMillis) {
        final String normalizedIpToBan = IPUtils.normalizeIpAddress(ipToBan);

// Проверяем на наличие любого активного IP-бана (перманентного или временного).
        // Метод getActivePunishment(String ipAddress, PunishmentType type) в AbstractDatabaseManager
        // теперь сам проверяет оба типа (IP_BAN и TEMP_IP_BAN) если ему передать любой из них.
        // Поэтому достаточно одного вызова, например, с PunishmentType.IP_BAN.
        CompletableFuture<Optional<BanRecord>> existingBanFuture = plugin.getDatabaseManager().getActivePunishment(normalizedIpToBan, PunishmentType.IP_BAN);

        existingBanFuture
                .thenAccept(existingBanOptional -> {
                    if (existingBanOptional.isPresent()) {
                        Map<String, String> placeholders = Map.of("ip", normalizedIpToBan);
                        sender.sendMessage(plugin.getMessageManager().getMessage("player_already_ip_banned", placeholders));
                        return;
                    }

                    final long banTime = System.currentTimeMillis();
                    final long expireTime = banTime + durationMillis;

                    BanRecord newIpTempBan = new BanRecord(
                            null, // UUID не нужен для IP бана
                            null, // Имя не нужно для IP бана
                            normalizedIpToBan,
                            moderatorUuid,
                            moderatorName,
                            PunishmentType.TEMP_IP_BAN, // Указываем TEMP_IP_BAN
                            reason,
                            banTime,
                            expireTime,
                            true
                    );

                    plugin.getDatabaseManager().addPunishment(newIpTempBan)
                            .thenRun(() -> {
                                Map<String, String> placeholders = new HashMap<>();
                                placeholders.put("ip", normalizedIpToBan);
                                placeholders.put("moderator_name", moderatorName);
                                placeholders.put("reason", reason);

                                String formattedDuration = TimeUtil.formatDuration(durationMillis);
                                SimpleDateFormat dateFormat = new SimpleDateFormat(plugin.getMessageManager().getMessage("date_format"));
                                dateFormat.setTimeZone(TimeZone.getDefault());
                                String formattedExpireTime = dateFormat.format(new Date(expireTime));

                                placeholders.put("duration", formattedDuration);
                                placeholders.put("expire_date", formattedExpireTime);
                                placeholders.put("ban_date", dateFormat.format(new Date(banTime)));

                                // Сообщения о временном IP-бане
                                sender.sendMessage(plugin.getMessageManager().getMessage("iptempban_success_moderator", placeholders));
                                if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                    plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("iptempban_success_broadcast", placeholders));
                                }

                                // Кикнуть всех онлайн игроков с этим IP
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                                        if (onlinePlayer.getAddress() != null && onlinePlayer.getAddress().getAddress() != null) {
                                            String playerIp = IPUtils.normalizeIpAddress(onlinePlayer.getAddress().getAddress().getHostAddress());
                                            if (playerIp.equals(normalizedIpToBan)) {
                                                String kickMessage = plugin.getMessageManager().getMessage("ip_banned_screen", placeholders);
                                                onlinePlayer.kickPlayer(kickMessage);
                                            }
                                        }
                                    }
                                });
                            })
                            .exceptionally(ex -> {
                                sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при добавлении временного IP-бана для " + normalizedIpToBan + ": " + ex.getMessage()), ex);
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при проверке существующего IP-бана для " + normalizedIpToBan + ": " + ex.getMessage()), ex);
                    return null;
                });
    }
}