package org.teverus.adventoraBans.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern; // Добавил импорт для Pattern

/**
 * Утилитарный класс для асинхронного получения OfflinePlayer и работы с UUID/именами.
 */
public class PlayerUtil {

    private static final ExecutorService playerLookupExecutor = Executors.newCachedThreadPool(); // Пул для неблокирующих операций
    private static final ConcurrentHashMap<String, UUID> nameToUuidCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> uuidToNameCache = new ConcurrentHashMap<>();

    // Регулярное выражение для проверки валидности UUID
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"); //

    // Загрузка кэша при старте плагина
    public static void initializeCache() {
        // Можно загрузить известные UUID/имена из БД здесь при старте, если нужно,
        // но для начала это не обязательно, кэш будет заполняться по мере запросов.
        // Bukkit.getOfflinePlayers() загружает всех известных игроков, что может быть медленно.
        // Лучше использовать отдельные запросы к вашей БД.
    }

    // Очистка кэша при остановке плагина
    public static void shutdownCache() {
        playerLookupExecutor.shutdown();
    }


    /**
     * Асинхронно получает OfflinePlayer по имени.
     * Сначала проверяет онлайн игроков, затем кэш, затем Bukkit/Mojang (блокирующий вызов).
     * @param playerName Имя игрока.
     * @return CompletableFuture, содержащий Optional<OfflinePlayer>.
     */
    public static CompletableFuture<Optional<OfflinePlayer>> getOfflinePlayerAsync(String playerName) {
        // 1. Проверяем онлайн игроков (быстро)
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            return CompletableFuture.completedFuture(Optional.of(onlinePlayer));
        }

        // 2. Проверяем кэш (быстро)
        if (nameToUuidCache.containsKey(playerName.toLowerCase())) {
            return CompletableFuture.completedFuture(Optional.of(Bukkit.getOfflinePlayer(nameToUuidCache.get(playerName.toLowerCase()))));
        }

        // 3. Асинхронно выполняем потенциально блокирующий вызов Bukkit.getOfflinePlayer
        // (который в свою очередь может обращаться к Mojang API или локальному файлу usercache.json)
        return CompletableFuture.supplyAsync(() -> {
            @SuppressWarnings("deprecation") // getOfflinePlayer(String) deprecated, но необходим для поиска по имени
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);

            // getUniqueId() для OfflinePlayer может быть null, если игрока не существует.
            // Также, если Mojang API недоступен, он может вернуть фейковый OfflinePlayer с пустым UUID.
            // Добавлена проверка на isValidUUID(offlinePlayer.getUniqueId().toString()) для более строгой валидации
            if (offlinePlayer != null && offlinePlayer.getUniqueId() != null && isValidUUID(offlinePlayer.getUniqueId().toString())) { //
                nameToUuidCache.put(playerName.toLowerCase(), offlinePlayer.getUniqueId());
                uuidToNameCache.put(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                return Optional.of(offlinePlayer);
            }
            return Optional.empty();
        }, playerLookupExecutor);
    }

    /**
     * Асинхронно получает OfflinePlayer по UUID.
     * @param uuid UUID игрока.
     * @return CompletableFuture, содержащий Optional<OfflinePlayer>.
     */
    public static CompletableFuture<Optional<OfflinePlayer>> getOfflinePlayerAsync(UUID uuid) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            return CompletableFuture.completedFuture(Optional.of(onlinePlayer));
        }

        return CompletableFuture.supplyAsync(() -> Optional.ofNullable(Bukkit.getOfflinePlayer(uuid)), playerLookupExecutor);
    }

    /**
     * Получает имя игрока по UUID из кэша или синхронно через Bukkit.
     * Используйте, когда асинхронность не критична или когда имя, скорее всего, уже известно.
     * @param uuid UUID игрока.
     * @return Имя игрока или null, если не найдено.
     */
    public static String getPlayerNameSync(UUID uuid) {
        return uuidToNameCache.computeIfAbsent(uuid, k -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(k);
            return player.getName();
        });
    }

    /**
     * Проверяет, является ли строка валидным UUID.
     * @param uuidString Строка для проверки.
     * @return true, если строка является валидным UUID, иначе false.
     */
    public static boolean isValidUUID(String uuidString) {
        if (uuidString == null) {
            return false;
        }
        return UUID_PATTERN.matcher(uuidString).matches();
    }
}