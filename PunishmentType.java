package org.teverus.adventoraBans.punishments;

/**
 * Перечисление для типов наказаний.
 */
public enum PunishmentType {
    BAN,
    TEMPBAN,
    MUTE,
    TEMPMUTE,
    KICK,
    IP_BAN,
    TEMP_IP_BAN,
    IP_UNBAN, // Не совсем наказание, но тип записи для истории
    UNBAN,    // Не совсем наказание
    UNMUTE, IPTEMPBAN;   // Не совсем наказание

    /**
     * Возвращает базовый тип наказания (для проверки активных наказаний).
     * Например, TEMPBAN относится к BAN, TEMPMUTE к MUTE.
     */
    public PunishmentType getBaseType() {
        switch (this) {
            case TEMPBAN:
                return BAN;
            case TEMPMUTE:
                return MUTE;
            case IP_UNBAN:
                return IP_BAN;
            case TEMP_IP_BAN:
                return IP_UNBAN;
            default:
                return this;
        }
    }
}