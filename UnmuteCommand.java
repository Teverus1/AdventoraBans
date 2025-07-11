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

public class UnmuteCommand extends BaseCommand {

    public UnmuteCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.unmute", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            // Использование отдельного ключа для usage сообщения
            sender.sendMessage(plugin.getMessageManager().getMessage("unmute_usage"));
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

                    plugin.getDatabaseManager().getActivePunishment(target.getUniqueId(), PunishmentType.MUTE)
                            .thenAccept(existingMute -> {
                                if (existingMute.isEmpty()) {
                                    sender.sendMessage(plugin.getMessageManager().getMessage("player_not_muted", Map.of("player", targetIdentifier)));
                                    return;
                                }

                                final BanRecord muteToDeactivate = existingMute.get();

                                // Определяем имя для записи о размуте и для сообщений
                                String punishedNameForUnmuteRecord = muteToDeactivate.getPunishedName();

                                if (punishedNameForUnmuteRecord == null || punishedNameForUnmuteRecord.isEmpty()) {
                                    if (target.getName() != null && !target.getName().isEmpty()) {
                                        punishedNameForUnmuteRecord = target.getName();
                                    } else {
                                        punishedNameForUnmuteRecord = targetIdentifier;
                                    }
                                }

                                final String finalPunishedNameForUnmuteRecord = punishedNameForUnmuteRecord;

                                // Деактивируем существующий мут
                                plugin.getDatabaseManager().deactivatePunishment(muteToDeactivate.getId())
                                        .thenRun(() -> {
                                            // Добавляем запись о размуте в историю
                                            BanRecord unmuteRecord = new BanRecord(
                                                    target.getUniqueId(),
                                                    finalPunishedNameForUnmuteRecord,
                                                    null,
                                                    moderatorUuid,
                                                    moderatorName,
                                                    PunishmentType.UNMUTE,
                                                    // Использование message из messages.yml для причины размута
                                                    plugin.getMessageManager().getMessage("unmute_reason_entry", Map.of("moderator_name", moderatorName)),
                                                    System.currentTimeMillis(),
                                                    0,
                                                    false
                                            );
                                            plugin.getDatabaseManager().addPunishment(unmuteRecord);

                                            Map<String, String> placeholders = new HashMap<>();
                                            placeholders.put("player_name", finalPunishedNameForUnmuteRecord);
                                            placeholders.put("moderator_name", moderatorName);

                                            sender.sendMessage(plugin.getMessageManager().getMessage("unmute_success_moderator", placeholders));
                                            if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                                plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("unmute_success_broadcast", placeholders));
                                            }
                                        })
                                        .exceptionally(ex -> {
                                            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                            plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при размуте " + finalPunishedNameForUnmuteRecord + ": " + ex.getMessage()), ex); // Форматирование лога
                                            return null;
                                        });
                            })
                            .exceptionally(ex -> {
                                sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при проверке мута для размута " + targetIdentifier + ": " + ex.getMessage()), ex); // Форматирование лога
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("error_command_execution", Map.of("error", "Player resolution error.")));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при разрешении игрока " + targetIdentifier + ": " + ex.getMessage()), ex); // Форматирование лога
                    return null;
                });
    }
}