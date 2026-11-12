package net.millyland.authdiscord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.millyland.auth.api.AdminApi;
import net.millyland.auth.api.AdminPinApi;
import net.millyland.auth.api.AdminPlayerInfo;
import net.millyland.auth.api.AdminResult;
import net.millyland.auth.api.ExternalLinkInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The Discord admin panel: the same tools as TgAuth's Telegram {@code /admin} panel (find a player, kick, ban, warn,
 * unban, remove premium, unlink), all through TgAuth's {@link AdminApi}. Everything is answered privately
 * (ephemeral) and the reason and the duration are typed into a Discord form (modal) instead of the next message.
 *
 * <p>Who may use it: Discord ids in {@code discord.admin-ids}, and every Discord user whose linked game account
 * has TgAuth's {@code tgauth.admin} permission. The check runs when the panel is opened and is remembered for
 * {@code discord.admin-session-minutes}, because a form must be answered within three seconds.
 *
 * <p>Then comes TgAuth's admin PIN (when {@code admin-pin.enabled} in TgAuth): a keypad of buttons whose entered
 * digits are shown only as dots. The first time the person sets a PIN (typed twice). The PIN, the wrong-guess
 * lockout, the alerts and the unlocked session all live in TgAuth ({@link AdminApi#pin()}).
 */
final class AdminPanel {

    static final String BTN_OPEN = "tgd:adm:open";
    private static final String P = "tgd:adm:";

    private static final String NS = DiscordProvider.ID;

    private enum PinStep { VERIFY, SET1, SET2 }

    private static final class PinEntry {
        PinStep step;
        final StringBuilder digits = new StringBuilder();
        String first;

        PinEntry(PinStep step) {
            this.step = step;
        }
    }

    private final DiscordTgAuthPlugin plugin;
    private final Map<Long, PinEntry> pinEntries = new ConcurrentHashMap<>();

    /** Discord user id -> until when they count as verified admins. */
    private final Map<Long, Long> adminUntil = new ConcurrentHashMap<>();

    AdminPanel(DiscordTgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    static boolean owns(String componentId) {
        return componentId.startsWith(P);
    }

    private Msg msg() {
        return plugin.msg();
    }

    private AdminApi admin() {
        return plugin.api().admin();
    }

    String channelId() {
        String id = plugin.getConfig().getString("discord.admin-channel-id", "");
        return id == null ? "" : id.trim();
    }

    // ------------------------------------------------------------------ the panel message

    /** Posts the "Open admin panel" message in the admin channel unless it is already there. */
    void ensurePanel(JDA j, boolean forceNew) {
        String channelId = channelId();
        if (channelId.isEmpty()) return;
        TextChannel channel = j.getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("Discord admin channel " + channelId + " was not found (is the bot on that "
                    + "server and does it see the channel?).");
            return;
        }
        if (forceNew) {
            post(channel);
            return;
        }
        long self = j.getSelfUser().getIdLong();
        channel.getHistory().retrievePast(50).queue(messages -> {
            boolean found = messages.stream().anyMatch(m ->
                    m.getAuthor().getIdLong() == self && m.getButtonById(BTN_OPEN) != null);
            if (!found) post(channel);
        }, error -> plugin.getLogger().warning("Could not read the admin channel history: " + error));
    }

    private void post(TextChannel channel) {
        channel.sendMessage(msg().raw("admin.panel"))
                .setComponents(ActionRow.of(Button.primary(BTN_OPEN, msg().raw("admin.panel-button"))))
                .queue(m -> { }, error -> plugin.getLogger().warning("Could not post the admin panel: " + error));
    }

    // ------------------------------------------------------------------ access

    private Set<Long> configuredIds() {
        return plugin.getConfig().getStringList("discord.admin-ids").stream()
                .map(s -> {
                    try {
                        return Long.parseLong(s.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** The person was verified as an administrator recently (the PIN is a separate step). */
    private boolean hasIdentity(long userId) {
        if (configuredIds().contains(userId)) return true;
        Long until = adminUntil.get(userId);
        return until != null && until > System.currentTimeMillis();
    }

    private boolean pinUnlocked(long userId) {
        AdminPinApi pin = admin().pin();
        return !pin.isEnabled() || pin.isUnlocked(NS, String.valueOf(userId));
    }

    private boolean hasAccess(long userId) {
        return hasIdentity(userId) && pinUnlocked(userId);
    }

    private CompletableFuture<Boolean> checkAdmin(long userId) {
        if (configuredIds().contains(userId)) return CompletableFuture.completedFuture(true);
        return plugin.api().getExternalLinks(DiscordProvider.ID, String.valueOf(userId)).thenCompose(links -> {
            CompletableFuture<Boolean> result = CompletableFuture.completedFuture(false);
            for (ExternalLinkInfo link : links) {
                result = result.thenCompose(found -> found ? CompletableFuture.completedFuture(true)
                        : admin().isAdmin(link.uuid()));
            }
            return result;
        });
    }

    private static String actor(net.dv8tion.jda.api.entities.User user) {
        return "Discord " + user.getName();
    }

    // ------------------------------------------------------------------ buttons

    void onButton(ButtonInteractionEvent event) {
        String rest = event.getComponentId().substring(P.length());
        long userId = event.getUser().getIdLong();

        if (rest.equals("open")) {
            open(event);
            return;
        }
        String[] a = rest.split(":");
        if (a[0].equals("k")) {
            if (a.length == 2 && hasIdentity(userId)) onKey(event, a[1]);
            else event.reply(msg().raw("admin.expired")).setEphemeral(true).queue();
            return;
        }
        if (!hasAccess(userId)) {
            event.reply(msg().raw("admin.expired")).setEphemeral(true).queue();
            return;
        }
        switch (a[0]) {
            case "find" -> event.replyModal(findModal()).queue();
            case "lock" -> {
                admin().pin().lock(NS, String.valueOf(userId));
                adminUntil.remove(userId);
                event.editMessage(msg().raw("admin.locked")).setComponents(List.of()).queue();
            }
            case "cancel" -> event.editMessage(msg().raw("admin.cancelled")).setComponents(List.of()).queue();
            case "kick", "ban", "warn" -> {
                if (a.length == 2) event.replyModal(reasonModal(a[0], a[1])).queue();
            }
            case "unban" -> {
                if (a.length == 2) run(event, uuid(a[1]), "unban", u -> admin().unban(u, actor(event.getUser())));
            }
            case "unprem" -> {
                if (a.length == 2) run(event, uuid(a[1]), "unprem", u -> admin().clearPremium(u, actor(event.getUser())));
            }
            case "ultg" -> {
                if (a.length == 2) confirmUnlink(event, uuid(a[1]), "tg");
            }
            case "ulp" -> {
                if (a.length == 3) confirmUnlink(event, uuid(a[2]), a[1]);
            }
            case "cf" -> {
                if (a.length == 3) {
                    UUID target = uuid(a[2]);
                    String which = a[1];
                    event.deferEdit().queue();
                    if (target == null) return;
                    CompletableFuture<AdminResult> call = which.equals("tg")
                            ? admin().unlinkTelegram(target, actor(event.getUser()))
                            : admin().unlinkProvider(target, which, actor(event.getUser()));
                    call.whenComplete((r, e) -> event.getHook()
                            .editOriginal(resultText("unlink", r, e)).setComponents(List.of()).queue());
                }
            }
            default -> event.reply(msg().raw("admin.expired")).setEphemeral(true).queue();
        }
    }

    private void open(ButtonInteractionEvent event) {
        String guildId = plugin.getConfig().getString("discord.guild-id", "");
        if (event.getGuild() == null
                || (guildId != null && !guildId.isBlank() && !guildId.trim().equals(event.getGuild().getId()))) {
            event.reply(msg().raw("bot.wrong-server")).setEphemeral(true).queue();
            return;
        }
        long userId = event.getUser().getIdLong();
        event.deferReply(true).queue();
        checkAdmin(userId).whenComplete((admin, error) -> {
            if (error != null || !Boolean.TRUE.equals(admin)) {
                event.getHook().sendMessage(msg().raw("admin.no-access")).queue();
                return;
            }
            long minutes = Math.max(1, plugin.getConfig().getLong("discord.admin-session-minutes", 10));
            adminUntil.put(userId, System.currentTimeMillis() + minutes * 60_000L);

            AdminPinApi pin = admin().pin();
            String subject = String.valueOf(userId);
            if (!pin.isEnabled() || pin.isUnlocked(NS, subject)) {
                event.getHook().sendMessage(msg().raw("admin.menu")).setComponents(menuRows()).queue();
                return;
            }
            pin.hasPin(NS, subject).thenAccept(has -> {
                PinEntry entry = new PinEntry(has ? PinStep.VERIFY : PinStep.SET1);
                pinEntries.put(userId, entry);
                event.getHook().sendMessage(pinText(entry, null)).setComponents(keypadRows()).queue();
            });
        });
    }

    private List<ActionRow> menuRows() {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.primary(P + "find", msg().raw("admin.find-button")));
        if (admin().pin().isEnabled()) buttons.add(Button.secondary(P + "lock", msg().raw("admin.lock-button")));
        return List.of(ActionRow.of(buttons));
    }

    // ------------------------------------------------------------------ PIN keypad

    private List<ActionRow> keypadRows() {
        List<Button> first = new ArrayList<>();
        List<Button> second = new ArrayList<>();
        for (int d = 1; d <= 5; d++) first.add(Button.secondary(P + "k:" + d, String.valueOf(d)));
        for (int d = 6; d <= 9; d++) second.add(Button.secondary(P + "k:" + d, String.valueOf(d)));
        second.add(Button.secondary(P + "k:0", "0"));
        return List.of(ActionRow.of(first), ActionRow.of(second), ActionRow.of(
                Button.secondary(P + "k:del", "⌫"),
                Button.success(P + "k:ok", "✓"),
                Button.danger(P + "k:cancel", "✖")));
    }

    private String pinText(PinEntry entry, String note) {
        AdminPinApi pin = admin().pin();
        String mask = entry.digits.length() == 0 ? "-" : "•".repeat(entry.digits.length());
        String key = switch (entry.step) {
            case VERIFY -> "admin.pin-enter";
            case SET1 -> "admin.pin-set";
            case SET2 -> "admin.pin-repeat";
        };
        String text = msg().raw(key, "%mask%", mask, "%min%", String.valueOf(pin.minLength()),
                "%max%", String.valueOf(pin.maxLength()));
        return note == null ? text : note + "\n" + text;
    }

    private void onKey(ButtonInteractionEvent event, String key) {
        long userId = event.getUser().getIdLong();
        PinEntry entry = pinEntries.get(userId);
        AdminPinApi pin = admin().pin();
        if (entry == null) {
            event.reply(msg().raw("admin.expired")).setEphemeral(true).queue();
            return;
        }

        switch (key) {
            case "cancel" -> {
                pinEntries.remove(userId);
                event.editMessage(msg().raw("admin.cancelled")).setComponents(List.of()).queue();
            }
            case "del" -> {
                if (entry.digits.length() > 0) entry.digits.setLength(entry.digits.length() - 1);
                event.editMessage(pinText(entry, null)).setComponents(keypadRows()).queue();
            }
            case "ok" -> submitPin(event, userId, entry);
            default -> {
                if (key.length() == 1 && Character.isDigit(key.charAt(0)) && entry.digits.length() < pin.maxLength()) {
                    entry.digits.append(key);
                }
                event.editMessage(pinText(entry, null)).setComponents(keypadRows()).queue();
            }
        }
    }

    private void submitPin(ButtonInteractionEvent event, long userId, PinEntry entry) {
        AdminPinApi pin = admin().pin();
        String subject = String.valueOf(userId);
        String typed = entry.digits.toString();
        if (!pin.isValidFormat(typed)) {
            event.editMessage(pinText(entry, msg().raw("admin.pin-short", "%min%", String.valueOf(pin.minLength()),
                    "%max%", String.valueOf(pin.maxLength())))).setComponents(keypadRows()).queue();
            return;
        }
        entry.digits.setLength(0);
        event.deferEdit().queue();
        var hook = event.getHook();

        switch (entry.step) {
            case SET1 -> {
                entry.first = typed;
                entry.step = PinStep.SET2;
                hook.editOriginal(pinText(entry, null)).setComponents(keypadRows()).queue();
            }
            case SET2 -> {
                if (!typed.equals(entry.first)) {
                    entry.step = PinStep.SET1;
                    entry.first = null;
                    hook.editOriginal(pinText(entry, msg().raw("admin.pin-mismatch"))).setComponents(keypadRows()).queue();
                    return;
                }
                pin.setPin(NS, subject, typed).whenComplete((ok, error) -> {
                    pinEntries.remove(userId);
                    if (error != null || !Boolean.TRUE.equals(ok)) {
                        hook.editOriginal(msg().raw("admin.pin-failed")).setComponents(List.of()).queue();
                    } else {
                        hook.editOriginal(msg().raw("admin.pin-saved") + "\n" + msg().raw("admin.menu"))
                                .setComponents(menuRows()).queue();
                    }
                });
            }
            case VERIFY -> pin.verify(NS, subject, typed).whenComplete((check, error) -> {
                if (error != null || check == null) {
                    pinEntries.remove(userId);
                    hook.editOriginal(msg().raw("admin.failed")).setComponents(List.of()).queue();
                    return;
                }
                switch (check.status()) {
                    case OK -> {
                        pinEntries.remove(userId);
                        hook.editOriginal(msg().raw("admin.menu")).setComponents(menuRows()).queue();
                    }
                    case WRONG -> hook.editOriginal(pinText(entry, msg().raw("admin.pin-wrong",
                            "%left%", String.valueOf(check.attemptsLeft())))).setComponents(keypadRows()).queue();
                    case LOCKED -> {
                        pinEntries.remove(userId);
                        hook.editOriginal(msg().raw("admin.pin-locked",
                                "%time%", pin.formatDuration(check.lockSeconds()))).setComponents(List.of()).queue();
                    }
                    case NO_PIN -> {
                        entry.step = PinStep.SET1;
                        hook.editOriginal(pinText(entry, null)).setComponents(keypadRows()).queue();
                    }
                }
            });
        }
    }

    /** An action without a form: answer privately with the result. */
    private void run(ButtonInteractionEvent event, UUID target, String action,
                     java.util.function.Function<UUID, CompletableFuture<AdminResult>> call) {
        event.deferReply(true).queue();
        if (target == null) {
            event.getHook().sendMessage(msg().raw("admin.not-found")).queue();
            return;
        }
        call.apply(target).whenComplete((r, e) -> event.getHook().sendMessage(resultText(action, r, e)).queue());
    }

    private void confirmUnlink(ButtonInteractionEvent event, UUID target, String which) {
        event.deferReply(true).queue();
        if (target == null) {
            event.getHook().sendMessage(msg().raw("admin.not-found")).queue();
            return;
        }
        admin().getPlayer(target).thenAccept(info -> {
            String name = info.map(AdminPlayerInfo::name).orElse("?");
            String what = which.equals("tg") ? "Telegram" : which;
            event.getHook().sendMessage(msg().raw("admin.confirm-unlink", "%player%", name, "%method%", what))
                    .setComponents(ActionRow.of(
                            Button.danger(P + "cf:" + which + ":" + target, msg().raw("admin.confirm-button")),
                            Button.secondary(P + "cancel", msg().raw("admin.cancel-button"))))
                    .queue();
        });
    }

    // ------------------------------------------------------------------ forms (modals)

    private Modal modal(String id, String title, TextInput... inputs) {
        Modal.Builder builder = Modal.create(id, title);
        for (TextInput input : inputs) builder.addActionRow(input);
        return builder.build();
    }

    private Modal findModal() {
        TextInput name = TextInput.create("name", msg().raw("admin.find-label"), TextInputStyle.SHORT)
                .setRequired(true).setMinLength(1).setMaxLength(32)
                .setPlaceholder(msg().raw("admin.find-placeholder")).build();
        return modal(P + "m:find", msg().raw("admin.find-title"), name);
    }

    private Modal reasonModal(String action, String uuid) {
        TextInput reason = TextInput.create("reason", msg().raw("admin.reason-label"), TextInputStyle.PARAGRAPH)
                .setRequired(true).setMinLength(1).setMaxLength(200)
                .setPlaceholder(msg().raw("admin.reason-placeholder")).build();
        String title = msg().raw("admin.modal-title-" + action);
        if (action.equals("kick")) return modal(P + "m:kick:" + uuid, title, reason);

        TextInput duration = TextInput.create("duration", msg().raw("admin.duration-label"), TextInputStyle.SHORT)
                .setRequired(false).setMaxLength(12)
                .setPlaceholder(msg().raw("admin.duration-placeholder")).build();
        return modal(P + "m:" + action + ":" + uuid, title, reason, duration);
    }

    void onModal(ModalInteractionEvent event) {
        String[] a = event.getModalId().substring(P.length()).split(":");   // m, action[, uuid]
        long userId = event.getUser().getIdLong();
        if (a.length < 2 || !a[0].equals("m")) return;
        if (!hasAccess(userId)) {
            event.reply(msg().raw("admin.expired")).setEphemeral(true).queue();
            return;
        }

        if (a[1].equals("find")) {
            String name = value(event, "name");
            event.deferReply(true).queue();
            admin().findPlayer(name).whenComplete((info, e) -> {
                if (e != null || info.isEmpty()) {
                    event.getHook().sendMessage(msg().raw("admin.not-found")).queue();
                } else {
                    sendView(event.getHook(), info.get());
                }
            });
            return;
        }

        UUID target = a.length == 3 ? uuid(a[2]) : null;
        if (target == null) {
            event.reply(msg().raw("admin.not-found")).setEphemeral(true).queue();
            return;
        }
        String reason = value(event, "reason");
        String duration = value(event, "duration");
        if (a[1].equals("kick")) {
            event.deferReply(true).queue();
            admin().kick(target, reason, actor(event.getUser()))
                    .whenComplete((r, e) -> event.getHook().sendMessage(resultText("kick", r, e)).queue());
            return;
        }
        if (!duration.isEmpty() && !AdminApi.isValidDuration(duration)) {
            event.reply(msg().raw("admin.invalid-duration", "%value%", duration)).setEphemeral(true).queue();
            return;
        }
        String dur = duration.isEmpty() ? null : duration;
        event.deferReply(true).queue();
        CompletableFuture<AdminResult> call = a[1].equals("ban")
                ? admin().ban(target, reason, dur, actor(event.getUser()))
                : admin().warn(target, reason, dur, actor(event.getUser()));
        call.whenComplete((r, e) -> event.getHook().sendMessage(resultText(a[1], r, e)).queue());
    }

    private static String value(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        return mapping == null ? "" : mapping.getAsString().trim();
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ views and results

    private String resultText(String action, AdminResult r, Throwable error) {
        if (error != null || r == null) return msg().raw("admin.failed");
        return switch (r.outcome()) {
            case DONE -> msg().raw("admin.done-" + action);
            case NOT_ONLINE -> msg().raw("admin.not-online");
            case NOT_FOUND -> msg().raw("admin.not-found");
            case INVALID_DURATION -> msg().raw("admin.invalid-duration", "%value%", "?");
            case FAILED -> msg().raw("admin.failed");
        };
    }

    private void sendView(net.dv8tion.jda.api.interactions.InteractionHook hook, AdminPlayerInfo info) {
        Msg m = msg();
        String yes = m.raw("admin.yes");
        String no = m.raw("admin.no");
        String telegram = info.telegram() == null ? m.raw("admin.none")
                : info.telegram().telegramId() + (info.telegram().telegramUsername() == null ? ""
                        : " (@" + info.telegram().telegramUsername() + ")");
        String providers = info.external().isEmpty() ? m.raw("admin.none")
                : info.external().stream().map(l -> l.provider() + ": " + l.externalId()
                        + (l.externalName() == null ? "" : " (" + l.externalName() + ")"))
                        .collect(Collectors.joining(", "));
        String text = m.raw("admin.view", "%name%", info.name(), "%uuid%", info.uuid().toString(),
                "%status%", info.online() ? m.raw("admin.online") : m.raw("admin.offline"),
                "%banned%", info.banned() ? yes : no, "%premium%", info.premium() ? yes : no,
                "%telegram%", telegram, "%providers%", providers);

        String u = info.uuid().toString();
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.secondary(P + "kick:" + u, m.raw("admin.btn-kick")));
        buttons.add(Button.danger(P + "ban:" + u, m.raw("admin.btn-ban")));
        buttons.add(Button.primary(P + "warn:" + u, m.raw("admin.btn-warn")));
        buttons.add(Button.success(P + "unban:" + u, m.raw("admin.btn-unban")));
        if (info.premium()) buttons.add(Button.secondary(P + "unprem:" + u, m.raw("admin.btn-unpremium")));
        if (info.telegram() != null) buttons.add(Button.danger(P + "ultg:" + u, m.raw("admin.btn-unlink", "%method%", "Telegram")));
        for (ExternalLinkInfo link : info.external()) {
            buttons.add(Button.danger(P + "ulp:" + link.provider() + ":" + u,
                    m.raw("admin.btn-unlink", "%method%", link.provider())));
        }

        List<ActionRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size() && rows.size() < 5; i += 5) {
            rows.add(ActionRow.of(buttons.subList(i, Math.min(buttons.size(), i + 5))));
        }
        hook.sendMessage(text).setComponents(rows).queue();
    }
}
