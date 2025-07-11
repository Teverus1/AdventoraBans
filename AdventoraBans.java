package org.teverus.adventoraBans;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import org.teverus.adventoraBans.commands.*;
import org.teverus.adventoraBans.config.ConfigManager;
import org.teverus.adventoraBans.database.DatabaseManager;
import org.teverus.adventoraBans.database.MySQLManager;
import org.teverus.adventoraBans.database.SQLiteManager;
import org.teverus.adventoraBans.listeners.PlayerListener;
import org.teverus.adventoraBans.messages.MessageManager;
import org.teverus.adventoraBans.punishments.PunishmentType;
import org.teverus.adventoraBans.tasks.PunishmentCleanupTask;
import org.teverus.adventoraBans.util.PlayerUtil;
import org.teverus.adventoraBans.util.TimeUtil; // Убедитесь, что TimeUtil импортирован
import org.teverus.adventoraBans.util.IPUtils; // Убедитесь, что IPUtils импортирован

import org.teverus.adventoraBans.database.AbstractDatabaseManager; // НОВЫЙ ИМПОРТ: для getExecutorService()
import java.util.concurrent.ExecutorService; // НОВЫЙ ИМПОРТ: для getExecutorService()

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class AdventoraBans extends JavaPlugin {

    private static AdventoraBans instance;
    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private MessageManager messageManager;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Загрузка конфигурации
        this.configManager = new ConfigManager(this);
        this.configManager.loadConfig(); // Загружает config.yml
        this.configManager.saveDefaultMessages(); // Сохраняет messages.yml, если нет

        // 2. Инициализация менеджера сообщений
        this.messageManager = new MessageManager(this); // Загружает messages.yml

        // Вывод статуса отладочного режима (Пункт 1)
        getLogger().info(messageManager.getFormattedMessage("prefix") + " Отладочный режим: " + (isDebugMode() ? "Включен" : "Выключен"));


        TimeUtil.loadTimeUnits(this); // Это остается, так как loadTimeUnits - статический метод и требует доступа к плагину
        PlayerUtil.initializeCache(); // Инициализация кэша PlayerUtil

        // 4. Настройка и подключение к базе данных
        setupDatabase();
        if (databaseManager == null || !databaseManager.isConnected()) {
            getLogger().severe(messageManager.getFormattedMessage("prefix") + " Не удалось подключиться к базе данных! Плагин отключается.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Регистрация команд
        registerCommands();this.getCommand("banlist").setExecutor(new BanListCommand(this));

        // 6. Регистрация слушателей событий
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // 7. Запуск задачи по очистке старых наказаний
        int cleanupInterval = configManager.getCleanupIntervalMinutes();
        if (cleanupInterval > 0) {
            // Запускаем асинхронно, так как работа с БД может быть долгой
            new PunishmentCleanupTask(this).runTaskTimerAsynchronously(this, 20L * 60L * cleanupInterval, 20L * 60L * cleanupInterval);
            getLogger().info(messageManager.getFormattedMessage("prefix") + " Задача по очистке старых наказаний запущена с интервалом " + cleanupInterval + " минут.");
        }

        getLogger().info(messageManager.getFormattedMessage("prefix") + " AdventoraBans успешно запущен!");
        getLogger().info(messageManager.getFormattedMessage("prefix") + " Спасибо, что используете наш плагин!");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        PlayerUtil.shutdownCache(); // Очистка кэша PlayerUtil при выключении
        getLogger().info(messageManager.getFormattedMessage("prefix") + " AdventoraBans успешно остановлен!");
    }

    private void setupDatabase() {
        String dbType = configManager.getDatabaseType().toLowerCase();
        if (dbType.equals("mysql")) {
            MySQLManager mysqlManager = new MySQLManager(this);
            mysqlManager.setConnectionDetails(
                    configManager.getMySQLHost(),
                    configManager.getMySQLPort(),
                    configManager.getMySQLDatabase(),
                    configManager.getMySQLUsername(),
                    configManager.getMySQLPassword(),
                    configManager.isMySQLUseSSL(),
                    configManager.getMySQLPoolMaximumSize(),
                    configManager.getMySQLPoolMinimumIdle(),
                    configManager.getMySQLPoolConnectionTimeout(),
                    configManager.getMySQLPoolIdleTimeout(),
                    configManager.getMySQLPoolMaxLifetime()
            );
            this.databaseManager = mysqlManager;
            getLogger().info(messageManager.getFormattedMessage("prefix") + " Выбрана база данных: MySQL.");
        } else {
            // Убедитесь, что SQLiteManager корректно инициализирует себя
            this.databaseManager = new SQLiteManager(this);
            getLogger().info(messageManager.getFormattedMessage("prefix") + " Выбрана база данных: SQLite.");
        }

        try {
            databaseManager.connect();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, messageManager.getFormattedMessage("prefix") + " Ошибка подключения к базе данных (" + dbType + "): " + e.getMessage(), e);
            getLogger().severe(messageManager.getFormattedMessage("prefix") + " Проверьте настройки в config.yml и убедитесь, что база данных запущена.");
            databaseManager = null; // Устанавливаем в null, чтобы onEnable мог проверить подключение
        }
    }

    private void registerCommands() {
        // Команды банов
        registerCommand("ban", new BanCommand(this));
        registerCommand("tempban", new TempBanCommand(this));
        registerCommand("unban", new UnbanCommand(this));

        // Команды мутов
        registerCommand("mute", new MuteCommand(this));
        registerCommand("tempmute", new TempMuteCommand(this));
        registerCommand("unmute", new UnmuteCommand(this));

        // Команды киков
        registerCommand("kick", new KickCommand(this));

        // Команды IP-банов и информации
        registerCommand("ipban", new IpBanCommand(this));
        // --- ДОБАВЛЕНА НОВАЯ КОМАНДА IPTEMPBAN ---
        registerCommand("iptempban", new IpTempBanCommand(this));
        // ------------------------------------------
        registerCommand("ipunban", new IpUnbanCommand(this));
        registerCommand("ipinfo", new IpInfoCommand(this));

        registerCommand("history", new PunishmentHistoryCommand(this));
        registerCommand("check", new PunishmentHistoryCommand(this)); // Альтернативный алиас

        // Замените ReloadCommand на ваш собственный AdventoraBansCommand
        // Вам нужно создать этот класс: AdventoraBansCommand
        registerCommand("adventorabans", new AdventoraBansCommand(this));
    }

    private void registerCommand(String commandName, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(commandName);
        if (command != null) {
            command.setExecutor(executor);
            getLogger().info(messageManager.getFormattedMessage("prefix") + " Команда /" + commandName + " зарегистрирована.");
        } else {
            getLogger().warning(messageManager.getFormattedMessage("prefix") + " Команда '" + commandName + "' не найдена в plugin.yml! Проверьте файл.");
        }
    }

    public static AdventoraBans getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    /**
     * Возвращает ExecutorService, используемый для асинхронных операций с базой данных.
     * Это позволяет другим частям плагина использовать тот же пул потоков.
     * @return ExecutorService из DatabaseManager.
     */
    public ExecutorService getExecutorService() {
        if (this.databaseManager instanceof AbstractDatabaseManager) {
            return ((AbstractDatabaseManager) this.databaseManager).getExecutor();
        }
        getLogger().warning(messageManager.getFormattedMessage("prefix") + " Запрошен ExecutorService, но databaseManager не является AbstractDatabaseManager.");
        return null; // В случае, если databaseManager не инициализирован или не того типа
    }

    /**
     * Проверяет, включен ли отладочный режим плагина. (Пункт 1)
     * @return true, если отладочный режим включен, иначе false.
     */
    public boolean isDebugMode() {
        if (configManager == null) {
            // Это должно быть инициализировано до использования, но как защитный механизм
            return false;
        }
        return configManager.isDebugModeEnabled();
    }


    /**
     * Проверяет, замучен ли игрок по его UUID.
     * Использует асинхронный запрос к базе данных и блокирует поток для получения результата.
     *
     * @param playerUuid UUID игрока.
     * @return true, если игрок замучен и мут активен, false в противном случае или при ошибке.
     */
    public boolean isPlayerMuted(UUID playerUuid) {
        if (databaseManager == null || !databaseManager.isConnected()) {
            // Если база данных недоступна, нельзя проверить мут.
            // Логируем предупреждение и считаем, что игрок не замучен, чтобы не блокировать чат совсем.
            getLogger().warning("Попытка проверить мут игрока " + playerUuid + ", но база данных недоступна.");
            return false;
        }
        try {
            // Асинхронно получаем активный мут и блокируем поток для получения результата.
            // Устанавливаем таймаут, чтобы не зависнуть, если запрос к БД будет долгим.
            return databaseManager.getActivePunishment(playerUuid, PunishmentType.MUTE)
                    .get(1, TimeUnit.SECONDS) // Ждем до 1 секунды для получения результата
                    .map(record -> !record.isExpired()) // Проверяем, существует ли запись и не истекла ли она
                    .orElse(false); // Если Optional пуст или истек, игрок не замучен
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Восстанавливаем interrupted status
            getLogger().log(Level.SEVERE, "Проверка мута для игрока " + playerUuid + " была прервана: " + e.getMessage(), e);
            return false;
        } catch (ExecutionException e) {
            // Ошибка, которая произошла в CompletableFuture (например, SQLException)
            getLogger().log(Level.SEVERE, "Ошибка выполнения запроса при проверке мута для игрока " + playerUuid + ": " + e.getCause().getMessage(), e.getCause());
            return false;
        } catch (TimeoutException e) {
            getLogger().log(Level.WARNING, "Таймаут при проверке мута для игрока " + playerUuid + ". База данных отвечает слишком медленно.", e);
            return false;
        } catch (Exception e) { // Общий перехват на всякий случай
            getLogger().log(Level.SEVERE, "Непредвиденная ошибка при проверке мута для игрока " + playerUuid + ": " + e.getMessage(), e);
            return false;
        }
    }
}