package org.teverus.adventoraBans.commands;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;
import org.teverus.adventoraBans.util.PlayerUtil;
import org.teverus.adventoraBans.util.TimeUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.TimeZone;
import java.util.logging.Level;

public class TempBanCommand extends BaseCommand {

    public TempBanCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.tempban", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            // Использование отдельного ключа для usage сообщения
            sender.sendMessage(plugin.getMessageManager().getMessage("tempban_usage"));
            return;
        }

        final String targetName = args[0];
        final String durationString = args[1];
        final String reason = getReason(args, 2);
        final UUID moderatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        final String moderatorName = (sender instanceof Player) ? sender.getName() : "CONSOLE";

        // Проверка, не банит ли модератор самого себя
        if (sender instanceof Player && sender.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(plugin.getMessageManager().getMessage("player_self_punish"));
            return;
        }

        final long durationMillis = TimeUtil.parseDuration(durationString);
        if (durationMillis <= 0 && !durationString.equalsIgnoreCase("perm") && !durationString.equalsIgnoreCase("permanent")) {
            sender.sendMessage(plugin.getMessageManager().getMessage("duration_format_error"));
            return;
        }

        PlayerUtil.getOfflinePlayerAsync(targetName)
                .thenAccept(targetOptional -> {
                    if (targetOptional.isEmpty()) {
                        sender.sendMessage(plugin.getMessageManager().getMessage("player_not_found", Map.of("player", targetName)));
                        return;
                    }
                    final OfflinePlayer target = targetOptional.get();

                    // Определяем имя для записи в базу данных.
                    final String punishedNameForRecord;
                    if (target.getName() != null && !target.getName().isEmpty()) {
                        punishedNameForRecord = target.getName();
                    } else {
                        punishedNameForRecord = targetName;
                    }

                    plugin.getDatabaseManager().getActivePunishment(target.getUniqueId(), PunishmentType.BAN)
                            .thenAccept(existingBan -> {
                                if (existingBan.isPresent()) {
                                    Map<String, String> placeholders = Map.of("player_name", punishedNameForRecord); // Используем punishedNameForRecord
                                    sender.sendMessage(plugin.getMessageManager().getMessage("player_already_banned", placeholders));
                                    return;
                                }

                                final long banTime = System.currentTimeMillis();
                                final long expireTime = (durationMillis == 0) ? 0 : banTime + durationMillis;

                                BanRecord newBan = new BanRecord(
                                        target.getUniqueId(),
                                        punishedNameForRecord, // ИСПОЛЬЗУЕМ punishedNameForRecord
                                        null, // IP будет получен при логине или из истории
                                        moderatorUuid,
                                        moderatorName,
                                        (durationMillis == 0) ? PunishmentType.BAN : PunishmentType.TEMPBAN,
                                        reason,
                                        banTime,
                                        expireTime,
                                        true
                                );

                                plugin.getDatabaseManager().addPunishment(newBan)
                                        .thenRun(() -> {
                                            Map<String, String> placeholders = new HashMap<>();
                                            placeholders.put("player_name", punishedNameForRecord); // ИСПОЛЬЗУЕМ punishedNameForRecord
                                            placeholders.put("moderator_name", moderatorName);
                                            placeholders.put("reason", reason);

                                            String formattedDuration;
                                            String formattedExpireTime;
                                            if (newBan.isPermanent()) {
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
                                            placeholders.put("ban_date", new SimpleDateFormat(plugin.getMessageManager().getMessage("date_format")).format(new Date(banTime)));

                                            // Выбираем правильное сообщение в зависимости от типа бана
                                            if (newBan.getPunishmentType() == PunishmentType.BAN) {
                                                sender.sendMessage(plugin.getMessageManager().getMessage("ban_success_moderator", placeholders));
                                                if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                                    plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("ban_success_broadcast", placeholders));
                                                }
                                            } else { // PunishmentType.TEMPBAN
                                                sender.sendMessage(plugin.getMessageManager().getMessage("tempban_success_moderator", placeholders)); // NEW MESSAGE KEY
                                                if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                                    plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("tempban_success_broadcast", placeholders)); // NEW MESSAGE KEY
                                                }
                                            }


                                            Player onlineTarget = target.getPlayer();
                                            if (onlineTarget != null && onlineTarget.isOnline()) {
                                                String kickMessage = plugin.getMessageManager().getMessage("player_banned_screen", placeholders);
                                                plugin.getServer().getScheduler().runTask(plugin, () -> onlineTarget.kickPlayer(kickMessage));
                                            }
                                        })
                                        .exceptionally(ex -> {
                                            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                            plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при добавлении временного бана для " + punishedNameForRecord + ": " + ex.getMessage()), ex); // Форматирование лога
                                            return null;
                                        });
                            })
                            .exceptionally(ex -> {
                                sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при проверке существующего бана для " + targetName + ": " + ex.getMessage()), ex); // Форматирование лога
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("error_command_execution", Map.of("error", "Player resolution error.")));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при разрешении игрока " + targetName + ": " + ex.getMessage()), ex); // Форматирование лога
                    return null;
                });
    }
}