package org.teverus.adventoraBans.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.teverus.adventoraBans.AdventoraBans;
// import org.teverus.adventoraBans.commands.BaseCommand; // Это лишний импорт, так как вы уже в пакете commands
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.util.TimeUtil; // Добавляем импорт TimeUtil
import org.teverus.adventoraBans.util.IPUtils;
import net.md_5.bungee.api.chat.ClickEvent; // Для кликабельных сообщений
import net.md_5.bungee.api.chat.ComponentBuilder; // Для построения сообщения
import net.md_5.bungee.api.chat.HoverEvent; // Для подсказок при наведении
import net.md_5.bungee.api.chat.TextComponent; // Для элементов сообщения
import org.bukkit.ChatColor; // Для цветов

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger; // Для безопасного использования в лямбдах
import java.util.Map; // Для пагинации для каждого игрока
import java.util.HashMap; // Для пагинации для каждого игрока
import java.util.logging.Level;
import java.util.Optional; // Добавляем импорт Optional

public class PunishmentHistoryCommand extends BaseCommand {

    private static final int ITEMS_PER_PAGE = 5; // Сколько записей на одной странице
    // Карта для отслеживания текущих страниц для игроков и их целей просмотра
    private final Map<UUID, PunishmentViewContext> playerContexts = new HashMap<>();

    public PunishmentHistoryCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.history", false); // Можно сделать requiresPlayer true, если хотите только для игроков
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        UUID senderUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        String targetInput = null; // Может быть никнейм или IP
        int page = 1;

