package org.teverus.adventoraBans.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;
import org.teverus.adventoraBans.util.IPUtils;
import org.teverus.adventoraBans.util.PlayerUtil; // Возможно, понадобится для получения OfflinePlayer, если захотите улучшить получение имени

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.Optional;

public class IpUnbanCommand extends BaseCommand {

    // Улучшенные паттерны для IPv4 и IPv6
    private static final Pattern IP_V4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );
    private static final Pattern IP_V6_PATTERN = Pattern.compile(
            "(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,6})|:))$"
    );

    public IpUnbanCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.ipunban", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(plugin.getMessageManager().getMessage("ipunban_usage"));
            return;
        }

        final String identifier = args[0];
        final UUID moderatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        final String moderatorName = (sender instanceof Player) ? sender.getName() : "CONSOLE";

        // Проверяем, является ли аргумент IP-адресом
        if (IP_V4_PATTERN.matcher(identifier).matches() || IP_V6_PATTERN.matcher(identifier).matches()) {
            // НОРМАЛИЗАЦИЯ ВХОДЯЩЕГО IP ДЛЯ IP_UNBAN
            final String normalizedIp = IPUtils.normalizeIpAddress(identifier);
            unbanByIpAddress(sender, normalizedIp, moderatorUuid, moderatorName);
        } else {
            // Если не IP, то считаем, что это никнейм
            unbanByPlayerNickname(sender, identifier, moderatorUuid, moderatorName);
        }
    }

    // Метод для разбана по IP-адресу
    private void unbanByIpAddress(CommandSender sender, String targetIp, UUID moderatorUuid, String moderatorName) {
        // targetIp уже должен быть нормализован, так как мы его передаем нормализованным
        plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("Попытка разбана IP-адреса: " + targetIp)); // Форматирование лога
        plugin.getDatabaseManager().getActivePunishment(targetIp, PunishmentType.IP_BAN)
                .thenAccept(existingIpBan -> {
                    if (existingIpBan.isEmpty()) {
                        Map<String, String> placeholders = Map.of("ip", targetIp);
                        sender.sendMessage(plugin.getMessageManager().getMessage("ip_not_banned", placeholders));
                        plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("IP " + targetIp + " не найден как активно забаненный.")); // Форматирование лога
                        return;
                    }

                    final BanRecord banToDeactivate = existingIpBan.get();
                    plugin.getDatabaseManager().deactivatePunishment(banToDeactivate.getId())
                            .thenRun(() -> {
                                // Добавляем запись о разбане IP в историю
                                BanRecord unbanRecord = new BanRecord(
                                        null, // punishedUuid - null для IP-бана, если не связано с конкретным игроком
                                        null, // punishedName - null для IP-бана
                                        targetIp, // targetIp уже нормализован
                                        moderatorUuid,
                                        moderatorName,
                                        PunishmentType.IP_UNBAN,
                                        // Причина разбана также может быть вынесена в messages.yml
                                        plugin.getMessageManager().getMessage("ipunban_reason_entry", Map.of("moderator_name", moderatorName)), // Использование message из messages.yml
                                        System.currentTimeMillis(),
                                        0,
                                        false
                                );
                                plugin.getDatabaseManager().addPunishment(unbanRecord); // Добавляем асинхронно

                                Map<String, String> placeholders = new HashMap<>();
                                placeholders.put("ip", targetIp);
                                placeholders.put("moderator_name", moderatorName);

                                sender.sendMessage(plugin.getMessageManager().getMessage("ipunban_success_moderator", placeholders));
                                if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                                    plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("ipunban_success_broadcast", placeholders));
                                }
                                plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("IP-адрес " + targetIp + " успешно разбанен.")); // Форматирование лога
                            })
                            .exceptionally(ex -> {
                                sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при разбане IP " + targetIp + ": " + ex.getMessage()), ex); // Форматирование лога
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при проверке IP-бана для разбана " + targetIp + ": " + ex.getMessage()), ex); // Форматирование лога
                    return null;
                });
    }

    // Новый метод для разбана по никнейму игрока
    private void unbanByPlayerNickname(CommandSender sender, String nickname, UUID moderatorUuid, String moderatorName) {
        plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("Попытка разбана IP по никнейму: " + nickname)); // Форматирование лога
        // 1. Получаем UUID игрока по никнейму
        plugin.getDatabaseManager().getLastKnownUuidByName(nickname)
                .thenCompose(optionalUuid -> {
                    if (optionalUuid.isPresent()) {
                        final UUID playerUuid = optionalUuid.get();
                        plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("Найден UUID " + playerUuid + " для никнейма " + nickname)); // Форматирование лога
                        // 2. Получаем последний известный IP для этого UUID
                        return plugin.getDatabaseManager().getLastKnownIp(playerUuid);
                    } else {
                        // Если UUID не найден, сразу сообщаем об этом и завершаем цепочку
                        Map<String, String> placeholders = Map.of("player_name", nickname);
                        sender.sendMessage(plugin.getMessageManager().getMessage("player_not_found", placeholders));
                        plugin.getLogger().warning(plugin.getMessageManager().getFormattedMessage("Не удалось найти UUID для игрока " + nickname + " для разбана IP.")); // Форматирование лога
                        return CompletableFuture.completedFuture(Optional.empty()); // Возвращаем пустой Optional
                    }
                })
                .thenAccept(optionalIp -> {
                    if (optionalIp.isPresent()) {
                        final String playerIp = optionalIp.get();
                        plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("Найден IP " + playerIp + " для никнейма " + nickname)); // Форматирование лога
                        // 3. Вызываем существующий метод для разбана IP
                        unbanByIpAddress(sender, playerIp, moderatorUuid, moderatorName);
                    } else {
                        // Если IP не найден (даже если UUID был найден), сообщаем об этом
                        Map<String, String> placeholders = Map.of("player_name", nickname);
                        sender.sendMessage(plugin.getMessageManager().getMessage("player_ip_not_found_for_ipunban", placeholders));
                        plugin.getLogger().warning(plugin.getMessageManager().getFormattedMessage("Не удалось найти IP для игрока " + nickname + " для разбана IP.")); // Форматирование лога
                    }
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при разбане IP по никнейму игрока " + nickname + ": " + ex.getMessage()), ex); // Форматирование лога
                    return null;
                });
    }
}