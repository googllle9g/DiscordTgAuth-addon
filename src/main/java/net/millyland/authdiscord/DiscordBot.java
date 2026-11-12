package net.millyland.authdiscord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.millyland.auth.api.ConfirmationRequest;
import net.millyland.auth.api.LinkResult;
import net.millyland.auth.api.LinkSession;
import org.bukkit.Bukkit;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The Discord side: the "Link" panel, DM code entry and login confirmation buttons. */
final class DiscordBot {

    private static final String BTN_LINK = "tgd:link";
    private static final String BTN_OK = "tgd:ok:";
    private static final String BTN_NO = "tgd:no:";

    private record PendingConfirm(ConfirmationRequest request, UUID uuid, long discordId, long expiresAt) {
    }

    private static final class Throttle {
        int wrong;
        long lockedUntil;
    }

    private final DiscordTgAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();

    private volatile JDA jda;
    private final AdminPanel adminPanel;

    /** Discord users who pressed the button and are expected to send their code (user id -> expiry). */
    private final Map<Long, Long> awaitingCode = new ConcurrentHashMap<>();
    private final Map<String, PendingConfirm> pendingConfirms = new ConcurrentHashMap<>();
    private final Map<Long, Throttle> throttles = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastHint = new ConcurrentHashMap<>();

    DiscordBot(DiscordTgAuthPlugin plugin) {
        this.plugin = plugin;
        this.adminPanel = new AdminPanel(plugin);
    }

