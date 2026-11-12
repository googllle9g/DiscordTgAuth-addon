package net.millyland.authdiscord;

import net.millyland.auth.api.TgAuth;
import net.millyland.auth.api.TgAuthAPI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Discord login for TgAuth. It only registers a {@link DiscordProvider} with TgAuth's API: the links live in
 * TgAuth's database and TgAuth does the choice menu, the licence/trusted-IP checks and the login itself.
 */
public final class DiscordTgAuthPlugin extends JavaPlugin {

    private TgAuthAPI api;
    private Msg msg;
    private DiscordBot bot;
    private DiscordProvider provider;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Settings added by a plugin update are merged into the existing config.yml (comments included).
        if (YamlMerger.mergeMissingKeys(this, "config.yml", new java.io.File(getDataFolder(), "config.yml"))) {
            reloadConfig();
        }

        try {
            api = TgAuth.get();
        } catch (IllegalStateException e) {
            getLogger().severe("TgAuth is not enabled - disabling DiscordTgAuth.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (api.getApiVersion() < 4) {
            getLogger().severe("This TgAuth is too old (API version " + api.getApiVersion()
                    + "). DiscordTgAuth needs a TgAuth with API version 4 or newer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        msg = new Msg(this);
        bot = new DiscordBot(this);
        provider = new DiscordProvider(this);
        if (!api.registerLoginProvider(this, provider)) {
            getLogger().severe("TgAuth refused the \"discord\" login provider (another plugin registered it?).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AdminCommand admin = new AdminCommand();
        getCommand("tgdiscord").setExecutor(admin);
        getCommand("tgdiscord").setTabCompleter(admin);

        bot.start();
        getLogger().info("DiscordTgAuth enabled.");
    }

    @Override
    public void onDisable() {
        if (api != null && provider != null) api.unregisterLoginProvider(this, DiscordProvider.ID);
        if (bot != null) bot.shutdown();
        ProxySupport.release();
    }

    TgAuthAPI api() {
        return api;
    }

    Msg msg() {
        return msg;
    }

    DiscordBot bot() {
        return bot;
    }

    DiscordProvider provider() {
        return provider;
    }

    /** /tgdiscord panel | reload. Unlinking and link info are TgAuth's /tgauth unlink and /tgauth userinfo. */
    private final class AdminCommand implements TabExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("tgdiscord.admin")) {
                sender.sendMessage("You don't have permission.");
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage("Usage: /tgdiscord <panel|adminpanel|reload>");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "panel" -> {
                    if (!bot.isReady()) {
                        sender.sendMessage("The Discord bot is not connected.");
                    } else {
                        bot.ensurePanel(true);
                        sender.sendMessage("Posted a new link panel in the configured channel.");
                    }
                }
                case "adminpanel" -> {
                    if (!bot.isReady()) {
                        sender.sendMessage("The Discord bot is not connected.");
                    } else if (!bot.postAdminPanel()) {
                        sender.sendMessage("Set discord.admin-channel-id in config.yml first.");
                    } else {
                        sender.sendMessage("Posted a new admin panel in the configured admin channel.");
                    }
                }
                case "reload" -> {
                    reloadConfig();
                    msg.load();
                    sender.sendMessage("Config and language file reloaded (the bot token is only read at startup).");
                }
                default -> sender.sendMessage("Usage: /tgdiscord <panel|adminpanel|reload>");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return args.length == 1 ? List.of("panel", "adminpanel", "reload") : List.of();
        }
    }
}
