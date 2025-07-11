package org.teverus.adventoraBans.database;

import org.teverus.adventoraBans.punishments.BanRecord;
import org.teverus.adventoraBans.punishments.PunishmentType;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture; // Для асинхронности

/**
 * Интерфейс для взаимодействия с базой данных для управления наказаниями.
 */
public interface DatabaseManager {

    /**
     * Подключается к базе данных и инициализирует необходимые таблицы.
     * @throws SQLException Если происходит ошибка при подключении или создании таблиц.
     */
    void connect() throws SQLException;

    /**
     * Отключается от базы данных.
     */
    void disconnect();

    /**
     * Проверяет, активно ли подключение к базе данных.
     * @return true, если подключение активно, иначе false.
     */
    boolean isConnected();

    /**
     * Асинхронно добавляет новую запись о наказании в базу данных.
     * @param record Запись о наказании.
     * @return CompletableFuture, который завершается, когда запись добавлена, или с ошибкой.
     */
    CompletableFuture<Void> addPunishment(BanRecord record);

    /**
     * Асинхронно деактивирует наказание по его ID.
     * Используется для снятия банов/мутов или деактивации истекших.
     * @param punishmentId ID записи о наказании.
     * @return CompletableFuture, который завершается, когда наказание деактивировано.
     */
    CompletableFuture<Void> deactivatePunishment(int punishmentId);

    /**
     * Асинхронно деактивирует все активные наказания определенного типа для игрока по UUID.
     * @param playerUuid UUID игрока.
     * @param type Тип наказания (например, BAN, MUTE).
     * @return CompletableFuture, который завершается, когда наказания деактивированы.
     */
    CompletableFuture<Void> deactivatePunishments(UUID playerUuid, PunishmentType type);

    /**
     * Асинхронно деактивирует все активные IP-баны для указанного IP-адреса.
     * @param ipAddress IP-адрес.
     * @return CompletableFuture, который завершается, когда наказания деактивированы.
     */
    CompletableFuture<Void> deactivateIpPunishments(String ipAddress);

    /**
     * Асинхронно получает активное наказание для игрока по UUID и типу.
     * @param playerUuid UUID игрока.
     * @param type Тип наказания (BAN или MUTE).
     * @return CompletableFuture, содержащий Optional<BanRecord> с активным наказанием, если найдено.
     */
    CompletableFuture<Optional<BanRecord>> getActivePunishment(UUID playerUuid, PunishmentType type);

    /**
     * Асинхронно получает активное наказание для IP-адреса по IP и типу.
     * @param ipAddress IP-адрес.
     * @param type Тип наказания (IP_BAN).
     * @return CompletableFuture, содержащий Optional<BanRecord> с активным наказанием, если найдено.
     */
    CompletableFuture<Optional<BanRecord>> getActivePunishment(String ipAddress, PunishmentType type);

    /**
     * Асинхронно получает всю историю наказаний для игрока по UUID.
     * @param playerUuid UUID игрока.
     * @return CompletableFuture, содержащий список записей о наказаниях.
     */
    CompletableFuture<List<BanRecord>> getPunishmentHistory(UUID playerUuid);

    /**
     * Асинхронно получает всю историю наказаний для IP-адреса.
     * @param ipAddress IP-адрес.
     * @return CompletableFuture, содержащий список записей о наказаниях.
     */
    CompletableFuture<List<BanRecord>> getPunishmentHistory(String ipAddress);

    /**
     * Асинхронно сохраняет/обновляет последний известный IP-адрес игрока.
     * @param playerUuid UUID игрока.
     * @param ipAddress IP-адрес игрока.
     * @return CompletableFuture, который завершается, когда IP-адрес сохранен.
     */
    CompletableFuture<Void> savePlayerIp(UUID playerUuid, String ipAddress);

    /**
     * Асинхронно получает последний известный IP-адрес для игрока.
     * @param playerUuid UUID игрока.
     * @return CompletableFuture, содержащий Optional<String> с IP-адресом.
     */
    CompletableFuture<Optional<String>> getLastKnownIp(UUID playerUuid);

    /**
     * Асинхронно получает UUID последнего известного игрока для данного IP-адреса.
     * @param ipAddress IP-адрес.
     * @return CompletableFuture, содержащий Optional<UUID> с UUID игрока.
     */
    CompletableFuture<Optional<UUID>> getLastKnownUuid(String ipAddress);

    /**
     * Асинхронно получает все активные наказания (для очистки по таймеру).
     * @return CompletableFuture, содержащий список всех активных наказаний.
     */
    CompletableFuture<List<BanRecord>> getAllActivePunishments();

    /**
     * Асинхронно обновляет имя игрока, связанное с UUID, в базе данных.
     * Используется для обеспечения актуальности ников в записях.
     * @param playerUuid UUID игрока.
     * @param newName Новое имя игрока.
     * @return CompletableFuture, который завершается, когда имя обновлено.
     */
    CompletableFuture<Void> updatePlayerName(UUID playerUuid, String newName);

    /**
     * Асинхронно получает последний известный UUID игрока по его имени.
     * Этот метод может использовать записи о наказаниях (punished_name) или Bukkit API.
     * @param playerName Имя игрока.
     * @return CompletableFuture, содержащий Optional<UUID> с UUID игрока, если найден.
     */
    CompletableFuture<Optional<UUID>> getLastKnownUuidByName(String playerName);

    /**
     * Асинхронно получает все наказания из базы данных с пагинацией.
     * Отсортированы по активности (активные вверху) и затем по времени выдачи (новые вверху).
     * @param limit Максимальное количество записей для возврата.
     * @param offset Смещение, с которого начинать выборку.
     * @return CompletableFuture, содержащий список BanRecord.
     */
    CompletableFuture<List<BanRecord>> getAllPunishments(int limit, int offset);

    /**
     * Асинхронно получает наказания для конкретного игрока по UUID с пагинацией.
     * Отсортированы по активности (активные вверху) и затем по времени выдачи (новые вверху).
     * @param uuid UUID игрока.
     * @param limit Максимальное количество записей для возврата.
     * @param offset Смещение, с которого начинать выборку.
     * @return CompletableFuture, содержащий список BanRecord.
     */
    CompletableFuture<List<BanRecord>> getPunishmentsByPlayerUuid(UUID uuid, int limit, int offset);

    /**
     * Асинхронно получает общее количество наказаний в базе данных.
     * @return CompletableFuture, содержащий общее количество наказаний.
     */
    CompletableFuture<Integer> getTotalPunishmentsCount();

    /**
     * Асинхронно получает количество наказаний для конкретного игрока по UUID.
     * @param uuid UUID игрока.
     * @return CompletableFuture, содержащий количество наказаний.
     */
    CompletableFuture<Integer> getPunishmentsCountByPlayerUuid(UUID uuid);

    /**
     * Асинхронно получает список активных наказаний указанных типов.
     * Используется для отображения списков активных банов/мутов.
     * @param types Список типов наказаний (например, PunishmentType.BAN, PunishmentType.IP_BAN).
     * @return CompletableFuture, содержащий список активных BanRecord.
     */
    CompletableFuture<List<BanRecord>> getActivePunishments(List<PunishmentType> types);
}