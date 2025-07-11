package org.teverus.adventoraBans.commands;

import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class KickCommand extends BaseCommand {

    public KickCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.kick", false);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            // Использование отдельного ключа для usage сообщения
            sender.sendMessage(plugin.getMessageManager().getMessage("kick_usage"));
            return;
        }

        String targetName = args[0];
        String reason = getReason(args, 1);
        UUID moderatorUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        String moderatorName = (sender instanceof Player) ? sender.getName() : "CONSOLE";

        // Проверка, не кикает ли модератор самого себя
        if (sender instanceof Player && sender.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(plugin.getMessageManager().getMessage("player_self_punish"));
            return;
        }

        Player targetPlayer = plugin.getServer().getPlayer(targetName);

        if (targetPlayer == null || !targetPlayer.isOnline()) {
            sender.sendMessage(plugin.getMessageManager().getMessage("player_not_online", Map.of("player", targetName)));
            return;
        }

        // Кик не требует асинхронной проверки UUID, т.к. игрок уже онлайн и у нас есть его объект.
        // Просто добавляем запись о кике в БД и кикаем.
        BanRecord newKick = new BanRecord(
                targetPlayer.getUniqueId(),
                targetPlayer.getName(),
                targetPlayer.getAddress().getAddress().getHostAddress(), // Сохраняем IP на момент кика
                moderatorUuid,
                moderatorName,
                PunishmentType.KICK,
                reason,
                System.currentTimeMillis(),
                0, // Кик не имеет срока истечения
                false // Кик не является "активным" наказанием в плане блокировки доступа
        );

        plugin.getDatabaseManager().addPunishment(newKick)
                .thenRun(() -> {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player_name", targetPlayer.getName());
                    placeholders.put("moderator_name", moderatorName);
                    placeholders.put("reason", reason);

                    sender.sendMessage(plugin.getMessageManager().getMessage("kick_success_moderator", placeholders));
                    if (plugin.getConfigManager().shouldBroadcastPunishments()) {
                        plugin.getServer().broadcastMessage(plugin.getMessageManager().getMessage("kick_success_broadcast", placeholders));
                    }

                    String kickMessage = plugin.getMessageManager().getMessage("player_kicked_screen", placeholders);
                    // Кикнуть игрока нужно в основном потоке Bukkit
                    plugin.getServer().getScheduler().runTask(plugin, () -> targetPlayer.kickPlayer(kickMessage));
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при добавлении кика для " + targetName + ": " + ex.getMessage()), ex); // Форматирование лога
                    return null;
                });
    }
}