package org.teverus.adventoraBans.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.teverus.adventoraBans.AdventoraBans;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.stream.Collectors;

public abstract class BaseCommand implements CommandExecutor {

    protected final AdventoraBans plugin;
    private final String permission;
    private final boolean playerOnly; // True, если команду могут выполнять только игроки

    public BaseCommand(AdventoraBans plugin, String permission, boolean playerOnly) {
        this.plugin = plugin;
        this.permission = permission;
        this.playerOnly = playerOnly;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (playerOnly && !(sender instanceof Player)) {
            // ИСПОЛЬЗУЕМ ОБНОВЛЕННЫЙ МЕССАДЖ МЕНЕДЖЕР
            sender.sendMessage(plugin.getMessageManager().getMessage("player_only_command"));
            return true;
        }

        if (!sender.hasPermission(permission)) {
            sender.sendMessage(plugin.getMessageManager().getMessage("no_permission"));
            return true;
        }

        // Логика команды будет в абстрактном методе execute
        try {
            execute(sender, args);
        } catch (CompletionException e) {
            // Ошибка, которая произошла в CompletableFuture (например, SQLException)
            plugin.getLogger().log(Level.SEVERE, "Ошибка БД при выполнении команды " + cmd.getName() + " от " + sender.getName() + ": " + e.getCause().getMessage(), e.getCause());
            sender.sendMessage(plugin.getMessageManager().getMessage("database_error"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка при выполнении команды " + cmd.getName() + " от " + sender.getName() + ": " + e.getMessage(), e);
            Map<String, String> placeholders = Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
            sender.sendMessage(plugin.getMessageManager().getMessage("error_command_execution", placeholders));
        }

        return true;
    }

    /**
     * Основная логика выполнения команды.
     * Должна быть реализована в дочерних классах.
     * @param sender Отправитель команды.
     * @param args Аргументы команды.
     */
    protected abstract void execute(CommandSender sender, String[] args);

    /**
     * Собирает причину из оставшихся аргументов, начиная с определенного индекса.
     * @param args Все аргументы команды.
     * @param startIndex Начальный индекс для причины.
     * @return Объединенная строка причины.
     */
    protected String getReason(String[] args, int startIndex) {
        if (startIndex >= args.length) {
            return "Не указана"; // ИЗМЕНЕНО: Возвращаем "Не указана" или пустую строку, но НЕ null
        }
        return Arrays.stream(args).skip(startIndex).collect(Collectors.joining(" "));
    }
}