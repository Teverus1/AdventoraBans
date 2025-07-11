package org.teverus.adventoraBans.tasks;

import org.bukkit.scheduler.BukkitRunnable;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.punishments.BanRecord;

import java.util.logging.Level;

public class PunishmentCleanupTask extends BukkitRunnable {

    private final AdventoraBans plugin;

    public PunishmentCleanupTask(AdventoraBans plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        plugin.getLogger().info("DEBUG: Запущена задача очистки истекших наказаний...");
        plugin.getDatabaseManager().getAllActivePunishments()
                .thenAccept(activePunishments -> {
                    long cleanedCount = 0;
                    for (BanRecord record : activePunishments) {
                        if (record.isExpired()) {
                            plugin.getDatabaseManager().deactivatePunishment(record.getId())
                                    .exceptionally(ex -> {
                                        plugin.getLogger().log(Level.SEVERE, "Ошибка при деактивации истекшего наказания ID " + record.getId() + ": " + ex.getMessage(), ex);
                                        return null;
                                    });
                            cleanedCount++;
                        }
                    }
                    if (cleanedCount > 0) {
                        plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("prefix") + " Деактивировано " + cleanedCount + " истекших наказаний.");
                    } else {
                        plugin.getLogger().info("DEBUG: Истекших наказаний не найдено для очистки.");
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка при получении списка активных наказаний для очистки: " + ex.getMessage(), ex);
                    return null;
                });
    }
}