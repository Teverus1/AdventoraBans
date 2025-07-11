package org.teverus.adventoraBans.listeners;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;
import org.teverus.adventoraBans.util.TimeUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public class PlayerListener implements Listener {

    private AdventoraBans plugin = null;

    // Используем ThreadLocal для SimpleDateFormat для потокобезопасности и переиспользования (Пункт 2)
    private final ThreadLocal<SimpleDateFormat> dateFormatThreadLocal = ThreadLocal.withInitial(() -> {
        SimpleDateFormat format = new SimpleDateFormat(plugin.getMessageManager().getMessage("date_format"));
        format.setTimeZone(TimeZone.getDefault()); // Используем системный часовой пояс по умолчанию
        return format;
    });


    public PlayerListener(AdventoraBans plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID playerUuid = event.getUniqueId();
        String playerName = event.getName();
        String playerIp = event.getAddress().getHostAddress();

        if (plugin.isDebugMode()) { // Пункт 1
            plugin.getLogger().info("DEBUG: Игрок " + playerName + " (" + playerUuid + ", IP: " + playerIp + ") пытается войти.");
        }

        // Асинхронное сохранение IP игрока (не блокирует логин)
        plugin.getDatabaseManager().savePlayerIp(playerUuid, playerIp)
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при сохранении IP игрока " + playerName + ": " + ex.getMessage(), ex);
                    return null;
                });

        // --- Основная логика проверки бана (ДОЛЖНА БЛОКИРОВАТЬ AsyncPlayerPreLoginEvent) ---
        Optional<BanRecord> activeBan = Optional.empty();
        try {
            activeBan = plugin.getDatabaseManager().getActivePunishment(playerUuid, PunishmentType.BAN)
                    .thenCompose(uuidBan -> {
                        if (uuidBan.isPresent()) {
                            return CompletableFuture.completedFuture(uuidBan);
                        }
                        return plugin.getDatabaseManager().getActivePunishment(playerIp, PunishmentType.IP_BAN);
                    })
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore the interrupted status
            plugin.getLogger().log(Level.SEVERE, "Таймаут или прерывание при проверке банов для " + playerName + ": " + e.getMessage(), e);
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            event.setKickMessage(plugin.getMessageManager().getMessage("error_during_login_check"));
            return;
        } catch (TimeoutException e) {
            plugin.getLogger().log(Level.SEVERE, "Таймаут или прерывание при проверке банов для " + playerName + ": " + e.getMessage(), e);
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            event.setKickMessage(plugin.getMessageManager().getMessage("error_during_login_check"));
            return;
        } catch (ExecutionException e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка выполнения асинхронной операции (ExecutionException) при проверке банов для " + playerName + ": " + e.getCause().getMessage(), e.getCause());
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            event.setKickMessage(plugin.getMessageManager().getMessage("error_during_login_check"));
            return;
        } catch (CompletionException e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка БД при проверке банов для " + playerName + ": " + e.getCause().getMessage(), e.getCause());
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            event.setKickMessage(plugin.getMessageManager().getMessage("error_during_login_check"));
            return;
        }

        if (activeBan.isPresent()) {
            BanRecord banRecord = activeBan.get();
            applyBanKickMessage(event, banRecord, playerName);
            if (plugin.isDebugMode()) { // Пункт 1
                plugin.getLogger().info("DEBUG: Игроку " + playerName + " отказано во входе. Активный бан найден.");
            }
        } else {
            if (plugin.isDebugMode()) { // Пункт 1
                plugin.getLogger().info("DEBUG: Игроку " + playerName + " разрешен вход. Банов не найдено.");
            }
        }
    }

    private void applyBanKickMessage(AsyncPlayerPreLoginEvent event, BanRecord ban, String playerName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("reason", ban.getReason());
        placeholders.put("moderator_name", ban.getModeratorName());
        placeholders.put("player_name", playerName);

        // Получаем экземпляр SimpleDateFormat из ThreadLocal (Пункт 2)
        SimpleDateFormat dateFormat = dateFormatThreadLocal.get();
        placeholders.put("ban_date", dateFormat.format(new Date(ban.getBanTime())));

        String durationString;
        String expireDateString;
        if (ban.isPermanent()) {
            durationString = plugin.getMessageManager().getMessage("duration_permanent");
            expireDateString = plugin.getMessageManager().getMessage("duration_permanent");
        } else {
            long remainingMillis = ban.getExpireTime() - System.currentTimeMillis();
            durationString = TimeUtil.formatDuration(remainingMillis);
            expireDateString = dateFormat.format(new Date(ban.getExpireTime()));
        }
        placeholders.put("duration", durationString);
        placeholders.put("expire_date", expireDateString);

        String kickMessage;
        if (ban.getPunishmentType() == PunishmentType.IP_BAN) {
            kickMessage = plugin.getMessageManager().getMessage("ip_banned_screen", placeholders);
        } else {
            kickMessage = plugin.getMessageManager().getMessage("player_banned_screen", placeholders);
        }

        event.setKickMessage(kickMessage);
        event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_BANNED);
        if (plugin.isDebugMode()) { // Пункт 1
            plugin.getLogger().info("DEBUG: Установлено KICK_BANNED для " + playerName + ". Причина: " + ban.getReason() + ". Тип: " + ban.getPunishmentType().name());
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        String chatMessage = event.getMessage();

        if (event.isCancelled()) {
            if (plugin.isDebugMode()) { // Пункт 1
                plugin.getLogger().info("DEBUG: Событие чата уже отменено. Проверка мута пропущена для " + playerName);
            }
            return;
        }

        if (event.getPlayer().hasPermission("adventorabans.bypass.mute")) {
            if (plugin.isDebugMode()) { // Пункт 1
                plugin.getLogger().info("DEBUG: Игрок " + playerName + " имеет обход мута. Проверка мута пропущена.");
            }
            return;
        }

        if (plugin.isDebugMode()) { // Пункт 1
            plugin.getLogger().info("DEBUG: Игрок " + playerName + " пытается отправить сообщение в чат.");
        }

        try {
            Optional<BanRecord> optionalMute = plugin.getDatabaseManager().getActivePunishment(playerUuid, PunishmentType.MUTE)
                    .get(1, TimeUnit.SECONDS);

            if (optionalMute.isPresent()) {
                BanRecord mute = optionalMute.get();

                if (mute.isExpired()) {
                    if (plugin.isDebugMode()) { // Пункт 1
                        plugin.getLogger().info("DEBUG: Мут для " + playerName + " истек. Деактивация в БД.");
                    }
                    // Асинхронно деактивируем, чтобы не блокировать основной поток дальше
                    plugin.getDatabaseManager().deactivatePunishment(mute.getId())
                            .exceptionally(ex -> {
                                plugin.getLogger().log(Level.SEVERE, "Ошибка при асинхронной деактивации мута для " + playerName + ": " + ex.getMessage(), ex);
                                return null;
                            });
                    return;
                }

                // Мут активен: отменяем событие, очищаем получателей и отправляем сообщение
                event.setCancelled(true);
                event.getRecipients().clear(); // Это должно удалить всех получателей по умолчанию

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("reason", mute.getReason());
                placeholders.put("moderator_name", mute.getModeratorName());

                String durationString;
                if (mute.isPermanent()) {
                    durationString = plugin.getMessageManager().getMessage("duration_permanent");
                } else {
                    long remainingMillis = mute.getExpireTime() - System.currentTimeMillis();
                    durationString = TimeUtil.formatDuration(remainingMillis);
                }
                placeholders.put("duration", durationString);

                event.getPlayer().sendMessage(plugin.getMessageManager().getMessage("player_cannot_chat", placeholders));
                if (plugin.isDebugMode()) { // Пункт 1
                    plugin.getLogger().info("DEBUG: Сообщение игрока " + playerName + " отменено из-за мута.");
                }
                return;
            } else {
                if (plugin.isDebugMode()) { // Пункт 1
                    plugin.getLogger().info("DEBUG: Активный мут для " + playerName + " не найден. Сообщение будет отправлено.");
                }
            }
        } catch (InterruptedException | TimeoutException | ExecutionException | CompletionException e) {
            handleMuteCheckException(playerName, e, "chat", event); // Пункт 4
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        String commandMessage = event.getMessage();

        if (event.isCancelled()) {
            if (plugin.isDebugMode()) { // Пункт 1
                plugin.getLogger().info("DEBUG: Событие команды уже отменено. Проверка мута пропущена для " + playerName);
            }
            return;
        }

        if (event.getPlayer().hasPermission("adventorabans.bypass.mute")) {
            if (plugin.isDebugMode()) { // Пункт 1
                plugin.getLogger().info("DEBUG: Игрок " + playerName + " имеет обход мута. Проверка мута пропущена.");
            }
            return;
        }

        List<String> allowedMutedCommands = plugin.getConfigManager().getAllowedMutedCommands();
        String command = commandMessage.split(" ")[0].substring(1).toLowerCase();

        if (allowedMutedCommands.contains(command)) {
            if (plugin.isDebugMode()) { // Пункт 1
                plugin.getLogger().info("DEBUG: Команда '" + command + "' разрешена для замученного игрока. Проверка мута пропускается.");
            }
            return;
        }

        if (plugin.isDebugMode()) { // Пункт 1
            plugin.getLogger().info("DEBUG: Игрок " + playerName + " пытается выполнить команду: " + commandMessage);
        }

        try {
            Optional<BanRecord> optionalMute = plugin.getDatabaseManager().getActivePunishment(playerUuid, PunishmentType.MUTE)
                    .get(1, TimeUnit.SECONDS);

            if (optionalMute.isPresent()) {
                BanRecord mute = optionalMute.get();

                if (mute.isExpired()) {
                    if (plugin.isDebugMode()) { // Пункт 1
                        plugin.getLogger().info("DEBUG: Мут для " + playerName + " истек. Деактивация в БД.");
                    }
                    plugin.getDatabaseManager().deactivatePunishment(mute.getId())
                            .exceptionally(ex -> {
                                plugin.getLogger().log(Level.SEVERE, "Ошибка при асинхронной деактивации мута для " + playerName + ": " + ex.getMessage(), ex);
                                return null;
                            });
                    return;
                }

                // Мут активен: отменяем команду
                event.setCancelled(true);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("reason", mute.getReason());
                placeholders.put("moderator_name", mute.getModeratorName());

                String durationString;
                if (mute.isPermanent()) {
                    durationString = plugin.getMessageManager().getMessage("duration_permanent");
                } else {
                    long remainingMillis = mute.getExpireTime() - System.currentTimeMillis();
                    durationString = TimeUtil.formatDuration(remainingMillis);
                }
                placeholders.put("duration", durationString);

                event.getPlayer().sendMessage(plugin.getMessageManager().getMessage("player_cannot_use_command", placeholders));
                if (plugin.isDebugMode()) { // Пункт 1
                    plugin.getLogger().info("DEBUG: Команда игрока " + playerName + " отменена из-за мута.");
                }
                return;
            } else {
                if (plugin.isDebugMode()) { // Пункт 1
                    plugin.getLogger().info("DEBUG: Активный мут для " + playerName + " не найден. Команда разрешена.");
                }
            }
        } catch (InterruptedException | TimeoutException | ExecutionException | CompletionException e) {
            handleMuteCheckException(playerName, e, "command", event); // Пункт 4
            return;
        }
    }

    /**
     * Вспомогательный метод для обработки исключений при проверке мута. (Пункт 4)
     * Улучшает читаемость и уменьшает дублирование кода.
     *
     * @param playerName Имя игрока, для которого произошла ошибка.
     * @param e          Перехваченное исключение.
     * @param eventType  Тип события (например, "chat" или "command").
     * @param event      Само событие (AsyncPlayerChatEvent или PlayerCommandPreprocessEvent).
     */
    private void handleMuteCheckException(String playerName, Exception e, String eventType, Event event) {
        Level logLevel = Level.SEVERE;
        String errorMessageKey;
        String logMessagePrefix = "Ошибка при проверке мута для " + playerName + " при " + (eventType.equals(Boolean.parseBoolean("chat") ? "отправке чата" : "выполнении команды")) + ": ";

        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt(); // Восстанавливаем статус прерывания
            errorMessageKey = "error_during_mute_check_" + eventType + "_interrupted";
            plugin.getLogger().log(logLevel, logMessagePrefix + "операция прервана: " + e.getMessage(), e);
        } else if (e instanceof TimeoutException) {
            errorMessageKey = "error_during_mute_check_" + eventType + "_timeout";
            plugin.getLogger().log(Level.WARNING, logMessagePrefix + "таймаут: " + e.getMessage(), e);
        } else if (e instanceof ExecutionException) {
            // Исходное исключение обернуто в ExecutionException
            Throwable cause = e.getCause();
            errorMessageKey = "error_during_mute_check_" + eventType + "_db_error";
            plugin.getLogger().log(logLevel, logMessagePrefix + "ошибка выполнения запроса: " + (cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
        } else if (e instanceof CompletionException) {
            // Исходное исключение обернуто в CompletionException (из CompletableFuture)
            Throwable cause = e.getCause();
            errorMessageKey = "error_during_mute_check_" + eventType + "_db_error";
            plugin.getLogger().log(logLevel, logMessagePrefix + "ошибка БД (CompletionException): " + (cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
        } else {
            errorMessageKey = "error_during_mute_check_" + eventType + "_unknown_error";
            plugin.getLogger().log(logLevel, logMessagePrefix + "непредвиденная ошибка: " + e.getMessage(), e);
        }

        // Отменяем событие и отправляем сообщение игроку
        if (event instanceof AsyncPlayerChatEvent) {
            ((AsyncPlayerChatEvent) event).setCancelled(true);
            ((AsyncPlayerChatEvent) event).getRecipients().clear();
            ((AsyncPlayerChatEvent) event).getPlayer().sendMessage(plugin.getMessageManager().getMessage(errorMessageKey));
        } else if (event instanceof PlayerCommandPreprocessEvent) {
            ((PlayerCommandPreprocessEvent) event).setCancelled(true);
            ((PlayerCommandPreprocessEvent) event).getPlayer().sendMessage(plugin.getMessageManager().getMessage(errorMessageKey));
        }
        if (plugin.isDebugMode()) { // Пункт 1
            plugin.getLogger().info("DEBUG: Событие " + eventType + " для " + playerName + " отменено из-за ошибки проверки мута.");
        }
    }
}