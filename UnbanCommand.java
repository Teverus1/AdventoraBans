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
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class UnbanCommand extends BaseCommand {

    public UnbanCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.unban", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            // Использование отдельного ключа для usage сообщения
            sender.sendMessage(plugin.getMessageManager().getMessage("unban_usage"));
            return;
        }

        final String targetIdentifier = args[0];
        final UUID moderatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        final String moderatorName = (sender instanceof Player) ? sender.getName() : "CONSOLE";

        PlayerUtil.getOfflinePlayerAsync(targetIdentifier)
                .thenAccept(targetOptional -> {
                    if (targetOptional.isEmpty()) {
                        sender.sendMessage(plugin.getMessageManager().getMessage("player_not_found", Map.of("player", targetIdentifier)));
                        return;
                    }
                    final OfflinePlayer target = targetOptional.get();

                    plugin.getDatabaseManager().getActivePunishment(target.getUniqueId(), PunishmentType.BAN)
                            .thenAccept(existingBan -> {
                                if (existingBan.isEmpty()) {
                                    sender.sendMessage(plugin.getMessageManager().getMessage("player_not_banned", Map.of("player", targetIdentifier)));
                                    return;
                                }

                                final BanRecord banToDeactivate = existingBan.get();

                                // Ключевое изменение: Получаем имя из существующей записи о бане
                                String punishedNameForUnbanRecord = banToDeactivate.getPunishedName();

                                // Запасной вариант, если punishedName в BanRecord каким-то образом null (не должно быть, но для безопасности)
                                if (punishedNameForUnbanRecord == null || punishedNameForUnbanRecord.isEmpty()) {
                                    if (PlayerUtil.isValidUUID(targetIdentifier)) {
                                        punishedNameForUnbanRecord = target.getUniqueId().toString();
                                    } else {
                                        punishedNameForUnbanRecord = targetIdentifier;
                                    }
                                }

                                final String finalPunishedNameForUnbanRecord = punishedNameForUnbanRecord;

                                // Деактивируем существующий бан
                                plugin.getDatabaseManager().deactivatePunishment(banToDeactivate.getId())
                                        .thenRun(() -> {
                                            // Добавляем запись о разбане в историю
                                            BanRecord unbanRecord = new BanRecord(
                                                    target.getUniqueId(),
                                                    finalPunishedNameForUnbanRecord,
                                                    banToDeactivate.getPunishedIp(),
                                                    moderatorUuid,
                                                    moderatorName,
                                                    PunishmentType.UNBAN,
                                                    // Использование message из messages.yml для причины разбана
                                                    plugin.getMessageManager().getMessage("unban_reason_entry", Map.of("moderator_name", moderatorName)),
                                                    System.currentTimeMillis(),
                                                    0,
                                                    false
                                            );
                                            plugin.getDatabaseManager().addPunishment(unbanRecord);

                                            Map<String, String> placeholders = new HashMap<>();
                                            placeholders.put("player_name", finalPunishedNameForUnbanRecord);
                                            placeholders.put("moderator_name", moderatorName);

                                            sender.sendMessage(plugin.getMessageManager().getMessage("unban_success_moderator", placeholders));
                                            if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                                plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("unban_success_broadcast", placeholders));
                                            }
                                        })
                                        .exceptionally(ex -> {
                                            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                            plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при деактивации бана для " + targetIdentifier + ": " + ex.getMessage()), ex); // Форматирование лога
                                            return null;
                                        });
                            })
                            .exceptionally(ex -> {
                                sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при проверке активного бана для " + targetIdentifier + ": " + ex.getMessage()), ex); // Форматирование лога
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("error_command_execution", Map.of("error", "Player resolution error.")));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при разрешении игрока " + targetIdentifier + " для разбана: " + ex.getMessage()), ex); // Форматирование лога
                    return null;
                });
    }
}