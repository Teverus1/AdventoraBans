package org.teverus.adventoraBans.messages;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.teverus.adventoraBans.AdventoraBans;

import java.io.File;
import java.util.Map;
import java.util.logging.Level; // Import for logging

public class MessageManager {

    private final AdventoraBans plugin;
    private FileConfiguration messagesConfig;
    private String cachedPrefix; // Для кэширования отформатированного префикса
    private File messagesFile; // Ссылка на файл messages.yml

    public MessageManager(AdventoraBans plugin) {
        this.plugin = plugin;
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml"); // Инициализируем File здесь
        loadMessages(); // Загружаем сообщения при инициализации
    }

    // Приватный метод для загрузки/перезагрузки сообщений
    private void loadMessages() {
        // Убедимся, что messages.yml существует. Если нет, он будет скопирован из JAR.
        // Это более безопасно, чем просто plugin.saveResource("messages.yml", false),
        // так как ConfigManager уже сохраняет дефолтные сообщения при старте.
        // Здесь мы просто загружаем его.
        this.messagesConfig = YamlConfiguration.loadConfiguration(this.messagesFile);

        // Обновляем кэшированный префикс после загрузки конфигурации
        this.cachedPrefix = ChatColor.translateAlternateColorCodes('&', messagesConfig.getString("prefix", "&8[&cAdventoraBans&8] &r"));
    }

    /**
     * Получает сообщение из messages.yml и применяет цветовые коды.
     * Автоматически включает префикс плагина, если сообщение его содержит.
     * @param path Путь к сообщению в messages.yml
     * @return Отформатированное сообщение
     */
    public String getMessage(String path) {
        // Получаем сырую строку сообщения.
        // Fallback на английский, если сообщение не найдено (или если messages.yml поврежден)
        String message = messagesConfig.getString(path, "&cMessage not found: '" + path + "' in messages.yml. Please check your configuration.");

        // Заменяем %prefix% на отформатированный префикс и применяем цветовые коды
        // ChatColor.translateAlternateColorCodes уже обрабатывает '&'
        return ChatColor.translateAlternateColorCodes('&', message.replace("%prefix%", getFormattedPrefix()));
    }

    /**
     * Получает сообщение из messages.yml, применяет цветовые коды и заменяет плейсхолдеры.
     * Автоматически включает префикс плагина, если сообщение его содержит.
     * @param path Путь к сообщению в messages.yml
     * @param placeholders Карта плейсхолдеров (ключ -> значение)
     * @return Отформатированное сообщение
     */
    public String getMessage(String path, Map<String, String> placeholders) {
        // Сначала получаем базовое сообщение с префиксом и цветами, используя getMessage(String path)
        String message = getMessage(path);
        // Затем заменяем специфичные плейсхолдеры
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String placeholderKey = "%" + entry.getKey() + "%";
            String placeholderValue = entry.getValue();

            // *** ИСПРАВЛЕНИЕ: Обработка null значений для плейсхолдеров ***
            if (placeholderValue == null) {
                plugin.getLogger().warning("Плейсхолдер " + placeholderKey + " имеет значение NULL для сообщения: '" + path + "'. Использую 'N/A'.");
                placeholderValue = "N/A"; // Заменяем null на "N/A" или пустую строку, чтобы избежать NullPointerException
            }
            // ***************************************************************

            // Используем replaceAll для замены всех вхождений плейсхолдера
            // replaceAll ожидает регулярное выражение, поэтому экранируем "%"
            message = message.replaceAll(java.util.regex.Pattern.quote(placeholderKey), placeholderValue);
        }
        return message;
    }

    /**
     * Получает отформатированный префикс плагина.
     * Предпочтительно использовать этот метод для получения префикса,
     * так как он возвращает уже отформатированную и закэшированную строку.
     * @return Отформатированный префикс
     */
    public String getFormattedPrefix() {
        return cachedPrefix;
    }

    /**
     * Форматирует произвольную строку, применяя цветовые коды.
     * Этот метод НЕ добавляет префикс автоматически. Используйте его для форматирования
     * частей сообщений или сообщений без префикса.
     * @param rawString Сырая строка для форматирования.
     * @return Отформатированная строка.
     */
    public String colorize(String rawString) {
        return ChatColor.translateAlternateColorCodes('&', rawString);
    }


    /**
     * Форматирует произвольную строку, применяя цветовые коды и добавляя префикс плагина в начало.
     * Используется для сообщений, которые не хранятся в messages.yml, но должны иметь стандартный префикс и форматирование.
     * @param rawMessage Сырое сообщение для форматирования.
     * @return Отформатированное сообщение с префиксом.
     */
    public String getFormattedMessage(String rawMessage) {
        // Вы можете решить, должен ли этот метод также заменять %prefix%.
        // Исходя из вашего использования, кажется, что он просто добавляет префикс к любой строке.
        return getFormattedPrefix() + ChatColor.translateAlternateColorCodes('&', rawMessage);
    }


    /**
     * Перезагружает конфигурацию сообщений из файла messages.yml.
     * Обновляет кэшированный префикс.
     * Должен вызываться после изменения messages.yml на диске.
     */
    public void reloadMessages() {
        if (!messagesFile.exists()) {
            plugin.getLogger().warning("messages.yml не найден. Создаю новый...");
            plugin.saveResource("messages.yml", true); // Копируем дефолтный файл, перезаписывая его, если уже есть
        }

        try {
            this.messagesConfig = YamlConfiguration.loadConfiguration(this.messagesFile);
            // Важно: обновить кэшированный префикс после перезагрузки
            this.cachedPrefix = ChatColor.translateAlternateColorCodes('&', messagesConfig.getString("prefix", "&8[&cAdventoraBans&8] &r"));
            plugin.getLogger().info(getFormattedPrefix() + " Сообщения плагина успешно перезагружены из messages.yml.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, getFormattedPrefix() + " Ошибка при перезагрузке messages.yml: " + e.getMessage(), e);
        }
    }
}