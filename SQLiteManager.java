package org.teverus.adventoraBans.database;

import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.util.IPUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.concurrent.TimeUnit; // Добавьте этот импорт

/**
 * Менеджер базы данных SQLite для AdventoraBans.
 */
public class SQLiteManager extends AbstractDatabaseManager {

    private final String databasePath;
    private static boolean driverLoaded = false; // Флаг, чтобы загружать драйвер только один раз

    public SQLiteManager(AdventoraBans plugin) {
        super(plugin);
        this.databasePath = plugin.getDataFolder().getAbsolutePath() + File.separator + "database.db";
        // Загрузка драйвера при инициализации менеджера (один раз)
        if (!driverLoaded) {
            try {
                Class.forName("org.sqlite.JDBC");
                driverLoaded = true;
                plugin.getLogger().info("SQLite JDBC драйвер успешно загружен.");
            } catch (ClassNotFoundException e) {
                plugin.getLogger().log(Level.SEVERE, "SQLite JDBC драйвер не найден! Функции БД не будут работать.", e);
                // Можно добавить флаг ошибки, чтобы предотвратить дальнейшие попытки использования БД
            }
        }
    }

    @Override
    protected Connection getConnection() throws SQLException {
        // Каждый раз возвращаем новое соединение.
        // try-with-resources в AbstractDatabaseManager будет отвечать за его закрытие.
        if (!driverLoaded) {
            throw new SQLException("SQLite JDBC драйвер не загружен. Невозможно получить соединение.");
        }
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    @Override
    public void disconnect() {
        super.disconnect(); // Завершаем пул потоков (executor) из AbstractDatabaseManager
        // В SQLiteManager больше нет поля 'connection' для явного закрытия здесь,
        // так как каждое соединение закрывается сразу после использования в try-with-resources.
        plugin.getLogger().info("Отключение от SQLite базы данных завершено (соединения закрываются по мере использования).");
    }

    @Override
    protected void createTables() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Таблица для наказаний
            String punishmentsTable = "CREATE TABLE IF NOT EXISTS punishments (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "punished_uuid TEXT NULL," + // UUID игрока (ЯВНО УКАЗАНО NULL)
                    "punished_name TEXT NULL," + // Имя игрока (на момент наказания) (ЯВНО УКАЗАНО NULL)
                    "punished_ip TEXT NULL," + // IP-адрес игрока (для IP-банов) (ЯВНО УКАЗАНО NULL)
                    "moderator_uuid TEXT," + // UUID модератора
                    "moderator_name TEXT NOT NULL," + // Имя модератора (всегда должно быть)
                    "type TEXT NOT NULL," + // Тип наказания (BAN, MUTE, KICK, IP_BAN)
                    "reason TEXT NOT NULL," + // Причина наказания
                    "ban_time INTEGER NOT NULL," + // Время выдачи (Unix timestamp)
                    "expire_time INTEGER NOT NULL," + // Время истечения (0 для перманентного)
                    "active BOOLEAN NOT NULL" + // Активно ли наказание
                    ");";
            stmt.execute(punishmentsTable);
            plugin.getLogger().info("Таблица 'punishments' проверена/создана.");

            // Таблица для хранения последних IP-адресов игроков
            String playerIpsTable = "CREATE TABLE IF NOT EXISTS player_ips (" +
                    "player_uuid TEXT PRIMARY KEY," + // UUID игрока
                    "ip_address TEXT NOT NULL," + // Последний известный IP
                    "last_seen INTEGER NOT NULL" + // Последнее время входа с этим IP
                    ");";
            stmt.execute(playerIpsTable);
            plugin.getLogger().info("Таблица 'player_ips' проверена/создана.");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось создать таблицы SQLite: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Void> savePlayerIp(UUID playerUuid, String ipAddress) {
        // Удалено: plugin.getLogger().info("DEBUG: SQLiteManager: Попытка сохранения/обновления IP для UUID " + playerUuid + ", IP: " + ipAddress);
        return CompletableFuture.runAsync(() -> {
            String normalizedIpAddress = IPUtils.normalizeIpAddress(ipAddress);
            // ON CONFLICT(player_uuid) DO UPDATE SET ... - это специфично для SQLite
            String sql = "INSERT INTO player_ips (player_uuid, ip_address, last_seen) VALUES (?, ?, ?) " +
                    "ON CONFLICT(player_uuid) DO UPDATE SET ip_address = ?, last_seen = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, normalizedIpAddress);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.setString(4, normalizedIpAddress);
                stmt.setLong(5, System.currentTimeMillis());
                // Удалено: plugin.getLogger().info("DEBUG: SQLiteManager: Выполняем SQL INSERT/UPDATE для player_ips: " + stmt.toString().replaceFirst(".*: ", ""));
                int rowsAffected = stmt.executeUpdate();
                /*
                // Удалены эти DEBUG-логи, так как они не являются критическими информационными сообщениями.
                if (rowsAffected > 0) {
                    plugin.getLogger().info("DEBUG: SQLiteManager: IP для " + playerUuid + " успешно сохранен/обновлен. Строк затронуто: " + rowsAffected);
                } else {
                    plugin.getLogger().warning("DEBUG: SQLiteManager: Сохранение/обновление IP для " + playerUuid + " не затронуло ни одной строки.");
                }
                */
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при сохранении/обновлении IP для " + playerUuid + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }
}
