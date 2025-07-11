package org.teverus.adventoraBans.punishments;

import java.util.UUID;

/**
 * Представляет запись о наказании (бан, мут, кик и т.д.) в базе данных.
 */
public class BanRecord { // Можно переименовать в PunishmentRecord, если хотите более общее название

    private int id; // ID записи в БД
    private UUID punishedUuid; // UUID игрока, если применимо
    private String punishedName; // Имя игрока на момент наказания (для удобства)
    private String punishedIp; // IP-ададрес, если применимо (для IP-банов)
    private UUID moderatorUuid; // UUID модератора
    private String moderatorName; // Имя модератора
    private PunishmentType punishmentType; // Тип наказания (BAN, MUTE, KICK, IP_BAN)
    private String reason; // Причина наказания
    private long banTime; // Время выдачи наказания (Unix timestamp в миллисесекундах)
    private long expireTime; // Время истечения наказания (Unix timestamp), 0 для перманентного
    private boolean active; // Активно ли наказание (для деактивации истекших или снятых)

    // Конструктор для создания новой записи перед добавлением в БД (без ID)
    public BanRecord(UUID punishedUuid, String punishedName, String punishedIp,
                     UUID moderatorUuid, String moderatorName, PunishmentType punishmentType,
                     String reason, long banTime, long expireTime, boolean active) {
        this.punishedUuid = punishedUuid;
        this.punishedName = punishedName;
        this.punishedIp = punishedIp;
        this.moderatorUuid = moderatorUuid;
        this.moderatorName = moderatorName;
        this.punishmentType = punishmentType; // <-- Здесь инициализируется
        this.reason = reason;
        this.banTime = banTime;
        this.expireTime = expireTime;
        this.active = active;
    }

    // Конструктор для загрузки записи из БД (с ID)
    public BanRecord(int id, UUID punishedUuid, String punishedName, String punishedIp,
                     UUID moderatorUuid, String moderatorName, PunishmentType punishmentType,
                     String reason, long banTime, long expireTime, boolean active) {
        this.id = id;
        this.punishedUuid = punishedUuid;
        this.punishedName = punishedName;
        this.punishedIp = punishedIp;
        this.moderatorUuid = moderatorUuid;
        this.moderatorName = moderatorName;
        this.punishmentType = punishmentType; // <-- Здесь инициализируется
        this.reason = reason;
        this.banTime = banTime;
        this.expireTime = expireTime;
        this.active = active;
    }

    // Геттеры
    public int getId() {
        return id;
    }

    public UUID getPunishedUuid() {
        return punishedUuid;
    }

    public String getPunishedName() {
        return punishedName;
    }

    public String getPunishedIp() {
        return punishedIp;
    }

    public UUID getModeratorUuid() {
        return moderatorUuid;
    }

    public String getModeratorName() {
        return moderatorName;
    }

    // Этот геттер был добавлен (или уже был), он решает ошибку getType()
    public PunishmentType getPunishmentType() {
        return punishmentType;
    }

    public String getReason() {
        return reason;
    }

    public long getBanTime() {
        return banTime;
    }

    public long getExpireTime() {
        return expireTime;
    }

    public boolean isActive() {
        return active;
    }

    // Сеттеры
    public void setId(int id) {
        this.id = id;
    }

    public void setPunishedName(String punishedName) {
        this.punishedName = punishedName;
    }

    public void setPunishedIp(String punishedIp) {
        this.punishedIp = punishedIp;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Проверяет, является ли наказание перманентным.
     * @return true, если наказание перманентное (expireTime == 0), иначе false.
     */
    public boolean isPermanent() {
        return expireTime == 0;
    }

    /**
     * Проверяет, истек ли срок наказания.
     * @return true, если наказание истекло, false в противном случае или если оно перманентное.
     */
    public boolean isExpired() {
        return !isPermanent() && System.currentTimeMillis() > expireTime;
    }

    @Override
    public String toString() {
        return "BanRecord{" +
                "id=" + id +
                ", punishedUuid=" + punishedUuid +
                ", punishedName='" + punishedName + '\'' +
                ", punishedIp='" + punishedIp + '\'' +
                ", moderatorUuid=" + moderatorUuid +
                ", moderatorName='" + moderatorName + '\'' +
                ", punishmentType=" + punishmentType +
                ", reason='" + reason + '\'' +
                ", banTime=" + banTime +
                ", expireTime=" + expireTime +
                ", active=" + active +
                '}';
    }
}