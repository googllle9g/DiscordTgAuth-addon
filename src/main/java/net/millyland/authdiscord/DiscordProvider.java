package net.millyland.authdiscord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.millyland.auth.api.ConfirmationRequest;
import net.millyland.auth.api.ExternalLinkInfo;
import net.millyland.auth.api.LinkInstructions;
import net.millyland.auth.api.LinkResult;
import net.millyland.auth.api.LinkSession;
import net.millyland.auth.api.LoginProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Discord login method as seen by TgAuth. Everything that is the same for every method (links in the
 * database, licence and trusted-IP checks, the Telegram/Discord choice, logging the player in) is done by TgAuth;
 * this class only proves that a player owns a Discord account (the code sent to the bot) and asks that Discord
 * account to confirm a login.
 */
final class DiscordProvider implements LoginProvider {

    static final String ID = "discord";

    private final DiscordTgAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();

    /** Codes shown in game that a Discord user has to send to the bot. Alive while the link session is active. */
    private final Map<String, LinkSession> sessionByCode = new ConcurrentHashMap<>();
    private final Map<UUID, String> codeByPlayer = new ConcurrentHashMap<>();

    DiscordProvider(DiscordTgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Discord";
    }

    @Override
    public @NotNull Component getChoiceLabel() {
        return plugin.msg().comp("choose.label");
    }

    @Override
    public @NotNull Component getChoiceHover() {
        return plugin.msg().comp("choose.hover");
    }

    @Override
    public boolean isAvailable() {
        return plugin.bot().isReady();
    }

    // ------------------------------------------------------------------ linking

    @Override
    public void beginLink(@NotNull Player player, @NotNull LinkSession session) {
        UUID uuid = player.getUniqueId();
        dropCode(uuid);

        String code;
        do {
            code = String.valueOf(100000 + random.nextInt(900000));
        } while (sessionByCode.putIfAbsent(code, session) != null);
        codeByPlayer.put(uuid, code);

        session.showInstructions(instructions(code));
    }

    private LinkInstructions instructions(String code) {
        Msg msg = plugin.msg();
        String channel = plugin.getConfig().getString("discord.link-channel-name", "link");
        List<Component> lines = new ArrayList<>();
        for (String line : msg.list("discord.instructions", "%code%", code, "%channel%", channel)) {
            lines.add(msg.withCopy(msg.fromRaw(line), code));
        }
        return new LinkInstructions(msg.comp("discord.title"), lines, inviteLink(),
                msg.withCopy(msg.comp("discord.reminder", "%code%", code, "%channel%", channel), code))
                .withCopyText(code);
    }

    private LinkInstructions.Link inviteLink() {
        String invite = plugin.getConfig().getString("discord.invite-url", "");
        if (invite == null || invite.isBlank()) return null;
        return new LinkInstructions.Link(plugin.msg().comp("discord.open-button"),
                plugin.msg().comp("discord.open-hover"), invite.trim());
    }

    /** The link session waiting for this code, or null if the code is wrong or its player is gone. Any thread. */
    LinkSession sessionForCode(String code) {
        LinkSession session = sessionByCode.get(code);
        if (session != null && !session.isActive()) {
            sessionByCode.remove(code, session);
            return null;
        }
        return session;
    }

    /** A Discord user sent a valid code: let TgAuth save the link and log the player in. Any thread. */
    CompletableFuture<LinkResult> complete(String code, long discordId, String discordName, int maxAccounts) {
        LinkSession session = sessionForCode(code);
        if (session == null) return CompletableFuture.completedFuture(LinkResult.PLAYER_GONE);
        return session.complete(String.valueOf(discordId), discordName, maxAccounts).thenApply(result -> {
            if (result == LinkResult.LINKED) dropCode(session.getPlayer().getUniqueId());
            return result;
        });
    }

    private void dropCode(UUID uuid) {
        String code = codeByPlayer.remove(uuid);
        if (code != null) sessionByCode.remove(code);
    }

    // ------------------------------------------------------------------ confirmation of a returning player

    @Override
    public void requestConfirmation(@NotNull Player player, @NotNull ExternalLinkInfo link,
                                    @NotNull ConfirmationRequest request) {
        long discordId;
        try {
            discordId = Long.parseLong(link.externalId());
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("The Discord link of " + link.minecraftName() + " has a bad id: " + link.externalId());
            return;
        }

        sendInviteButton(player);
        UUID uuid = player.getUniqueId();
        plugin.bot().sendLoginConfirm(discordId, request, player.getName(), delivered -> {
            if (delivered) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) p.sendMessage(plugin.msg().comp("login.dm-failed"));
            });
        });
    }

    private void sendInviteButton(Player player) {
        LinkInstructions.Link link = inviteLink();
        if (link == null) return;
        player.sendMessage(link.label().clickEvent(ClickEvent.openUrl(link.url()))
                .hoverEvent(HoverEvent.showText(link.hover())));
    }

    @Override
    public void onSessionEnded(@NotNull UUID playerUuid) {
        dropCode(playerUuid);
        plugin.bot().dropConfirmsFor(playerUuid);
    }
}
