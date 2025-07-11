package org.teverus.adventoraBans.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.util.IPUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.concurrent.TimeUnit; // Добавьте этот импорт

/**
 * Менеджер базы данных MySQL для AdventoraBans с использованием HikariCP.
 */
public class MySQLManager extends AbstractDatabaseManager {

    private HikariDataSource dataSource;
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;
    private boolean useSSL;

    // HikariCP Pool settings
    private int maximumPoolSize;
    private int minimumIdle;
    private long connectionTimeout;
    private long idleTimeout;
    private long maxLifetime;


    public MySQLManager(AdventoraBans plugin) {
        super(plugin);
    }

    public void setConnectionDetails(String host, int port, String database, String username, String password,
                                     boolean useSSL, int maximumPoolSize, int minimumIdle,
                                     long connectionTimeout, long idleTimeout, long maxLifetime) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.useSSL = useSSL;
        this.maximumPoolSize = maximumPoolSize;
        this.minimumIdle = minimumIdle;
        this.connectionTimeout = connectionTimeout;
        this.idleTimeout = idleTimeout;
        this.maxLifetime = maxLifetime;
    }

    @Override
    public void connect() throws SQLException {
        if (dataSource != null && !dataSource.isClosed()) {
            // Удалено: plugin.getLogger().info("DEBUG: HikariCP DataSource уже активен.");
            return; // Уже подключены
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?autoReconnect=true"); // Добавил autoReconnect
        config.setUsername(username);
        config.setPassword(password);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useSSL", String.valueOf(useSSL));
        config.addDataSourceProperty("allowPublicKeyRetrieval", "true"); // Важно для MySQL 8+ и старых драйверов
        config.setPoolName("AdventoraBans-HikariCP"); // Добавил имя пула для лучшей отладки
        config.setConnectionTestQuery("SELECT 1"); // Важно для проверки живости соединения

        // HikariCP Pool settings
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);

        /*
        // Удалены эти DEBUG-логи, так как они не являются критическими информационными сообщениями.
        plugin.getLogger().info("DEBUG: Попытка инициализации HikariCP с параметрами: " +
                "Host=" + host + ", Port=" + port + ", Database=" + database +
                ", User=" + username + ", UseSSL=" + useSSL +
                ", MaxPoolSize=" + maximumPoolSize + ", MinIdle=" + minimumIdle +
                ", ConnTimeout=" + connectionTimeout + ", IdleTimeout=" + idleTimeout +
                ", MaxLifetime=" + maxLifetime);
        */

        try {
            dataSource = new HikariDataSource(config);
            // Проверка соединения сразу после инициализации
            try (Connection testConn = dataSource.getConnection()) {
                if (testConn.isValid(1)) { // Проверяем, что соединение валидно в течение 1 секунды
                    plugin.getLogger().info("Подключение к MySQL установлено через HikariCP и соединение валидно.");
                } else {
                    plugin.getLogger().log(Level.WARNING, "Подключение к MySQL установлено, но тестовое соединение невалидно.");
                }
            }
            createTables(); // Создаем таблицы после успешного подключения
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка при подключении к MySQL: " + e.getMessage(), e);
            throw new SQLException("Ошибка при инициализации HikariCP.", e);
        }
    }

    @Override
    protected Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            // Удалено: plugin.getLogger().warning("DEBUG: Запрос соединения, когда HikariDataSource не инициализирован или закрыт.");
            throw new SQLException("HikariDataSource не инициализирован или закрыт.");
        }
        return dataSource.getConnection();
    }

    @Override
    public void disconnect() {
        super.disconnect(); // Завершаем пул потоков executor
        if (dataSource != null) {
            // Удалено: plugin.getLogger().info("DEBUG: Закрытие HikariCP DataSource.");
            dataSource.close();
            plugin.getLogger().info("Отключение от MySQL базы данных завершено.");
        }
    }

    @Override
    protected void createTables() {
        // Удалено: plugin.getLogger().info("DEBUG: Попытка создания/проверки таблиц MySQL.");
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Таблица для наказаний
            String punishmentsTable = "CREATE TABLE IF NOT EXISTS punishments (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "punished_uuid VARCHAR(36) NULL," + // UUID игрока (ЯВНО УКАЗАНО NULL)
                    "punished_name VARCHAR(16) NULL," + // Имя игрока (на момент наказания) (ЯВНО УКАЗАНО NULL)
                    "punished_ip VARCHAR(45) NULL," + // IP-адрес игрока (поддержка IPv6) (ЯВНО УКАЗАНО NULL)
                    "moderator_uuid VARCHAR(36)," + // UUID модератора
                    "moderator_name VARCHAR(16) NOT NULL," + // Имя модератора
                    "type VARCHAR(20) NOT NULL," + // Тип наказания (BAN, MUTE, KICK, IP_BAN)
                    "reason VARCHAR(255) NOT NULL," + // Причина наказания
                    "ban_time BIGINT NOT NULL," + // Время выдачи (Unix timestamp в мс)
                    "expire_time BIGINT NOT NULL," + // Время истечения (0 для перманентного)
                    "active BOOLEAN NOT NULL," + // Активно ли наказание
                    "INDEX idx_punished_uuid (punished_uuid, type, active)," + // Индекс для быстрого поиска активных наказаний по UUID
                    "INDEX idx_punished_ip (punished_ip, type, active)," + // Индекс для быстрого поиска активных наказаний по IP
                    "INDEX idx_active_type (active, type)" + // Индекс для поиска всех активных наказаний (для очистки)
                    ");";
            stmt.execute(punishmentsTable);
            plugin.getLogger().info("Таблица 'punishments' проверена/создана в MySQL.");

            // Таблица для хранения последних IP-адресов игроков
            String playerIpsTable = "CREATE TABLE IF NOT EXISTS player_ips (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY," + // UUID игрока
                    "ip_address VARCHAR(45) NOT NULL," + // Последний известный IP (поддержка IPv6)
                    "last_seen BIGINT NOT NULL" + // Последнее время входа с этим IP
                    ");";
            stmt.execute(playerIpsTable);
            plugin.getLogger().info("Таблица 'player_ips' проверена/создана в MySQL.");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось создать таблицы MySQL: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Void> savePlayerIp(UUID playerUuid, String ipAddress) {
        // Удалено: plugin.getLogger().info("DEBUG: MySQLManager: Попытка сохранения/обновления IP для UUID " + playerUuid + ", IP: " + ipAddress);
        return CompletableFuture.runAsync(() -> {
            String normalizedIpAddress = IPUtils.normalizeIpAddress(ipAddress);
            String sql = "INSERT INTO player_ips (player_uuid, ip_address, last_seen) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE ip_address = VALUES(ip_address), last_seen = VALUES(last_seen)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, normalizedIpAddress);
                stmt.setLong(3, System.currentTimeMillis());
                // Для MySQL ON DUPLICATE KEY UPDATE не требует повторного указания параметров для VALUES()
                // Они автоматически ссылаются на значения из INSERT части.
                /*
                // Удалены эти DEBUG-логи, так как они не являются критическими информационными сообщениями.
                plugin.getLogger().info("DEBUG: MySQLManager: Выполняем SQL INSERT/UPDATE для player_ips: " + stmt.toString().replaceFirst(".*: ", ""));
                int rowsAffected = stmt.executeUpdate();
                if (rowsAffected > 0) {
                    plugin.getLogger().info("DEBUG: MySQLManager: IP для " + playerUuid + " успешно сохранен/обновлен. Строк затронуто: " + rowsAffected);
                } else {
                    plugin.getLogger().warning("DEBUG: MySQLManager: Сохранение/обновление IP для " + playerUuid + " не затронуло ни одной строки.");
                }
                */
                stmt.executeUpdate(); // Выполнение запроса без логирования rowsAffected
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при сохранении/обновлении IP для " + playerUuid + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }
}
