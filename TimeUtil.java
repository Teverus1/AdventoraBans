package org.teverus.adventoraBans.util;

import org.teverus.adventoraBans.AdventoraBans;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone; // Добавлено для работы с часовыми поясами
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Утилитарный класс для работы со временем и длительностью.
 */
public class TimeUtil {

    private static final Map<String, Long> timeUnitMultipliers = new HashMap<>();
    // 'mo' (месяцы) добавлено в паттерн для корректного парсинга
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([smhdwy]|mo)");

    // Инициализация мультипликаторов временных единиц
    // Вызывается при первом доступе к классу
    public static void loadTimeUnits(AdventoraBans plugin) {
        timeUnitMultipliers.clear();
        Map<String, Object> units = plugin.getConfigManager().config.getConfigurationSection("time_units").getValues(false);
        if (units != null) {
            for (Map.Entry<String, Object> entry : units.entrySet()) {
                String alias = entry.getKey().toLowerCase();
                Object valueObj = entry.getValue();
                List<String> values = null;
                if (valueObj instanceof List) {
                    try {
                        values = (List<String>) valueObj;
                    } catch (ClassCastException e) {
                        plugin.getLogger().warning("Конфиг 'time_units." + alias + "' не является List<String>. Пропускаю.");
                        continue;
                    }
                }

                long multiplier = 0;
                switch (alias) {
                    case "s": multiplier = 1000L; break; // 1 секунда
                    case "m": multiplier = 1000L * 60L; break; // 1 минута
                    case "h": multiplier = 1000L * 60L * 60L; break; // 1 час
                    case "d": multiplier = 1000L * 60L * 60L * 24L; break; // 1 день
                    case "w": multiplier = 1000L * 60L * 60L * 24L * 7L; break; // 1 неделя
                    case "mo": multiplier = 1000L * 60L * 60L * 24L * 30L; break; // 1 месяц (приблизительно)
                    case "y": multiplier = 1000L * 60L * 60L * 24L * 365L; break; // 1 год (приблизительно)
                }
                if (multiplier > 0) {
                    timeUnitMultipliers.put(alias, multiplier);
                    if (values != null) {
                        for (String val : values) {
                            timeUnitMultipliers.put(val.toLowerCase(), multiplier);
                        }
                    }
                }
            }
        }
        plugin.getLogger().info("Загружены временные единицы: " + timeUnitMultipliers.keySet());
    }


    /**
     * Парсит строку длительности (например, "5d3h10m") в миллисекунды.
     * Поддерживаемые единицы: s (секунды), m (минуты), h (часы), d (дни), w (недели), mo (месяцы), y (годы).
     *
     * @param durationString Строка длительности.
     * @return Длительность в миллисекундах, или -1, если строка не может быть распарсена.
     */
    public static long parseDuration(String durationString) {
        if (durationString == null || durationString.isEmpty()) {
            return -1;
        }

        if (durationString.equalsIgnoreCase("perm") || durationString.equalsIgnoreCase("permanent")) {
            return 0; // 0 для перманентного наказания
        }

        long totalMillis = 0;
        Matcher matcher = DURATION_PATTERN.matcher(durationString.toLowerCase());

        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);

