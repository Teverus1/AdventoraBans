package org.teverus.adventoraBans.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IPUtils {

    // Паттерн для извлечения только IP-адреса (без порта) из строк вида "192.168.1.1:12345"
    // Поддерживает как IPv4, так и IPv6, которые могут содержать двоеточия, но порт всегда идет после последнего двоеточия
    // для IPv4 и после квадратных скобок для IPv6 ([::1]:12345)
    private static final Pattern IP_PORT_PATTERN = Pattern.compile(
            "^(.*):(\\d+)$" // Захватывает все до последнего двоеточия как IP, и цифры после как порт
    );

    // Улучшенный паттерн для проверки IPv4 адреса
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\." +
                    "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\." +
                    "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\." +
                    "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    // Более полный и надежный паттерн для проверки IPv6 адреса
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^(" +
                    "([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|" + // 1:2:3:4:5:6:7:8
                    "([0-9a-fA-F]{1,4}:){1,7}:|" +              // 1::            1:2:3::
                    "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" + // 1::8           1:2:3::8
                    "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|" + // 1::7:8         1:2:3::7:8
                    "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|" + // 1::6:7:8       1:2:3::6:7:8
                    "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|" + // 1::5:6:7:8     1:2:3::5:6:7:8
                    "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|" + // 1::4:5:6:7:8   1:2:3::4:5:6:7:8
                    "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|" + // ::3:4:5:6:7:8
                    ":((:[0-9a-fA-F]{1,4}){1,7}|:)|" +          // ::2:3:4:5:6:7:8 or ::
                    "fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|" + // fe80::7:8%eth0
                    "::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3,3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)|" + // ::255.255.255.255 or ::ffff:255.255.255.255
                    "([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3,3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)" + // 2001:DB8::255.255.255.255
                    ")$");


    /**
     * Нормализует IP-адрес, удаляя информацию о порте, если она присутствует.
     * Например, "192.168.1.1:25565" станет "192.168.1.1".
     * "2001:0db8::1:8080" станет "2001:0db8::1".
     * "[2001:0db8::1]:8080" станет "2001:0db8::1".
     *
     * @param ipAddress Исходный IP-адрес, который может содержать порт.
     * @return Нормализованный IP-адрес без порта.
     */
    public static String normalizeIpAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return ipAddress;
        }

        // Если это IPv6-адрес, заключенный в квадратные скобки (например, [::1]:25565)
        // и содержит порт
        if (ipAddress.startsWith("[") && ipAddress.contains("]:")) {
            int closingBracket = ipAddress.indexOf("]");
            if (closingBracket != -1) { // Убедимся, что скобка закрылась
                return ipAddress.substring(1, closingBracket); // Удаляем скобки и все, что после них
            }
        }

        // Для IPv4 или IPv6 без скобок, где порт идет после последнего двоеточия
        Matcher matcher = IP_PORT_PATTERN.matcher(ipAddress);
        if (matcher.matches()) {
            // Группа 1 содержит IP-адрес
            return matcher.group(1);
        }

        // Если порт не найден, возвращаем оригинальный IP-адрес
        return ipAddress;
    }

    /**
     * Проверяет, является ли строка корректным IP-адресом (IPv4 или IPv6).
     * @param ip Строка для проверки.
     * @return true, если строка является действительным IP-адресом, иначе false.
     */
    public static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        // Проверяем на IPv4
        Matcher ipv4Matcher = IPV4_PATTERN.matcher(ip);
        if (ipv4Matcher.matches()) {
            return true;
        }
        // Проверяем на IPv6
        Matcher ipv6Matcher = IPV6_PATTERN.matcher(ip);
        return ipv6Matcher.matches();
    }
}