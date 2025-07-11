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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.TimeZone;

public class IpBanCommand extends BaseCommand {

    public IpBanCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.ipban", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        // Минимальное количество аргументов: 2 (IP/игрок, причина) для перманентного бана,
        // или 3 (IP/игрок, длительность, причина) для временного.
        // Поэтому проверяем args.length < 2, а потом уже внутри определяем наличие длительности.
        if (args.length < 2) {
            sender.sendMessage(plugin.getMessageManager().getMessage("ipban_usage"));
            return;
        }

        final String targetIdentifier = args[0];
        final UUID moderatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        final String moderatorName = (sender instanceof Player) ? sender.getName() : "CONSOLE";

        String durationString;
        String reason;

        // Логика определения наличия аргумента длительности
        // Если args.length == 2, то это должен быть перманентный бан (targetIdentifier, reason)
        // Если args.length >= 3, то args[1] может быть длительностью.
        // Попытаемся распарсить args[1] как длительность. Если успешно, то это временный бан.
        // Иначе, это перманентный бан, и args[1] - часть причины.

        boolean isTempBanFormat = false;
        if (args.length >= 3) {
            long parsedDurationCheck = TimeUtil.parseDuration(args[1]);
            // Если parseDuration вернул не -1, значит, это валидная длительность
            if (parsedDurationCheck != -1 || args[1].equalsIgnoreCase("perm") || args[1].equalsIgnoreCase("permanent")) {
                isTempBanFormat = true;
            }
        }

        if (isTempBanFormat) {
            durationString = args[1];
            reason = getReason(args, 2);
        } else {
            // Предполагаем перманентный бан, если второй аргумент не является длительностью.
            // Причина начинается со второго аргумента.
            durationString = "perm"; // Указываем, что бан перманентный
            reason = getReason(args, 1);
        }

        final long durationMillis = TimeUtil.parseDuration(durationString);
        if (durationMillis == -1 && !durationString.equalsIgnoreCase("perm") && !durationString.equalsIgnoreCase("permanent")) {
            sender.sendMessage(plugin.getMessageManager().getMessage("duration_format_error"));
            return;
        }

