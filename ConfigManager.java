package org.teverus.adventoraBans.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.teverus.adventoraBans.AdventoraBans;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.logging.Level;

public class ConfigManager {

    private final AdventoraBans plugin;
    public FileConfiguration config;
    private File configFile;
    private File messagesFile;
    private FileConfiguration messagesConfig;

    public ConfigManager(AdventoraBans plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        plugin.getLogger().info("Конфигурация config.yml загружена.");
    }

    public void saveDefaultMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        plugin.getLogger().info("Файл messages.yml загружен/создан.");
    }

    public String getDatabaseType() {
        return config.getString("database.type", "SQLITE");
    }

    // MySQL Settings
    public String getMySQLHost() {
        return config.getString("database.mysql.host", "localhost");
    }

    public int getMySQLPort() {
        return config.getInt("database.mysql.port", 3306);
    }

    public String getMySQLDatabase() {
        return config.getString("database.mysql.database", "adventorabans");
    }

    public String getMySQLUsername() {
        return config.getString("database.mysql.username", "user");
    }

    public String getMySQLPassword() {
        return config.getString("database.mysql.password", "password");
    }

    public boolean isMySQLUseSSL() {
        return config.getBoolean("database.mysql.use_ssl", false);
    }

    // HikariCP Settings
    public int getMySQLPoolMaximumSize() {
        return config.getInt("database.mysql.pool.maximum_pool_size", 10);
    }

    public int getMySQLPoolMinimumIdle() {
        return config.getInt("database.mysql.pool.minimum_idle", 5);
    }

    public long getMySQLPoolConnectionTimeout() {
        return config.getLong("database.mysql.pool.connection_timeout", 30000);
    }

    public long getMySQLPoolIdleTimeout() {
        return config.getLong("database.mysql.pool.idle_timeout", 600000);
    }

    public long getMySQLPoolMaxLifetime() {
        return config.getLong("database.mysql.pool.max_lifetime", 1800000);
    }

    // General Settings
    public boolean shouldBroadcastPunishments() {
        return config.getBoolean("settings.broadcast_punishments", true);
    }

    public List<String> getAllowedMutedCommands() {
        return config.getStringList("settings.allowed_muted_commands");
    }

    public int getCleanupIntervalMinutes() {
        return config.getInt("settings.cleanup_interval_minutes", 60);
    }

    public FileConfiguration getMessagesConfig() {
        if (messagesConfig == null) {
            messagesFile = new File(plugin.getDataFolder(), "messages.yml");
            messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
            // Look for defaults in the jar
            InputStream defConfigStream = plugin.getResource("messages.yml");
            if (defConfigStream != null) {
                messagesConfig.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream)));
            }
        }
        return messagesConfig;
    }

    /**
     * Проверяет, включен ли отладочный режим в конфиге.
     * @return true, если отладочный режим включен, иначе false.
     */
    public boolean isDebugModeEnabled() {
        return config.getBoolean("debug_mode", false); // По умолчанию false
    }
}