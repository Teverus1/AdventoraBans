package org.teverus.adventoraBans.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.teverus.adventoraBans.AdventoraBans;
import org.teverus.adventoraBans.commands.BaseCommand;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class AdventoraBansCommand extends BaseCommand {

    public AdventoraBansCommand(AdventoraBans plugin) {
        super(plugin, "adventorabans.command.reload", true); // Требует быть игроком, т.к. может понадобиться console
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("config")) {
            // Перезагрузка только конфигов (безопасный вариант)
            sender.sendMessage(plugin.getMessageManager().getMessage("reloading_config"));
            plugin.getConfigManager().loadConfig();
            plugin.getMessageManager().reloadMessages();
            plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("Конфигурации AdventoraBans перезагружены.")); // Форматирование лога
            sender.sendMessage(plugin.getMessageManager().getMessage("config_reloaded_success"));
            return;
        }

        if (args[0].equalsIgnoreCase("plugin")) {
            // Перезагрузка всего плагина (ОПАСНО!)
            sender.sendMessage(plugin.getMessageManager().getMessage("reloading_plugin_warning"));
            plugin.getLogger().warning(plugin.getMessageManager().getFormattedMessage("Попытка горячей перезагрузки плагина AdventoraBans. Это не рекомендуется и может привести к проблемам!")); // Форматирование лога

            // Получаем доступ к менеджеру плагинов Bukkit
            JavaPlugin javaPlugin = plugin;

            try {
                // Отключаем плагин
                plugin.getServer().getPluginManager().disablePlugin(javaPlugin);
                plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("Плагин AdventoraBans отключен.")); // Форматирование лога

                // Убедимся, что все ресурсы закрыты (особенно соединения с БД)
                if (plugin.getDatabaseManager() != null) {
                    plugin.getDatabaseManager().disconnect(); // Закрываем соединение с БД
                    plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("Соединение с БД AdventoraBans закрыто.")); // Форматирование лога
                }

                // --- Попытка выгрузки ClassLoader'а (наиболее сложная и опасная часть) ---
                ClassLoader cl = javaPlugin.getClass().getClassLoader();
                if (cl instanceof URLClassLoader) {
                    URLClassLoader urlClassLoader = (URLClassLoader) cl;
                    // Очищаем кэши
                    try {
                        Field pluginField = cl.getClass().getDeclaredField("plugin");
                        pluginField.setAccessible(true);
                        pluginField.set(cl, null);
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}

                    try {
                        Field pluginInitField = cl.getClass().getDeclaredField("pluginInit");
                        pluginInitField.setAccessible(true);
                        pluginInitField.set(cl, null);
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}

                    // Закрываем URLClassLoader (не всегда срабатывает идеально)
                    try {
                        urlClassLoader.close();
                    } catch (IOException ex) {
                        plugin.getLogger().log(Level.WARNING, plugin.getMessageManager().getFormattedMessage("Ошибка при закрытии URLClassLoader для AdventoraBans: " + ex.getMessage()), ex); // Форматирование лога
                    }
                }
                // --- Конец выгрузки ClassLoader'а ---

                // ВАЖНО: Bukkit/Spigot не предоставляет прямого API для "выгрузки" плагина,
                // поэтому это очень грязный хак. Зарегистрированные Listener'ы и CommandExecutor'ы
                // могут остаться активными или быть зарегистрированы дважды.

                // Перезагружаем плагин (это приведет к его повторной инициализации через PluginManager)
                String pluginName = javaPlugin.getName();
                File pluginFile = new File("plugins", pluginName + ".jar");

                if (!pluginFile.exists()) {
                    sender.sendMessage(plugin.getMessageManager().getMessage("plugin_jar_not_found"));
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Не удалось найти JAR-файл плагина: " + pluginFile.getAbsolutePath())); // Форматирование лога
                    return;
                }

                // ОЧЕНЬ ОПАСНЫЙ ХАК для удаления плагина из внутренних карт Bukkit
                try {
                    Field pluginsField = plugin.getServer().getPluginManager().getClass().getDeclaredField("plugins");
                    pluginsField.setAccessible(true);
                    List<?> plugins = (List<?>) pluginsField.get(plugin.getServer().getPluginManager());
                    Iterator<?> it = plugins.iterator();
                    while(it.hasNext()){
                        Plugin p = (Plugin) it.next();
                        if(p.getName().equals(pluginName)){
                            it.remove();
                            break;
                        }
                    }

                    Field lookupNamesField = plugin.getServer().getPluginManager().getClass().getDeclaredField("lookupNames");
                    lookupNamesField.setAccessible(true);
                    Map<?,?> lookupNames = (Map<?,?>) lookupNamesField.get(plugin.getServer().getPluginManager());
                    lookupNames.remove(pluginName);

                    Field commandMapField = plugin.getServer().getPluginManager().getClass().getDeclaredField("commandMap");
                    commandMapField.setAccessible(true);
                    org.bukkit.command.SimpleCommandMap commandMap = (org.bukkit.command.SimpleCommandMap) commandMapField.get(plugin.getServer().getPluginManager());

                    Field knownCommandsField = org.bukkit.command.SimpleCommandMap.class.getDeclaredField("knownCommands");
                    knownCommandsField.setAccessible(true);
                    Map<String, org.bukkit.command.Command> knownCommands = (Map<String, org.bukkit.command.Command>) knownCommandsField.get(commandMap);

                    for (Iterator<Map.Entry<String, org.bukkit.command.Command>> itCommands = knownCommands.entrySet().iterator(); itCommands.hasNext(); ) {
                        Map.Entry<String, org.bukkit.command.Command> entry = itCommands.next();
                        if (entry.getValue() instanceof org.bukkit.command.PluginCommand) {
                            org.bukkit.command.PluginCommand cmd = (org.bukkit.command.PluginCommand) entry.getValue();
                            if (cmd.getPlugin().getName().equals(pluginName)) {
                                cmd.unregister(commandMap); // Отменить регистрацию команды
                                itCommands.remove();
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Критическая ошибка при попытке выгрузки плагина из внутренних структур Bukkit. Возможны утечки и конфликты!"), e); // Форматирование лога
                }


                // Перезагружаем плагин
                Plugin newPlugin = plugin.getServer().getPluginManager().loadPlugin(pluginFile);
                if (newPlugin != null) {
                    plugin.getServer().getPluginManager().enablePlugin(newPlugin);
                    sender.sendMessage(plugin.getMessageManager().getMessage("plugin_reloaded_success"));
                    plugin.getLogger().info(plugin.getMessageManager().getFormattedMessage("Плагин AdventoraBans успешно перезагружен.")); // Форматирование лога
                } else {
                    sender.sendMessage(plugin.getMessageManager().getMessage("plugin_reload_failed"));
                    plugin.getLogger().severe(plugin.getMessageManager().getFormattedMessage("Не удалось перезагрузить плагин AdventoraBans.")); // Форматирование лога
                }

            } catch (Exception e) {
                sender.sendMessage(plugin.getMessageManager().getMessage("plugin_reload_error", Map.of("error", e.getMessage())));
                plugin.getLogger().log(Level.SEVERE, plugin.getMessageManager().getFormattedMessage("Ошибка при перезагрузке плагина AdventoraBans: " + e.getMessage()), e); // Форматирование лога
            }
        } else {
            sender.sendMessage(plugin.getMessageManager().getMessage("reload_usage"));
        }
    }
}