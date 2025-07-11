package org.teverus.adventoraBans.commands;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;
import org.teverus.adventoraBans.util.PlayerUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class BanCommand extends BaseCommand {

    public BanCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.ban", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            // Использование отдельного ключа для usage сообщения
            sender.sendMessage(plugin.getMessageManager().getMessage("ban_usage"));
            return;
        }

        final String targetName = args[0];
        final String reason = getReason(args, 1);
        final UUID moderatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        final String moderatorName = (sender instanceof Player) ? sender.getName() : "CONSOLE";

        // Проверка, не банит ли модератор самого себя
        if (sender instanceof Player && sender.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(plugin.getMessageManager().getMessage("player_self_punish"));
            return;
        }

        // Асинхронно получаем UUID игрока по нику
        PlayerUtil.getOfflinePlayerAsync(targetName)
                .thenAccept(targetOptional -> {
                    if (targetOptional.isEmpty()) {
                        sender.sendMessage(plugin.getMessageManager().getMessage("player_not_found", Map.of("player", targetName)));
                        return;
                    }

                    final OfflinePlayer target = targetOptional.get();

                    final String punishedNameForRecord;
                    if (target.getName() != null && !target.getName().isEmpty()) {
                        punishedNameForRecord = target.getName();
                    } else {
                        punishedNameForRecord = targetName;
                    }


                    // Проверяем, не забанен ли уже игрок
                    plugin.getDatabaseManager().getActivePunishment(target.getUniqueId(), PunishmentType.BAN)
                            .thenAccept(existingBan -> {
                                if (existingBan.isPresent()) {
                                    Map<String, String> placeholders = Map.of("player_name", punishedNameForRecord);
                                    sender.sendMessage(plugin.getMessageManager().getMessage("player_already_banned", placeholders));
                                    return;
                                }

                                // Создаем новую запись о бане
                                BanRecord newBan = new BanRecord(
                                        target.getUniqueId(),
                                        punishedNameForRecord,
                                        null, // IP будет получен при логине или из истории
                                        moderatorUuid,
                                        moderatorName,
                                        PunishmentType.BAN,
                                        reason,
                                        System.currentTimeMillis(),
                                        0, // 0 для перманентного
                                        true
                                );

                                // Добавляем бан в БД
                                plugin.getDatabaseManager().addPunishment(newBan)
                                        .thenRun(() -> {
                                            Map<String, String> placeholders = new HashMap<>();
                                            placeholders.put("player_name", punishedNameForRecord);
                                            placeholders.put("moderator_name", moderatorName);
                                            placeholders.put("reason", reason);
                                            placeholders.put("duration", plugin.getMessageManager().getMessage("duration_permanent"));

                                            sender.sendMessage(plugin.getMessageManager().getMessage("ban_success_moderator", placeholders));
                                            if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                                plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("ban_success_broadcast", placeholders));
                                            }

                                            // Если игрок онлайн, кикаем его
                                            Player onlineTarget = target.getPlayer();
                                            if (onlineTarget != null && onlineTarget.isOnline()) {
                                                String kickMessage = plugin.getMessageManager().getMessage("player_banned_screen", placeholders);
                                                // Важно: kickPlayer должен быть вызван в главном потоке Spigot
                                                plugin.getServer().getScheduler().runTask(plugin, () -> onlineTarget.kickPlayer(kickMessage));
                                            }
                                        })
                                        .exceptionally(ex -> {
                                            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                            plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при добавлении бана для " + punishedNameForRecord + ": " + ex.getMessage()), ex); // Форматирование лога
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