    boolean isReady() {
        JDA j = jda;
        return j != null && j.getStatus() == JDA.Status.CONNECTED;
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        String token = plugin.getConfig().getString("discord.bot-token", "");
        if (token == null || token.isBlank()) {
            plugin.getLogger().severe("discord.bot-token is empty in config.yml - Discord login is disabled "
                    + "and players will get the normal Telegram link code.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JDABuilder builder = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.DIRECT_MESSAGES))
                        .addEventListeners(new Listener());
                ProxySupport.Choice proxy = ProxySupport.resolve(plugin, plugin.api());
                if (proxy != null) {
                    ProxySupport.apply(builder, proxy.proxy());
                    plugin.getLogger().info("The Discord bot connects through the proxy " + proxy.proxy().describe()
                            + " (from " + proxy.source() + ").");
                }
                JDA built = builder.build();
                built.awaitReady();
                this.jda = built;
                plugin.getLogger().info("Discord bot connected as " + built.getSelfUser().getName() + ".");
                ensurePanel(false);
                ensureInvite();
                adminPanel.ensurePanel(built, false);
            } catch (Exception e) {
                plugin.getLogger().severe("Could not start the Discord bot: " + e
                        + " (check discord.bot-token and the proxy.* settings). Players will get the normal Telegram link code.");
            }
        });
    }

    /** Posts the admin panel message again (/tgdiscord adminpanel). */
    boolean postAdminPanel() {
        JDA j = jda;
        if (j == null || adminPanel.channelId().isEmpty()) return false;
        adminPanel.ensurePanel(j, true);
        return true;
    }

    void shutdown() {
        JDA j = jda;
        jda = null;
        if (j != null) {
            j.shutdown();
        }
    }

    // ------------------------------------------------------------------ invite link

    /**
     * If {@code discord.invite-url} is empty and the bot has the "Create Invite" permission, creates a permanent
     * invite and writes it into config.yml.
     */
    void ensureInvite() {
        String current = plugin.getConfig().getString("discord.invite-url", "");
        if (current != null && !current.isBlank()) return;
        JDA j = jda;
        if (j == null) return;

        TextChannel channel = findInviteChannel(j);
        if (channel == null) {
            plugin.getLogger().info("discord.invite-url is empty and the bot has no \"Create Invite\" permission "
                    + "(or is on no server), so no invite was created. Give it the permission or set the URL by hand.");
            return;
        }
        channel.createInvite().setMaxAge(0).setMaxUses(0).setTemporary(false).setUnique(false).queue(
                invite -> Bukkit.getScheduler().runTask(plugin, () -> saveInvite(invite.getUrl())),
                error -> plugin.getLogger().warning("Could not create a Discord invite: " + error));
    }

    private TextChannel findInviteChannel(JDA j) {
        String channelId = plugin.getConfig().getString("discord.link-channel-id", "");
        if (channelId != null && !channelId.isBlank()) {
            TextChannel linkChannel = j.getTextChannelById(channelId.trim());
            if (linkChannel != null && canInvite(linkChannel)) return linkChannel;
        }
        Guild guild = null;
        String guildId = plugin.getConfig().getString("discord.guild-id", "");
        if (guildId != null && !guildId.isBlank()) guild = j.getGuildById(guildId.trim());
        if (guild == null && j.getGuilds().size() == 1) guild = j.getGuilds().get(0);
        if (guild == null) return null;
        for (TextChannel candidate : guild.getTextChannels()) {
            if (canInvite(candidate)) return candidate;
        }
        return null;
    }

    private boolean canInvite(TextChannel channel) {
        return channel.getGuild().getSelfMember().hasPermission(channel, Permission.CREATE_INSTANT_INVITE);
    }

    /** Writes the invite into config.yml, keeping the file's comments, then reloads the config. Main thread. */
    private void saveInvite(String url) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(file.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).matches("\\s+invite-url:.*")) {
                    lines.set(i, "  invite-url: \"" + url + "\"");
                    replaced = true;
                    break;
                }
            }
            if (replaced) {
                java.nio.file.Files.write(file.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
                plugin.reloadConfig();
            } else {
                plugin.getConfig().set("discord.invite-url", url);
                plugin.saveConfig();
            }
            plugin.getLogger().info("Created a permanent Discord invite and saved it to config.yml: " + url);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Created the invite " + url + " but could not write config.yml (" + e
                    + "). Set discord.invite-url by hand.");
        }
    }

    // ------------------------------------------------------------------ the panel in the link channel

    /**
     * Makes sure the channel contains the message with the "Link" button.
     *
     * @param forceNew post a fresh panel even if the old one still exists
     */
    void ensurePanel(boolean forceNew) {
        JDA j = jda;
        String channelId = plugin.getConfig().getString("discord.link-channel-id", "");
        if (j == null || channelId == null || channelId.isBlank()) {
            if (j != null) {
                plugin.getLogger().warning("discord.link-channel-id is empty: the bot cannot post the "
                        + "\"Привязать\" button. Set it and run /tgdiscord panel.");
            }
            return;
        }

        TextChannel channel = j.getTextChannelById(channelId.trim());
        if (channel == null) {
            plugin.getLogger().warning("Discord channel " + channelId + " was not found (is the bot on that server "
                    + "and does it see the channel?).");
            return;
        }

        if (forceNew) {
            postPanel(channel);
            return;
        }
        // Nothing is stored: look for our own panel among the latest messages of the channel.
        long self = j.getSelfUser().getIdLong();
        channel.getHistory().retrievePast(50).queue(messages -> {
            boolean found = messages.stream().anyMatch(m ->
                    m.getAuthor().getIdLong() == self && m.getButtonById(BTN_LINK) != null);
            if (!found) postPanel(channel);
        }, error -> plugin.getLogger().warning("Could not read the link channel history (does the bot have "
                + "\"Read Message History\"?): " + error));
    }

    private void postPanel(TextChannel channel) {
        channel.sendMessage(plugin.msg().raw("bot.panel"))
                .setComponents(ActionRow.of(Button.primary(BTN_LINK, plugin.msg().raw("bot.panel-button"))))
                .queue(message -> { },
                        error -> plugin.getLogger().warning("Could not post the link panel: " + error));
    }

    // ------------------------------------------------------------------ login confirmation

    /** Sends "is this you?" with buttons to the Discord user's DMs. The callback gets true if it was delivered. */
    void sendLoginConfirm(long discordId, ConfirmationRequest request, String playerName,
                          Consumer<Boolean> delivered) {
        JDA j = jda;
        if (j == null) {
            delivered.accept(false);
            return;
        }

        String token = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        long ttl = Math.max(10, request.getSecondsLeft()) * 1000L;
        pendingConfirms.put(token, new PendingConfirm(request, request.getPlayer().getUniqueId(), discordId,
                System.currentTimeMillis() + ttl));

        String ip = request.getIp() == null ? "?" : request.getIp();
        String text = plugin.msg().raw("bot.confirm", "%player%", playerName, "%ip%", ip);
        Button yes = Button.success(BTN_OK + token, plugin.msg().raw("bot.confirm-yes"));
        Button no = Button.danger(BTN_NO + token, plugin.msg().raw("bot.confirm-no"));

        j.retrieveUserById(discordId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> channel.sendMessage(text).setComponents(ActionRow.of(yes, no)).queue(
                                sent -> delivered.accept(true),
                                error -> failConfirm(token, delivered)),
                        error -> failConfirm(token, delivered)),
                error -> failConfirm(token, delivered));
    }

    private void failConfirm(String token, Consumer<Boolean> delivered) {
        pendingConfirms.remove(token);
        delivered.accept(false);
    }

    void dropConfirmsFor(UUID uuid) {
        pendingConfirms.values().removeIf(c -> c.uuid().equals(uuid));
    }

    // ------------------------------------------------------------------ JDA events

    private final class Listener extends ListenerAdapter {

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            String id = event.getComponentId();
            try {
                if (AdminPanel.owns(id)) {
                    adminPanel.onButton(event);
                } else if (id.equals(BTN_LINK)) {
                    onLinkButton(event);
                } else if (id.startsWith(BTN_OK)) {
                    onConfirmButton(event, id.substring(BTN_OK.length()), true);
                } else if (id.startsWith(BTN_NO)) {
                    onConfirmButton(event, id.substring(BTN_NO.length()), false);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Error handling a Discord button: " + e);
            }
        }

        @Override
        public void onModalInteraction(ModalInteractionEvent event) {
            if (!AdminPanel.owns(event.getModalId())) return;
            try {
                adminPanel.onModal(event);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Error handling a Discord form: " + e);
            }
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.isFromGuild() || event.getAuthor().isBot()) return;
            try {
                onDirectMessage(event);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Error handling a Discord message: " + e);
            }
        }
    }

    private void onLinkButton(ButtonInteractionEvent event) {
        Msg msg = plugin.msg();
        String guildId = plugin.getConfig().getString("discord.guild-id", "");
        if (event.getGuild() == null
                || (guildId != null && !guildId.isBlank() && !guildId.trim().equals(event.getGuild().getId()))) {
            event.reply(msg.raw("bot.wrong-server")).setEphemeral(true).queue();
            return;
        }

        long userId = event.getUser().getIdLong();
        awaitingCode.put(userId, System.currentTimeMillis() + 10 * 60_000L);

        event.deferReply(true).queue();
        event.getUser().openPrivateChannel().queue(
                channel -> channel.sendMessage(msg.raw("bot.dm-prompt")).queue(
                        ok -> event.getHook().sendMessage(msg.raw("bot.dm-sent")).queue(),
                        error -> event.getHook().sendMessage(msg.raw("bot.dm-closed")).queue()),
                error -> event.getHook().sendMessage(msg.raw("bot.dm-closed")).queue());
    }

    private void onDirectMessage(MessageReceivedEvent event) {
        Msg msg = plugin.msg();
        long userId = event.getAuthor().getIdLong();
        String content = event.getMessage().getContentRaw().trim();
        long now = System.currentTimeMillis();

        Long until = awaitingCode.get(userId);
        if (until == null || until < now) {
            awaitingCode.remove(userId);
            Long last = lastHint.get(userId);
            if (last == null || now - last > 30_000L) {
                lastHint.put(userId, now);
                String channel = plugin.getConfig().getString("discord.link-channel-name", "link");
                event.getChannel().sendMessage(msg.raw("bot.need-button", "%channel%", channel)).queue();
            }
            return;
        }

        Throttle throttle = throttles.computeIfAbsent(userId, k -> new Throttle());
        synchronized (throttle) {
            if (throttle.lockedUntil > now) {
                long left = (throttle.lockedUntil - now) / 1000L + 1;
                event.getChannel().sendMessage(msg.raw("bot.too-many", "%seconds%", String.valueOf(left))).queue();
                return;
            }
        }

        String code = content.replaceAll("\\s+", "");
        LinkSession waiting = code.matches("\\d{6}") ? plugin.provider().sessionForCode(code) : null;
        if (waiting == null) {
            synchronized (throttle) {
                throttle.wrong++;
                int max = Math.max(1, plugin.getConfig().getInt("login.max-wrong-codes", 5));
                if (throttle.wrong >= max) {
                    throttle.wrong = 0;
                    throttle.lockedUntil = now + plugin.getConfig().getInt("login.wrong-code-lockout-seconds", 300) * 1000L;
                }
            }
            event.getChannel().sendMessage(msg.raw("bot.bad-code")).queue();
            return;
        }

        int limit = Math.max(1, plugin.getConfig().getInt("discord.max-accounts-per-discord", 1));
        String discordName = event.getAuthor().getName();
        String playerName = waiting.getPlayer().getName();

        // TgAuth stores the link, checks the limit and logs the player in.
        plugin.provider().complete(code, userId, discordName, limit).thenAccept(result -> {
            String reply = switch (result) {
                case LINKED -> msg.raw("bot.linked", "%player%", playerName);
                case LIMIT_REACHED -> msg.raw("bot.limit", "%limit%", String.valueOf(limit));
                case ALREADY_LINKED, PLAYER_GONE -> msg.raw("bot.player-gone");
                case ERROR -> msg.raw("bot.error");
            };
            if (result == LinkResult.LINKED) {
                awaitingCode.remove(userId);
                synchronized (throttle) {
                    throttle.wrong = 0;
                }
            }
            event.getChannel().sendMessage(reply).queue();
        });
    }

    private void onConfirmButton(ButtonInteractionEvent event, String token, boolean approve) {
        Msg msg = plugin.msg();
        PendingConfirm confirm = pendingConfirms.get(token);

        if (confirm == null || confirm.expiresAt() < System.currentTimeMillis()) {
            pendingConfirms.remove(token);
            event.editMessage(msg.raw("bot.expired")).setComponents(Collections.emptyList()).queue();
            return;
        }
        if (confirm.discordId() != event.getUser().getIdLong()) {
            event.reply(msg.raw("bot.not-yours")).setEphemeral(true).queue();
            return;
        }

        pendingConfirms.remove(token);
        event.editMessage(msg.raw(approve ? "bot.confirmed" : "bot.rejected"))
                .setComponents(Collections.emptyList()).queue();

        if (approve) {
            confirm.request().approve();
        } else {
            confirm.request().deny();
        }
    }
}
