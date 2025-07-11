package org.teverus.adventoraBans.database;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;
import org.teverus.adventoraBans.util.IPUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Абстрактный базовый класс для менеджеров баз данных,
 * предоставляющий общую логику и асинхронные операции.
 */
public abstract class AbstractDatabaseManager implements DatabaseManager {

    protected final AdventoraBans plugin;
    protected ExecutorService executor; // Пул потоков для асинхронных операций с БД

    protected AbstractDatabaseManager(AdventoraBans plugin) {
        this.plugin = plugin;
        this.executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    /**
     * Возвращает ExecutorService, используемый для асинхронных операций с БД.
     * Это может быть полезно для других частей плагина, которым нужна асинхронность.
     * @return ExecutorService, используемый этим менеджером.
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * Возвращает активное соединение с базой данных.
     * Реализация этого метода должна быть в подклассах (MySQLManager, SQLiteManager)
     * и должна корректно управлять соединениями (например, через HikariCP).
     * @return Connection активное соединение с БД.
     * @throws SQLException если произошла ошибка при получении соединения.
     */
    protected abstract Connection getConnection() throws SQLException;

    /**
     * Создает необходимые таблицы в базе данных.
     * Вызывается один раз при успешном подключении.
     */
    protected abstract void createTables();

    @Override
    public boolean isConnected() {
        try (Connection connection = getConnection()) {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка при проверке подключения к БД: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void connect() throws SQLException {
        createTables();
    }

    @Override
    public void disconnect() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) { // Добавьте импорт TimeUnit
                    executor.shutdownNow();
                    plugin.getLogger().warning("Пул потоков БД не завершился в течение 5 секунд, принудительное завершение.");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                plugin.getLogger().log(Level.SEVERE, "Прерывание во время завершения пула потоков БД.", e);
            }
            plugin.getLogger().info("Пул потоков БД завершает работу.");
        }
    }

