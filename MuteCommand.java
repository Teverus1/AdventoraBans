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

public class MuteCommand extends BaseCommand {

    public MuteCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.mute", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            // Использование отдельного ключа для usage сообщения
            sender.sendMessage(plugin.getMessageManager().getMessage("mute_usage"));
            return;
        }

        final String targetName = args[0];
        final String reason = getReason(args, 1);
        final UUID moderatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        final String moderatorName = (sender instanceof Player) ? sender.getName() : "CONSOLE";

        // Проверка, не мутит ли модератор самого себя
        if (sender instanceof Player && sender.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(plugin.getMessageManager().getMessage("player_self_punish"));
            return;
        }

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

                    plugin.getDatabaseManager().getActivePunishment(target.getUniqueId(), PunishmentType.MUTE)
                            .thenAccept(existingMute -> {
                                if (existingMute.isPresent()) {
                                    Map<String, String> placeholders = Map.of("player_name", punishedNameForRecord);
                                    sender.sendMessage(plugin.getMessageManager().getMessage("player_already_muted", placeholders)); // Исправлено на "player_already_muted"
                                    return;
                                }

                                BanRecord newMute = new BanRecord(
                                        target.getUniqueId(),
                                        punishedNameForRecord,
                                        null,
                                        moderatorUuid,
                                        moderatorName,
                                        PunishmentType.MUTE,
                                        reason,
                                        System.currentTimeMillis(),
                                        0,
                                        true
                                );

                                plugin.getDatabaseManager().addPunishment(newMute)
                                        .thenRun(() -> {
                                            Map<String, String> placeholders = new HashMap<>();
                                            placeholders.put("player_name", punishedNameForRecord);
                                            placeholders.put("moderator_name", moderatorName);
                                            placeholders.put("reason", reason);
                                            placeholders.put("duration", plugin.getMessageManager().getMessage("duration_permanent"));

                                            sender.sendMessage(plugin.getMessageManager().getMessage("mute_success_moderator", placeholders));
                                            if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                                plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("mute_success_broadcast", placeholders));
                                            }

                                            // Сообщаем замученному игроку, если он онлайн
                                            Player onlineTarget = target.getPlayer();
                                            if (onlineTarget != null && onlineTarget.isOnline()) {
                                                plugin.getServer().getScheduler().runTask(plugin, () -> onlineTarget.sendMessage(plugin.getMessageManager().getMessage("player_cannot_chat", placeholders)));
                                            }
                                        })
                                        .exceptionally(ex -> {
                                            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                            plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при добавлении мута для " + punishedNameForRecord + ": " + ex.getMessage()), ex); // Форматирование лога
                                            return null;
                                        });
                            })
                            .exceptionally(ex -> {
                                sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при проверке существующего мута для " + targetName + ": " + ex.getMessage()), ex); // Форматирование лога
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