        // Определяем, является ли идентификатор IP-адресом или ником игрока
        if (IPUtils.isValidIpAddress(targetIdentifier)) {
            // Это IP-адрес
            processIpBan(sender, targetIdentifier, moderatorUuid, moderatorName, reason, durationMillis);
        } else {
            // Это, вероятно, ник игрока, пытаемся получить его последний IP
            PlayerUtil.getOfflinePlayerAsync(targetIdentifier)
                    .thenCompose(targetOptional -> {
                        if (targetOptional.isEmpty()) {
                            sender.sendMessage(plugin.getMessageManager().getMessage("player_not_found", Map.of("player", targetIdentifier)));
                            return CompletableFuture.completedFuture(Optional.empty());
                        }
                        final OfflinePlayer target = targetOptional.get();

                        // Проверяем, не банит ли модератор самого себя по IP
                        // Это сложнее, так как нужен IP модератора. Пока что пропустим эту проверку для IP-бана.
                        // Если очень нужно, то нужно получать IP модератора и сравнивать.
                        /*
                        if (sender instanceof Player && target.getUniqueId().equals(((Player) sender).getUniqueId())) {
                            sender.sendMessage(plugin.getMessageManager().getMessage("player_self_punish"));
                            return CompletableFuture.completedFuture(Optional.empty());
                        }
                        */

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
                            // Исправлено: приведение типа
                            String ipToBan = (String) ipOptional.get();
                            processIpBan(sender, ipToBan, moderatorUuid, moderatorName, reason, durationMillis);
                        }
                    })
                    .exceptionally(ex -> {
                        sender.sendMessage(plugin.getMessageManager().getMessage("error_command_execution", Map.of("error", "Player/IP resolution error.")));
                        plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при разрешении игрока/IP для IP-бана " + targetIdentifier + ": " + ex.getMessage()), ex); // Форматирование лога
                        return null;
                    });
        }
    }

    private void processIpBan(CommandSender sender, String ipToBan, UUID moderatorUuid, String moderatorName, String reason, long durationMillis) {
        final String normalizedIpToBan = IPUtils.normalizeIpAddress(ipToBan);

        // Проверяем и перманентные, и временные IP-баны
        CompletableFuture<Optional<BanRecord>> activeIpBanFuture = plugin.getDatabaseManager().getActivePunishment(normalizedIpToBan, PunishmentType.IP_BAN);
        CompletableFuture<Optional<BanRecord>> activeTempIpBanFuture = plugin.getDatabaseManager().getActivePunishment(normalizedIpToBan, PunishmentType.TEMP_IP_BAN);

        CompletableFuture.allOf(activeIpBanFuture, activeTempIpBanFuture)
                .thenAccept(__ -> {
                    Optional<BanRecord> existingIpBan = activeIpBanFuture.join();
                    Optional<BanRecord> existingTempIpBan = activeTempIpBanFuture.join();

                    if (existingIpBan.isPresent() || existingTempIpBan.isPresent()) {
                        Map<String, String> placeholders = Map.of("ip", normalizedIpToBan);
                        sender.sendMessage(plugin.getMessageManager().getMessage("player_already_ip_banned", placeholders));
                        return;
                    }

                    final long banTime = System.currentTimeMillis();
                    final long expireTime = (durationMillis == 0) ? 0 : banTime + durationMillis;

                    BanRecord newIpBan = new BanRecord(
                            null, // UUID не нужен для IP бана
                            null, // Имя не нужно для IP бана
                            normalizedIpToBan,
                            moderatorUuid,
                            moderatorName,
                            (durationMillis == 0) ? PunishmentType.IP_BAN : PunishmentType.TEMP_IP_BAN,
                            reason,
                            banTime,
                            expireTime,
                            true
                    );

                    plugin.getDatabaseManager().addPunishment(newIpBan)
                            .thenRun(() -> {
                                Map<String, String> placeholders = new HashMap<>();
                                placeholders.put("ip", normalizedIpToBan);
                                placeholders.put("moderator_name", moderatorName);
                                placeholders.put("reason", reason);

                                String formattedDuration;
                                String formattedExpireTime;
                                if (newIpBan.isPermanent()) {
                                    formattedDuration = plugin.getMessageManager().getMessage("duration_permanent");
                                    formattedExpireTime = plugin.getMessageManager().getMessage("duration_permanent");
                                } else {
                                    formattedDuration = TimeUtil.formatDuration(durationMillis);
                                    SimpleDateFormat dateFormat = new SimpleDateFormat(plugin.getMessageManager().getMessage("date_format"));
                                    dateFormat.setTimeZone(TimeZone.getDefault());
                                    formattedExpireTime = dateFormat.format(new Date(expireTime));
                                }
                                placeholders.put("duration", formattedDuration);
                                placeholders.put("expire_date", formattedExpireTime);
                                SimpleDateFormat dateFormatForBanDate = new SimpleDateFormat(plugin.getMessageManager().getMessage("date_format"));
                                dateFormatForBanDate.setTimeZone(TimeZone.getDefault());
                                placeholders.put("ban_date", dateFormatForBanDate.format(new Date(banTime)));


                                if (newIpBan.getPunishmentType() == PunishmentType.IP_BAN) {
                                    sender.sendMessage(plugin.getMessageManager().getMessage("ipban_success_moderator", placeholders));
                                    if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                        plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("ipban_success_broadcast", placeholders));
                                    }
                                } else {
                                    sender.sendMessage(plugin.getMessageManager().getMessage("iptempban_success_moderator", placeholders));
                                    if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                        plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("iptempban_success_broadcast", placeholders));
                                    }
                                }

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
                                plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при добавлении IP-бана для " + normalizedIpToBan + ": " + ex.getMessage()), ex); // Форматирование лога
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при проверке существующего IP-бана для " + normalizedIpToBan + ": " + ex.getMessage()), ex); // Форматирование лога
                    return null;
                });
    }
}