    @Override
    public CompletableFuture<Void> addPunishment(BanRecord record) {
        if (plugin.getConfigManager().isDebugModeEnabled()) { // Assumed method in AdventoraBans for config-based debug logging
            plugin.getLogger().info("DEBUG: Попытка добавить наказание для " + record.getPunishedName() + " (Тип: " + record.getPunishmentType() + ")");
        }
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO punishments (punished_uuid, punished_name, punished_ip, moderator_uuid, moderator_name, " +
                    "type, reason, ban_time, expire_time, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, record.getPunishedUuid() != null ? record.getPunishedUuid().toString() : null);
                stmt.setString(2, record.getPunishedName());
                stmt.setString(3, IPUtils.normalizeIpAddress(record.getPunishedIp()));
                stmt.setString(4, record.getModeratorUuid() != null ? record.getModeratorUuid().toString() : null);
                stmt.setString(5, record.getModeratorName());
                stmt.setString(6, record.getPunishmentType().name());
                stmt.setString(7, record.getReason());
                stmt.setLong(8, record.getBanTime());
                stmt.setLong(9, record.getExpireTime());
                stmt.setBoolean(10, record.isActive());

                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL INSERT для наказания: " + stmt.toString().replaceFirst(".*: ", ""));
                }
                int rowsAffected = stmt.executeUpdate();

                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    if (rowsAffected > 0) {
                        plugin.getLogger().info("DEBUG: Наказание для " + record.getPunishedName() + " (" + record.getPunishmentType() + ") успешно добавлено. Строк затронуто: " + rowsAffected);
                    } else {
                        plugin.getLogger().warning("DEBUG: Добавление наказания для " + record.getPunishedName() + " (" + record.getPunishmentType() + ") не затронуло ни одной строки.");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при добавлении наказания: " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deactivatePunishment(int punishmentId) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Попытка деактивировать наказание с ID: " + punishmentId);
        }
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE punishments SET active = FALSE WHERE id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, punishmentId);
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL UPDATE для деактивации наказания ID " + punishmentId + ": " + stmt.toString().replaceFirst(".*: ", ""));
                }
                int rowsAffected = stmt.executeUpdate();

                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    if (rowsAffected > 0) {
                        plugin.getLogger().info("DEBUG: Наказание с ID " + punishmentId + " деактивировано. Строк затронуто: " + rowsAffected);
                    } else {
                        plugin.getLogger().warning("DEBUG: Попытка деактивировать несуществующее наказание с ID " + punishmentId + " или уже неактивное.");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при деактивации наказания с ID " + punishmentId + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deactivatePunishments(UUID playerUuid, PunishmentType type) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Попытка деактивировать наказания типа " + type + " для UUID: " + playerUuid);
        }
        return CompletableFuture.runAsync(() -> {
            String sql;
            // Убедитесь, что логика IN ('BAN', 'TEMPBAN') корректно обрабатывается для каждого типа
            if (type == PunishmentType.BAN || type == PunishmentType.TEMPBAN) {
                sql = "UPDATE punishments SET active = FALSE WHERE punished_uuid = ? AND type IN ('BAN', 'TEMPBAN') AND active = TRUE";
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid.toString());
                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        plugin.getLogger().info("DEBUG: Выполняем SQL UPDATE для деактивации BAN/TEMPBAN для UUID " + playerUuid + ": " + stmt.toString().replaceFirst(".*: ", ""));
                    }
                    int rowsAffected = stmt.executeUpdate();

                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        if (rowsAffected > 0) {
                            plugin.getLogger().info("DEBUG: Деактивировано " + rowsAffected + " активных наказаний типа BAN/TEMPBAN для " + playerUuid);
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при деактивации BAN/TEMPBAN для " + playerUuid + ": " + e.getMessage(), e);
                    throw new CompletionException(e);
                }
            } else if (type == PunishmentType.MUTE || type == PunishmentType.TEMPMUTE) {
                sql = "UPDATE punishments SET active = FALSE WHERE punished_uuid = ? AND type IN ('MUTE', 'TEMPMUTE') AND active = TRUE";
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid.toString());
                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        plugin.getLogger().info("DEBUG: Выполняем SQL UPDATE для деактивации MUTE/TEMPMUTE для UUID " + playerUuid + ": " + stmt.toString().replaceFirst(".*: ", ""));
                    }
                    int rowsAffected = stmt.executeUpdate();

                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        if (rowsAffected > 0) {
                            plugin.getLogger().info("DEBUG: Деактивировано " + rowsAffected + " активных наказаний типа MUTE/TEMPMUTE для " + playerUuid);
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при деактивации MUTE/TEMPMUTE для " + playerUuid + ": " + e.getMessage(), e);
                    throw new CompletionException(e);
                }
            } else if (type == PunishmentType.IP_BAN) {
                sql = "UPDATE punishments SET active = FALSE WHERE punished_uuid = ? AND type = 'IP_BAN' AND active = TRUE";
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid.toString());
                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        plugin.getLogger().info("DEBUG: Выполняем SQL UPDATE для деактивации IP_BAN для UUID " + playerUuid + ": " + stmt.toString().replaceFirst(".*: ", ""));
                    }
                    int rowsAffected = stmt.executeUpdate();

                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        if (rowsAffected > 0) {
                            plugin.getLogger().info("DEBUG: Деактивировано " + rowsAffected + " активных наказаний типа IP_BAN для " + playerUuid);
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при деактивации IP_BAN для " + playerUuid + ": " + e.getMessage(), e);
                    throw new CompletionException(e);
                }
            }
            else {
                sql = "UPDATE punishments SET active = FALSE WHERE punished_uuid = ? AND type = ? AND active = TRUE";
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setString(2, type.name());
                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        plugin.getLogger().info("DEBUG: Выполняем SQL UPDATE для деактивации " + type.name() + " для UUID " + playerUuid + ": " + stmt.toString().replaceFirst(".*: ", ""));
                    }
                    int rowsAffected = stmt.executeUpdate();

                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        if (rowsAffected > 0) {
                            plugin.getLogger().info("DEBUG: Деактивировано " + rowsAffected + " активных наказаний типа " + type.name() + " для " + playerUuid); // Changed from playerManagerUuid
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при деактивации наказаний для " + playerUuid + " типа " + type + ": " + e.getMessage(), e);
                    throw new CompletionException(e);
                }
            }
        }, executor);
    }


    @Override
    public CompletableFuture<Void> deactivateIpPunishments(String ipAddress) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Попытка деактивировать IP-баны для IP: " + ipAddress);
        }
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE punishments SET active = FALSE WHERE punished_ip = ? AND type = 'IP_BAN' AND active = TRUE";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, IPUtils.normalizeIpAddress(ipAddress));
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL UPDATE для деактивации IP-банов для IP " + IPUtils.normalizeIpAddress(ipAddress) + ": " + stmt.toString().replaceFirst(".*: ", ""));
                }
                int rowsAffected = stmt.executeUpdate();

                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    if (rowsAffected > 0) {
                        plugin.getLogger().info("DEBUG: Деактивировано " + rowsAffected + " активных IP-банов для " + IPUtils.normalizeIpAddress(ipAddress));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при деактивации IP-банов для " + ipAddress + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<BanRecord>> getActivePunishments(List<PunishmentType> types) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Попытка получить список активных наказаний типов: " + types);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<BanRecord> activePunishments = new ArrayList<>();
            // Строим часть запроса для IN (...)
            StringBuilder typePlaceholders = new StringBuilder();
            for (int i = 0; i < types.size(); i++) {
                typePlaceholders.append("?");
                if (i < types.size() - 1) {
                    typePlaceholders.append(", ");
                }
            }

            String sql = "SELECT * FROM punishments WHERE type IN (" + typePlaceholders.toString() + ") AND active = TRUE AND (expire_time = 0 OR expire_time > ?) ORDER BY ban_time DESC";

            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                // Устанавливаем параметры для IN (...)
                for (int i = 0; i < types.size(); i++) {
                    pstmt.setString(i + 1, types.get(i).name());
                }
                // Устанавливаем параметр для expire_time
                pstmt.setLong(types.size() + 1, System.currentTimeMillis());

                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для активных наказаний: " + pstmt.toString().replaceFirst(".*: ", ""));
                }

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        activePunishments.add(mapResultSetToBanRecord(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении активных наказаний типов " + types + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            return activePunishments;
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<BanRecord>> getActivePunishment(UUID playerUuid, PunishmentType type) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос активного наказания для UUID: " + playerUuid + ", Тип: " + type.name());
        }
        return CompletableFuture.supplyAsync(() -> {
            String sql;
            // Убедитесь, что логика IN ('BAN', 'TEMPBAN') корректно обрабатывается для каждого типа
            if (type == PunishmentType.BAN || type == PunishmentType.TEMPBAN) {
                sql = "SELECT * FROM punishments WHERE punished_uuid = ? AND type IN ('BAN', 'TEMPBAN') AND active = TRUE AND (expire_time = 0 OR expire_time > ?) ORDER BY ban_time DESC LIMIT 1";
            } else if (type == PunishmentType.MUTE || type == PunishmentType.TEMPMUTE) {
                sql = "SELECT * FROM punishments WHERE punished_uuid = ? AND type IN ('MUTE', 'TEMPMUTE') AND active = TRUE AND (expire_time = 0 OR expire_time > ?) ORDER BY ban_time DESC LIMIT 1";
            } else if (type == PunishmentType.IP_BAN) {
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().warning("DEBUG: Запрос IP_BAN для UUID. Обычно IP_BAN проверяется по IP-адресу. Проверяем по UUID, но это может быть неверно.");
                }
                sql = "SELECT * FROM punishments WHERE punished_uuid = ? AND type = 'IP_BAN' AND active = TRUE AND (expire_time = 0 OR expire_time > ?) ORDER BY ban_time DESC LIMIT 1";
            } else {
                sql = "SELECT * FROM punishments WHERE punished_uuid = ? AND type = ? AND active = TRUE AND (expire_time = 0 OR expire_time > ?) ORDER BY ban_time DESC LIMIT 1";
            }

            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());

                if (type == PunishmentType.BAN || type == PunishmentType.TEMPBAN ||
                        type == PunishmentType.MUTE || type == PunishmentType.TEMPMUTE ||
                        type == PunishmentType.IP_BAN) {
                    stmt.setLong(2, System.currentTimeMillis());
                } else {
                    stmt.setString(2, type.name());
                    stmt.setLong(3, System.currentTimeMillis());
                }


                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для активного наказания (UUID): " + stmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        BanRecord record = readPunishmentRecord(rs);
                        if (plugin.getConfigManager().isDebugModeEnabled()) {
                            plugin.getLogger().info("DEBUG: Найдено наказание в БД для UUID " + playerUuid + ": ID=" + record.getId() + ", Тип=" + record.getPunishmentType() + ", Активно=" + record.isActive() + ", Истекает=" + (record.isPermanent() ? "НИКОГДА" : new java.util.Date(record.getExpireTime())));
                        }
                        if (record.isExpired()) {
                            if (plugin.getConfigManager().isDebugModeEnabled()) {
                                plugin.getLogger().info("DEBUG: Наказание ID " + record.getId() + " для UUID " + playerUuid + " истекло. Деактивируем и возвращаем Optional.empty().");
                            }
                            deactivatePunishment(record.getId()); // Асинхронная деактивация истекшего наказания
                            return Optional.empty();
                        }
                        if (plugin.getConfigManager().isDebugModeEnabled()) {
                            plugin.getLogger().info("DEBUG: Найдено АКТИВНОЕ наказание для UUID " + playerUuid + ": ID=" + record.getId() + ". Возвращаем Optional.of(record).");
                        }
                        return Optional.of(record);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении активного наказания для UUID " + playerUuid + " типа " + type + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Активного наказания не найдено в БД для UUID: " + playerUuid + ", Тип: " + type.name());
            }
            return Optional.empty();
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<BanRecord>> getActivePunishment(String ipAddress, PunishmentType type) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос активного наказания для IP: " + ipAddress + ", Тип: " + type.name());
        }
        return CompletableFuture.supplyAsync(() -> {
            String normalizedIpAddress = IPUtils.normalizeIpAddress(ipAddress);

            // Этот метод предназначен для проверки IP-банов.
            // Поэтому мы ожидаем PunishmentType.IP_BAN или PunishmentType.TEMP_IP_BAN.
            // Если передан другой тип, возвращаем пустой Optional.
            // Это условие проверяет, является ли тип одним из допустимых для IP-банов.
            // Если нет, то это не IP-бан, и мы возвращаем Optional.empty().
            // Важно: если вы хотите, чтобы этот метод мог проверять и другие типы (не IP),
            // это условие нужно будет пересмотреть. Но для текущей задачи оно корректно.
            if (type != PunishmentType.IP_BAN && type != PunishmentType.TEMP_IP_BAN) {
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().warning("DEBUG: getActivePunishment(String ipAddress, PunishmentType type) вызван с неподходящим типом " + type.name() + ". Этот метод предназначен только для IP_BAN или TEMP_IP_BAN.");
                }
                return Optional.empty();
            }

            // SQL-запрос для получения активного IP-наказания (IP_BAN или TEMP_IP_BAN)
            // Используем 'IN' для проверки обоих типов IP-банов.
            // Убедитесь, что 'TEMP_IP_BAN' соответствует имени в вашем enum PunishmentType.
            String sql = "SELECT * FROM punishments WHERE punished_ip = ? AND type IN ('IP_BAN', 'TEMP_IP_BAN') AND active = TRUE AND (expire_time = 0 OR expire_time > ?) ORDER BY ban_time DESC LIMIT 1";

            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, normalizedIpAddress);
                stmt.setLong(2, System.currentTimeMillis()); // Для проверки истечения временного бана

                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для активного наказания (IP): " + stmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        BanRecord record = readPunishmentRecord(rs);
                        if (plugin.getConfigManager().isDebugModeEnabled()) {
                            plugin.getLogger().info("DEBUG: Найдено наказание в БД для IP " + normalizedIpAddress + ": ID=" + record.getId() + ", Тип=" + record.getPunishmentType() + ", Активно=" + record.isActive() + ", Истекает=" + (record.isPermanent() ? "НИКОГДА" : new java.util.Date(record.getExpireTime())));
                        }
                        // Если наказание истекло, деактивируем его и не возвращаем
                        if (record.isExpired()) {
                            if (plugin.getConfigManager().isDebugModeEnabled()) {
                                plugin.getLogger().info("DEBUG: Наказание ID " + record.getId() + " для IP " + normalizedIpAddress + " истекло. Деактивируем и возвращаем Optional.empty().");
                            }
                            deactivatePunishment(record.getId()); // Асинхронная деактивация истекшего наказания
                            return Optional.empty();
                        }
                        if (plugin.getConfigManager().isDebugModeEnabled()) {
                            plugin.getLogger().info("DEBUG: Найдено АКТИВНОЕ наказание для IP " + normalizedIpAddress + ": ID=" + record.getId() + ". Возвращаем Optional.of(record).");
                        }
                        return Optional.of(record);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении активного наказания для IP " + ipAddress + " типа " + type + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Активного наказания не найдено в БД для IP: " + ipAddress + ", Тип: " + type.name());
            }
            return Optional.empty();
        }, executor);
    }

    @Override
    public CompletableFuture<List<BanRecord>> getPunishmentHistory(UUID playerUuid) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос истории наказаний для UUID: " + playerUuid);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<BanRecord> history = new ArrayList<>();
            String sql = "SELECT * FROM punishments WHERE punished_uuid = ? ORDER BY ban_time DESC";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для истории наказаний (UUID): " + stmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        history.add(mapResultSetToBanRecord(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении истории наказаний для UUID " + playerUuid + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Получено " + history.size() + " записей истории наказаний для UUID: " + playerUuid);
            }
            return history;
        }, executor);
    }

    @Override
    public CompletableFuture<List<BanRecord>> getPunishmentHistory(String ipAddress) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос истории наказаний для IP: " + ipAddress);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<BanRecord> history = new ArrayList<>();
            String sql = "SELECT * FROM punishments WHERE punished_ip = ? ORDER BY ban_time DESC";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, IPUtils.normalizeIpAddress(ipAddress));
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для истории наказаний (IP): " + stmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        history.add(mapResultSetToBanRecord(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении истории наказаний для IP " + ipAddress + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Получено " + history.size() + " записей истории наказаний для IP: " + ipAddress);
            }
            return history;
        }, executor);
    }

    @Override
    public CompletableFuture<List<BanRecord>> getAllPunishments(int limit, int offset) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос всех наказаний с LIMIT " + limit + " OFFSET " + offset);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<BanRecord> punishments = new ArrayList<>();
            String sql = "SELECT * FROM punishments ORDER BY active DESC, ban_time DESC LIMIT ? OFFSET ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, limit);
                pstmt.setInt(2, offset);
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для всех наказаний: " + pstmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        punishments.add(mapResultSetToBanRecord(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении всех наказаний: " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Получено " + punishments.size() + " всех наказаний.");
            }
            return punishments;
        }, executor);
    }

    @Override
    public CompletableFuture<List<BanRecord>> getPunishmentsByPlayerUuid(UUID uuid, int limit, int offset) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос наказаний для UUID " + uuid + " с LIMIT " + limit + " OFFSET " + offset);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<BanRecord> punishments = new ArrayList<>();
            String sql = "SELECT * FROM punishments WHERE punished_uuid = ? ORDER BY active DESC, ban_time DESC LIMIT ? OFFSET ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setInt(2, limit);
                pstmt.setInt(3, offset);
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для наказаний по UUID: " + pstmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        punishments.add(mapResultSetToBanRecord(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении наказаний для UUID " + uuid + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Получено " + punishments.size() + " наказаний для UUID " + uuid + ".");
            }
            return punishments;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> getTotalPunishmentsCount() {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос общего количества наказаний.");
        }
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM punishments";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    if (plugin.getConfigManager().isDebugModeEnabled()) {
                        plugin.getLogger().info("DEBUG: Общее количество наказаний: " + count);
                    }
                    return count;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении общего количества наказаний: " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Общее количество наказаний: 0 (ошибка или нет данных).");
            }
            return 0;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> getPunishmentsCountByPlayerUuid(UUID uuid) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос количества наказаний для UUID: " + uuid);
        }
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM punishments WHERE punished_uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для подсчета наказаний по UUID: " + pstmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        int count = rs.getInt(1);
                        if (plugin.getConfigManager().isDebugModeEnabled()) {
                            plugin.getLogger().info("DEBUG: Количество наказаний для UUID " + uuid + ": " + count);
                        }
                        return count;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении количества наказаний для UUID " + uuid + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Количество наказаний для UUID " + uuid + ": 0 (ошибка или нет данных).");
            }
            return 0;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> savePlayerIp(UUID playerUuid, String ipAddress) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Попытка сохранения/обновления IP для UUID " + playerUuid + ", IP: " + ipAddress);
        }
        return CompletableFuture.runAsync(() -> {
            throw new UnsupportedOperationException("savePlayerIp должен быть реализован в подклассах (SQLiteManager/MySQLManager)");
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<String>> getLastKnownIp(UUID playerUuid) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос последнего известного IP для UUID: " + playerUuid);
        }
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT ip_address FROM player_ips WHERE player_uuid = ? ORDER BY last_seen DESC LIMIT 1";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для последнего известного IP: " + stmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String ip = rs.getString("ip_address");
                        if (plugin.getConfigManager().isDebugModeEnabled()) {
                            plugin.getLogger().info("DEBUG: Найден последний известный IP для UUID " + playerUuid + ": " + ip);
                        }
                        return Optional.of(ip);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении последнего IP для " + playerUuid + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Последний известный IP для UUID " + playerUuid + " не найден.");
            }
            return Optional.empty();
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<UUID>> getLastKnownUuid(String ipAddress) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос последнего известного UUID для IP: " + ipAddress);
        }
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT player_uuid FROM player_ips WHERE ip_address = ? ORDER BY last_seen DESC LIMIT 1";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, IPUtils.normalizeIpAddress(ipAddress));
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для последнего известного UUID: " + stmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String uuidString = rs.getString("player_uuid");
                        if (uuidString != null) {
                            UUID uuid = UUID.fromString(uuidString);
                            if (plugin.getConfigManager().isDebugModeEnabled()) {
                                plugin.getLogger().info("DEBUG: Найден последний известный UUID для IP " + ipAddress + ": " + uuid);
                            }
                            return Optional.of(uuid);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении последнего UUID для IP " + ipAddress + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Последний известный UUID для IP " + ipAddress + " не найден.");
            }
            return Optional.empty();
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<UUID>> getLastKnownUuidByName(String playerName) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос последнего известного UUID по имени: " + playerName);
        }
        return CompletableFuture.supplyAsync(() -> {
            // 1. Попытка получить UUID через Bukkit API
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
            if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Найден UUID для " + playerName + " через Bukkit API: " + offlinePlayer.getUniqueId());
                }
                return Optional.of(offlinePlayer.getUniqueId());
            }

            // 2. Если Bukkit API не дал результат, ищем в нашей таблице punishments.
            String sqlPunishments = "SELECT punished_uuid FROM punishments WHERE punished_name = ? AND punished_uuid IS NOT NULL ORDER BY ban_time DESC LIMIT 1";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sqlPunishments)) {
                stmt.setString(1, playerName);
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для UUID по имени в punishments: " + stmt.toString().replaceFirst(".*: ", ""));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String uuidString = rs.getString("punished_uuid");
                        if (uuidString != null) {
                            UUID uuid = UUID.fromString(uuidString);
                            if (plugin.getConfigManager().isDebugModeEnabled()) {
                                plugin.getLogger().info("DEBUG: Найден UUID для " + playerName + " из таблицы punishments: " + uuid);
                            }
                            return Optional.of(uuid);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при поиске UUID по имени " + playerName + " в таблице punishments: " + e.getMessage(), e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: UUID для " + playerName + " не найден ни через Bukkit API, ни в таблице punishments.");
            }
            return Optional.empty();
        }, executor);
    }

    @Override
    public CompletableFuture<List<BanRecord>> getAllActivePunishments() {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Запрос всех активных наказаний.");
        }
        return CompletableFuture.supplyAsync(() -> {
            List<BanRecord> activePunishments = new ArrayList<>();
            String sql = "SELECT * FROM punishments WHERE active = TRUE AND (type IN ('BAN', 'TEMPBAN', 'MUTE', 'TEMPMUTE', 'IP_BAN'))";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL SELECT для всех активных наказаний: " + stmt.toString().replaceFirst(".*: ", ""));
                }
                while (rs.next()) {
                    activePunishments.add(mapResultSetToBanRecord(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при получении всех активных наказаний: " + e.getMessage(), e);
                throw new CompletionException(e);
            }
            if (plugin.getConfigManager().isDebugModeEnabled()) {
                plugin.getLogger().info("DEBUG: Получено " + activePunishments.size() + " всех активных наказаний.");
            }
            return activePunishments;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updatePlayerName(UUID playerUuid, String newName) {
        if (plugin.getConfigManager().isDebugModeEnabled()) {
            plugin.getLogger().info("DEBUG: Попытка обновить имя для UUID " + playerUuid + " на " + newName + ".");
        }
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE punishments SET punished_name = ? WHERE punished_uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, newName);
                stmt.setString(2, playerUuid.toString());
                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    plugin.getLogger().info("DEBUG: Выполняем SQL UPDATE для имени игрока: " + stmt.toString().replaceFirst(".*: ", ""));
                }
                int rowsAffected = stmt.executeUpdate();

                if (plugin.getConfigManager().isDebugModeEnabled()) {
                    if (rowsAffected > 0) {
                        plugin.getLogger().info("DEBUG: Имя игрока " + playerUuid + " обновлено на " + newName + " в таблице наказаний. Строк затронуто: " + rowsAffected);
                    } else {
                        plugin.getLogger().warning("DEBUG: Обновление имени игрока " + playerUuid + " на " + newName + " не затронуло ни одной строки.");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при обновлении имени игрока " + playerUuid + ": " + e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Преобразует ResultSet в объект BanRecord.
     * @param rs ResultSet с данными о наказании.
     * @return BanRecord объект.
     * @throws SQLException если произошла ошибка при чтении из ResultSet.
     */
    protected BanRecord mapResultSetToBanRecord(ResultSet rs) throws SQLException {
        UUID punishedUuid = null;
        String punishedUuidString = rs.getString("punished_uuid");
        if (punishedUuidString != null) {
            try {
                punishedUuid = UUID.fromString(punishedUuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Некорректный UUID в punished_uuid: " + punishedUuidString + ". ID записи: " + rs.getInt("id"), e);
            }
        }

        UUID moderatorUuid = null;
        String moderatorUuidString = rs.getString("moderator_uuid");
        if (moderatorUuidString != null) {
            try {
                moderatorUuid = UUID.fromString(moderatorUuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Некорректный UUID в moderator_uuid: " + moderatorUuidString + ". ID записи: " + rs.getInt("id"), e);
            }
        }

        return new BanRecord(
                rs.getInt("id"),
                punishedUuid,
                rs.getString("punished_name"),
                rs.getString("punished_ip"),
                moderatorUuid,
                rs.getString("moderator_name"),
                PunishmentType.valueOf(rs.getString("type")),
                rs.getString("reason"),
                rs.getLong("ban_time"),
                rs.getLong("expire_time"),
                rs.getBoolean("active")
        );
    }

    /**
     * Вспомогательный метод для чтения данных из ResultSet и создания объекта BanRecord.
     * Этот метод инкапсулирует логику преобразования строк из БД в объекты,
     * а также обрабатывает потенциально некорректные UUID.
     *
     * @param rs ResultSet, содержащий строку с данными о наказании.
     * @return Объект BanRecord, созданный из данных ResultSet.
     * @throws SQLException Если происходит ошибка доступа к данным в ResultSet.
     */
    protected BanRecord readPunishmentRecord(ResultSet rs) throws SQLException {
        UUID punishedUuid = null;
        String punishedUuidString = rs.getString("punished_uuid");
        if (punishedUuidString != null) {
            try {
                punishedUuid = UUID.fromString(punishedUuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Некорректный UUID в punished_uuid: " + punishedUuidString + ". ID записи: " + rs.getInt("id"), e);
            }
        }

        UUID moderatorUuid = null;
        String moderatorUuidString = rs.getString("moderator_uuid");
        if (moderatorUuidString != null) {
            try {
                moderatorUuid = UUID.fromString(moderatorUuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Некорректный UUID в moderator_uuid: " + moderatorUuidString + ". ID записи: " + rs.getInt("id"), e);
            }
        }

        return new BanRecord(
                rs.getInt("id"),
                punishedUuid,
                rs.getString("punished_name"),
                rs.getString("punished_ip"),
                moderatorUuid,
                rs.getString("moderator_name"),
                PunishmentType.valueOf(rs.getString("type")),
                rs.getString("reason"),
                rs.getLong("ban_time"),
                rs.getLong("expire_time"),
                rs.getBoolean("active")
        );
    }
}