package org.teverus.adventoraBans.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;
import org.teverus.adventoraBans.util.TimeUtil;

import java.util.Arrays; // Добавлен импорт
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public class BanListCommand implements CommandExecutor {

    private final AdventoraBans plugin;
    private final int RECORDS_PER_PAGE = 10; // Количество записей на одной странице

    public BanListCommand(AdventoraBans plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("adventorabans.command.banlist")) {
            sender.sendMessage(plugin.getMessageManager().getMessage("no_permission"));
            return true;
        }

        int page = 0; // По умолчанию первая страница (индекс 0)
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]) - 1; // Страницы начинаются с 1 для пользователя, с 0 для кода
                if (page < 0) {
                    sender.sendMessage(plugin.getMessageManager().getMessage("banlist_invalid_page"));
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getMessageManager().getMessage("banlist_usage"));
                return true;
            }
        }

        final int currentPage = page; // Используем final или effectively final переменную

        // Определяем типы банов, которые хотим получить
        List<PunishmentType> banTypes = Arrays.asList(
                PunishmentType.BAN,
                PunishmentType.TEMPBAN,
                PunishmentType.IP_BAN,
                PunishmentType.IPTEMPBAN
        );

        // Асинхронный запрос к базе данных
        CompletableFuture<List<BanRecord>> futureBans = plugin.getDatabaseManager().getActivePunishments(banTypes);

        futureBans.whenComplete((activeBans, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении активных банов для /banlist: ", throwable);
                sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                return;
            }

            if (activeBans == null || activeBans.isEmpty()) {
                sender.sendMessage(plugin.getMessageManager().getMessage("banlist_no_active_bans"));
                return;
            }

            // Вычисляем общее количество страниц
            int totalRecords = activeBans.size();
            int totalPages = (int) Math.ceil((double) totalRecords / RECORDS_PER_PAGE);

            if (currentPage >= totalPages) {
                sender.sendMessage(plugin.getMessageManager().getMessage("banlist_not_found_on_page")
                        .replace("%total_pages%", String.valueOf(totalPages)));
                return;
            }

            sender.sendMessage(plugin.getMessageManager().getMessage("banlist_header")
                    .replace("%page%", String.valueOf(currentPage + 1))
                    .replace("%total_pages%", String.valueOf(totalPages)));

            // Пагинация: получаем подсписок для текущей страницы
            int startIndex = currentPage * RECORDS_PER_PAGE;
            int endIndex = Math.min(startIndex + RECORDS_PER_PAGE, totalRecords);

            List<BanRecord> bansForPage = activeBans.subList(startIndex, endIndex);

            for (BanRecord ban : bansForPage) {
                String remainingTime = TimeUtil.getRemainingDuration(ban.getExpireTime());
                String banTime = TimeUtil.formatTime(ban.getBanTime());

                String targetIdentifier;
                // Определяем, что выводить: никнейм или IP-адрес
                if (ban.getPunishmentType() == PunishmentType.IP_BAN || ban.getPunishmentType() == PunishmentType.IPTEMPBAN) {
                    targetIdentifier = ban.getPunishedIp();
                } else {
                    targetIdentifier = ban.getPunishedName();
                }

                String entryMessage = plugin.getMessageManager().getMessage("banlist_entry")
                        .replace("%target_identifier%", targetIdentifier) // Использовать новый плейсхолдер
                        .replace("%type%", ban.getPunishmentType().name()) // Добавить тип наказания
                        .replace("%moderator_name%", ban.getModeratorName())
                        .replace("%reason%", ban.getReason())
                        .replace("%ban_time%", banTime)
                        .replace("%duration%", remainingTime); // Здесь %duration% - это оставшееся время

                sender.sendMessage(entryMessage);
            }
        });

        return true;
    }
}