        // Обработка аргументов: [ник/IP] [страница] или [страница]
        if (args.length >= 1) {
            // Попытка распарсить первый аргумент как номер страницы
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) page = 1;
                targetInput = null; // Если первый аргумент - страница, значит, это глобальная история
            } catch (NumberFormatException e) {
                // Если первый аргумент не число, то это целевой никнейм или IP
                targetInput = args[0];
                if (args.length >= 2) {
                    try {
                        page = Integer.parseInt(args[1]);
                        if (page < 1) page = 1;
                    } catch (NumberFormatException ignored) {
                        // Второй аргумент не число, игнорируем, страница останется 1
                    }
                }
            }
        }

        // Получаем контекст для текущего игрока-отправителя
        PunishmentViewContext context = playerContexts.computeIfAbsent(senderUuid, k -> new PunishmentViewContext());
        context.setCurrentPage(page);

        if (targetInput != null) {
            // Если указана цель, обновляем контекст
            context.setTargetInput(targetInput);
        } else {
            // Если цель не указана, но была в предыдущем запросе, сбрасываем ее
            if (args.length == 0 || (args.length == 1 && Character.isDigit(args[0].charAt(0)))) {
                context.setTargetInput(null);
            }
        }

        String finalTargetInput = context.getTargetInput();

        if (finalTargetInput != null) {
            // Проверяем, является ли цель IP-адресом
            // Предполагаем, что у вас есть метод getIpUtils() в главном классе плагина
            if (IPUtils.isValidIpAddress(finalTargetInput)) {
                // Это IP-адрес, показываем историю IP-банов
                plugin.getDatabaseManager().getPunishmentHistory(finalTargetInput)
                        .thenAccept(punishments -> {
                            // Здесь мы получаем ВСЮ историю для IP, нужно вручную пагинировать
                            int totalItems = punishments.size();
                            int totalPages = (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE);
                            int currentPage = context.getCurrentPage();
                            if (currentPage > totalPages && totalPages > 0) currentPage = totalPages;
                            if (currentPage < 1) currentPage = 1;

                            int startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
                            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalItems);

                            List<BanRecord> pagePunishments = (totalItems > 0) ? punishments.subList(startIndex, endIndex) : List.of();

                            displayPunishments(sender, pagePunishments, totalItems, currentPage, totalPages, finalTargetInput, true); // true для IP
                        })
                        .exceptionally(ex -> {
                            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                            plugin.getLogger().log(Level.SEVERE, "Ошибка при получении истории наказаний для IP " + finalTargetInput + ": " + ex.getMessage(), ex);
                            return null;
                        });
            } else {
                // Это никнейм, ищем UUID
                plugin.getDatabaseManager().getLastKnownUuidByName(finalTargetInput)
                        .thenAccept(optionalUuid -> {
                            if (optionalUuid.isPresent()) {
                                UUID targetUuid = optionalUuid.get();
                                plugin.getDatabaseManager().getPunishmentsCountByPlayerUuid(targetUuid)
                                        .thenAccept(totalItems -> {
                                            displayPunishmentsForPlayer(sender, targetUuid, finalTargetInput, totalItems, context.getCurrentPage());
                                        })
                                        .exceptionally(ex -> {
                                            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                                            plugin.getLogger().log(Level.SEVERE, "Ошибка при получении количества наказаний для игрока " + finalTargetInput + ": " + ex.getMessage(), ex);
                                            return null;
                                        });
                            } else {
                                sender.sendMessage(plugin.getMessageManager().getMessage("player_not_found", Map.of("player_name", finalTargetInput)));
                            }
                        })
                        .exceptionally(ex -> {
                            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                            plugin.getLogger().log(Level.SEVERE, "Ошибка при поиске UUID для игрока " + finalTargetInput + ": " + ex.getMessage(), ex);
                            return null;
                        });
            }
        } else {
            // Просмотр глобальной истории
            plugin.getDatabaseManager().getTotalPunishmentsCount()
                    .thenAccept(totalItems -> {
                        displayAllPunishments(sender, totalItems, context.getCurrentPage());
                    })
                    .exceptionally(ex -> {
                        sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                        plugin.getLogger().log(Level.SEVERE, "Ошибка при получении общего количества наказаний: " + ex.getMessage(), ex);
                        return null;
                    });
        }
    }

    private void displayPunishmentsForPlayer(CommandSender sender, UUID targetUuid, String targetName, int totalItems, int page) {
        int totalPages = (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE);
        if (page > totalPages && totalPages > 0) page = totalPages;
        if (page < 1) page = 1;

        int offset = (page - 1) * ITEMS_PER_PAGE;

        final int currentPageFinal = page; // Для использования в лямбде

        sender.sendMessage(plugin.getMessageManager().getMessage("history_header_player", Map.of("player_name", targetName, "page", String.valueOf(currentPageFinal), "total_pages", String.valueOf(totalPages))));

        plugin.getDatabaseManager().getPunishmentsByPlayerUuid(targetUuid, ITEMS_PER_PAGE, offset)
                .thenAccept(punishments -> {
                    displayPunishments(sender, punishments, totalItems, currentPageFinal, totalPages, targetName, false); // false для игрока
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при получении наказаний для игрока " + targetName + ": " + ex.getMessage(), ex);
                    return null;
                });
    }

    private void displayAllPunishments(CommandSender sender, int totalItems, int page) {
        int totalPages = (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE);
        if (page > totalPages && totalPages > 0) page = totalPages;
        if (page < 1) page = 1;

        int offset = (page - 1) * ITEMS_PER_PAGE;

        final int currentPageFinal = page; // Для использования в лямбде

        sender.sendMessage(plugin.getMessageManager().getMessage("history_header_global", Map.of("page", String.valueOf(currentPageFinal), "total_pages", String.valueOf(totalPages))));

        plugin.getDatabaseManager().getAllPunishments(ITEMS_PER_PAGE, offset)
                .thenAccept(punishments -> {
                    displayPunishments(sender, punishments, totalItems, currentPageFinal, totalPages, null, false); // null для глобальной, false для игрока
                })
                .exceptionally(ex -> {
                    sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при получении всех наказаний: " + ex.getMessage(), ex);
                    return null;
                });
    }

    private void displayPunishments(CommandSender sender, List<BanRecord> punishments, int totalItems, int currentPage, int totalPages, String targetNameOrIp, boolean isIpHistory) {
        if (punishments.isEmpty() && totalItems > 0) {
            sender.sendMessage(plugin.getMessageManager().getMessage("no_punishments_found_on_page")); // Новое сообщение
        } else if (punishments.isEmpty()) {
            sender.sendMessage(plugin.getMessageManager().getMessage("no_punishments_found"));
        } else {
            AtomicInteger counter = new AtomicInteger((currentPage - 1) * ITEMS_PER_PAGE + 1); // Сброс счетчика для каждой страницы
            punishments.forEach(record -> {
                String status = record.isActive() ? ChatColor.GREEN + "[АКТИВЕН]" : ChatColor.RED + "[НЕ АКТИВЕН]";
                if (record.getExpireTime() > 0 && record.isExpired()) {
                    status = ChatColor.GRAY + "[ИСТЕК]" + ChatColor.RESET;
                }

                // Форматирование даты/времени
                // Использование getTimeUtil() через экземпляр плагина
                String banTime = TimeUtil.formatTime(record.getBanTime());
                String expireTime = (record.getExpireTime() == 0) ? plugin.getMessageManager().getMessage("permanent_duration") : TimeUtil.formatTime(record.getExpireTime());
                String remainingTime = (record.getExpireTime() > 0 && !record.isExpired()) ? " " + plugin.getMessageManager().getMessage("remaining_duration", Map.of("duration", TimeUtil.getRemainingDuration(record.getExpireTime()))) : "";

                String target = "";
                if (record.getPunishedName() != null && !record.getPunishedName().isEmpty()) {
                    target = record.getPunishedName();
                } else if (record.getPunishedIp() != null && !record.getPunishedIp().isEmpty()) {
                    target = record.getPunishedIp();
                } else {
                    target = "Неизвестно"; // На случай, если оба поля null, хотя по логике такого быть не должно
                }

                String punishmentEntry = plugin.getMessageManager().getMessage(
                        "history_entry",
                        Map.of(
                                "id", String.valueOf(counter.getAndIncrement()),
                                "type", record.getPunishmentType().name(), // ИЗМЕНЕНО: record.getType() -> record.getPunishmentType()
                                "target", target,
                                "reason", record.getReason(),
                                "moderator", record.getModeratorName(),
                                "ban_time", banTime,
                                "expire_time", expireTime,
                                "status", status,
                                "remaining_time", remainingTime
                        )
                );
                sender.sendMessage(punishmentEntry);
            });

            sendPaginationFooter(sender, targetNameOrIp, currentPage, totalPages, isIpHistory);
        }
    }


    private void sendPaginationFooter(CommandSender sender, String targetNameOrIp, int currentPage, int totalPages, boolean isIpHistory) {
        if (totalPages <= 1) return; // Нет нужды в пагинации, если только одна страница

        ComponentBuilder footer = new ComponentBuilder("");

        // Кнопка "Предыдущая"
        if (currentPage > 1) {
            String prevCommand = "/history " + (targetNameOrIp != null ? targetNameOrIp + " " : "") + (currentPage - 1);
            TextComponent prev = new TextComponent(ChatColor.GRAY + "« Предыдущая");
            prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, prevCommand));
            prev.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Нажмите для перехода на страницу " + (currentPage - 1)).color(net.md_5.bungee.api.ChatColor.AQUA).create()));
            footer.append(prev);
        }

        // Информация о текущей странице
        footer.append(" " + ChatColor.YELLOW + currentPage + ChatColor.GRAY + "/" + ChatColor.YELLOW + totalPages + " ", ComponentBuilder.FormatRetention.NONE);

        // Кнопка "Следующая"
        if (currentPage < totalPages) {
            String nextCommand = "/history " + (targetNameOrIp != null ? targetNameOrIp + " " : "") + (currentPage + 1);
            TextComponent next = new TextComponent(ChatColor.GRAY + "Следующая »");
            next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, nextCommand));
            next.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Нажмите для перехода на страницу " + (currentPage + 1)).color(net.md_5.bungee.api.ChatColor.AQUA).create()));
            footer.append(next);
        }

        sender.spigot().sendMessage(footer.create()); // Отправляем как Spigot-компонент
    }

    // Внутренний класс для хранения контекста просмотра истории для каждого игрока
    private static class PunishmentViewContext {
        private String targetInput; // null для глобальной истории, может быть никнейм или IP
        private int currentPage;

        public PunishmentViewContext() {
            this.currentPage = 1;
        }

        public String getTargetInput() {
            return targetInput;
        }

        public void setTargetInput(String targetInput) {
            this.targetInput = targetInput;
        }

        public int getCurrentPage() {
            return currentPage;
        }

        public void setCurrentPage(int currentPage) {
            this.currentPage = currentPage;
        }
    }
}