            Long multiplier = timeUnitMultipliers.get(unit);
            if (multiplier == null) {
                return -1; // Неизвестная единица измерения
            }
            totalMillis += value * multiplier;
        }

        return matched ? totalMillis : -1;
    }

    /**
     * Возвращает правильную форму слова для русского языка в зависимости от числа.
     * @param value Число.
     * @param form1 Единственное число (например, "день").
     * @param form2 Родительный падеж ед. числа (например, "дня" для 2, 3, 4).
     * @param form3 Родительный падеж мн. числа (например, "дней" для 0, 5-20).
     * @return Правильная форма слова.
     */
    private static String getLocalizedPluralForm(long value, String form1, String form2, String form3) {
        if (value == 1) {
            return form1;
        }
        long lastTwoDigits = value % 100;
        if (lastTwoDigits >= 11 && lastTwoDigits <= 14) {
            return form3;
        }
        long lastDigit = value % 10;
        if (lastDigit == 1) {
            return form1;
        }
        if (lastDigit >= 2 && lastDigit <= 4) {
            return form2;
        }
        return form3;
    }


    /**
     * Форматирует длительность в читаемую строку (например, "5 дней 3 часа").
     * Исключает нулевые значения и использует правильные русские склонения.
     * @param millis Длительность в миллисекундах.
     * @return Отформатированная строка длительности.
     */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            // Если длительность 0 или меньше, возвращаем сообщение о перманентности
            return AdventoraBans.getInstance().getMessageManager().getMessage("duration_permanent");
        }

        StringBuilder sb = new StringBuilder();

        long remainingMillis = millis;

        long years = TimeUnit.MILLISECONDS.toDays(remainingMillis) / 365;
        remainingMillis -= TimeUnit.DAYS.toMillis(years * 365);
        if (years > 0) {
            sb.append(years).append(" ").append(getLocalizedPluralForm(years, "год", "года", "лет")).append(" ");
        }

        long months = TimeUnit.MILLISECONDS.toDays(remainingMillis) / 30; // Приближенно, т.к. месяцы имеют разную длину
        remainingMillis -= TimeUnit.DAYS.toMillis(months * 30);
        if (months > 0) {
            sb.append(months).append(" ").append(getLocalizedPluralForm(months, "месяц", "месяца", "месяцев")).append(" ");
        }

        long weeks = TimeUnit.MILLISECONDS.toDays(remainingMillis) / 7;
        remainingMillis -= TimeUnit.DAYS.toMillis(weeks * 7);
        if (weeks > 0) {
            sb.append(weeks).append(" ").append(getLocalizedPluralForm(weeks, "неделя", "недели", "недель")).append(" ");
        }

        long days = TimeUnit.MILLISECONDS.toDays(remainingMillis);
        remainingMillis -= TimeUnit.DAYS.toMillis(days);
        if (days > 0) {
            sb.append(days).append(" ").append(getLocalizedPluralForm(days, "день", "дня", "дней")).append(" ");
        }

        long hours = TimeUnit.MILLISECONDS.toHours(remainingMillis);
        remainingMillis -= TimeUnit.HOURS.toMillis(hours);
        if (hours > 0) {
            sb.append(hours).append(" ").append(getLocalizedPluralForm(hours, "час", "часа", "часов")).append(" ");
        }

        long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis);
        remainingMillis -= TimeUnit.MINUTES.toMillis(minutes);
        if (minutes > 0) {
            sb.append(minutes).append(" ").append(getLocalizedPluralForm(minutes, "минута", "минуты", "минут")).append(" ");
        }

        long seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMillis);
        // Если нет более крупных единиц, или если остались секунды, показываем их.
        // Это предотвращает пустую строку для коротких длительностей (например, 500ms).
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append(" ").append(getLocalizedPluralForm(seconds, "секунда", "секунды", "секунд"));
        }

        String result = sb.toString().trim();
        // Если после всех расчетов строка все еще пуста (например, исходная длительность была менее 1 секунды),
        // возвращаем сообщение о "менее чем секунда".
        return result.isEmpty() ? AdventoraBans.getInstance().getMessageManager().getMessage("duration_less_than_second") : result;
    }

    /**
     * Форматирует временную метку (long, миллисекунды) в читаемую строку даты и времени,
     * используя системный часовой пояс по умолчанию.
     * @param timestamp Временная метка в миллисекундах.
     * @return Отформатированная строка даты и времени.
     */
    public static String formatTime(long timestamp) {
        return formatTime(timestamp, TimeZone.getDefault());
    }

    /**
     * Форматирует временную метку (long, миллисекунды) в читаемую строку даты и времени,
     * используя указанный часовой пояс.
     * @param timestamp Временная метка в миллисекундах.
     * @param timeZone Часовой пояс для форматирования.
     * @return Отформатированная строка даты и времени.
     */
    public static String formatTime(long timestamp, TimeZone timeZone) {
        if (timestamp <= 0) {
            // Возвращаем сообщение о том, что время не применимо, если timestamp <= 0 (например, для перманентных наказаний)
            return AdventoraBans.getInstance().getMessageManager().getMessage("not_applicable");
        }
        // SimpleDateFormat не является потокобезопасным, поэтому создаем новый экземпляр каждый раз
        // или используем ThreadLocal, если производительность критична.
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        dateFormat.setTimeZone(timeZone);
        return dateFormat.format(new Date(timestamp));
    }

    /**
     * Вычисляет оставшуюся длительность с текущего момента до указанного времени истечения.
     * @param expireTime Временная метка истечения в миллисекундах.
     * @return Отформатированная строка оставшейся длительности.
     */
    public static String getRemainingDuration(long expireTime) {
        if (expireTime == 0) {
            // Для перманентного наказания
            return AdventoraBans.getInstance().getMessageManager().getMessage("duration_permanent");
        }

        long currentTime = System.currentTimeMillis();
        long remainingMillis = expireTime - currentTime;

        if (remainingMillis <= 0) {
            // Если время уже истекло
            return AdventoraBans.getInstance().getMessageManager().getMessage("duration_expired");
        }

        // Переиспользуем улучшенный formatDuration
        return formatDuration(remainingMillis);
    }
}