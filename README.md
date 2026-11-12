# DiscordTgAuth

Discord login for [TgAuth](../TgAuth), added through TgAuth's `LoginProvider` API (API version 3, admin panel needs 4). The addon only
proves that a player owns a Discord account and asks that account to confirm logins. Everything else is TgAuth's job:

* the **links are stored in TgAuth's database** (SQLite/MySQL/MariaDB, table `external_links`), not in a file;
* the **Telegram / Discord choice** is shown by TgAuth (clickable chat text, or the vanilla dialog window if the server
  owner sets `ui.vanilla-dialog: true` in TgAuth's config, off by default);
* TgAuth checks the **licence (FastLogin)** and the **trusted IP** with its own settings and logs the player in. It calls
  this addon only when a confirmation is really needed. A player without a link is never let in by FastLogin.

## How it works for the player

1. First join: TgAuth offers `[Telegram] or [Discord]`.
2. **Discord**: instructions with the code and a clickable **[Join the Discord server]** (your invite).
3. They press the button in the link channel, the bot DMs them, they send the code. TgAuth saves the link and logs them in.
4. Next joins: licensed players and trusted IPs go straight in (TgAuth decides); everybody else gets a DM with
   **"It's me / Not me"**. "Not me" kicks the player.

## Requirements

* **TgAuth with API version 4** (this repository's `TgAuth`), Paper 1.21.x, Java 21. No setting in TgAuth has to be
  turned on for the addon to work; set `auth.auth-timeout-seconds` to ~300 so players have time to use Discord.
* Licence skipping: install FastLogin (TgAuth already integrates with it). Trusted IPs: `auth.cracked-ip-cooldown-seconds`
  in TgAuth. The addon has no copies of these settings.

To use Discord **instead of** Telegram, set `telegram.auth-enabled: false` in TgAuth's config: Discord is then the only
method offered (no choice menu) and TgAuth refuses to let anyone in without a registered provider.

## Install

1. Copy the TgAuth jar (the build with API version 4) into `libs/TgAuth.jar` of this project, run `mvn package` and put
   `target/DiscordTgAuth-addon-1.0.0.jar` into `plugins/`. The TgAuth jar is only used to compile; nothing is bundled.
   JDA (the Discord library) is downloaded automatically by Paper on first start (needs internet access).
2. Discord Developer Portal -> New Application -> Bot -> copy the **token**. No privileged intents are needed.
3. Invite the bot to your server (OAuth2 -> URL Generator -> scope `bot`) with **View Channel**, **Send Messages** and
   **Read Message History** in the link channel. Add **Create Invite** too and, if `invite-url` is empty, the bot
   creates a permanent invite itself and writes it into `config.yml`.
4. In Discord enable Developer Mode, copy the **server id** and the **link channel id**.
5. Fill `plugins/DiscordTgAuth/config.yml`: `bot-token`, `guild-id`, `link-channel-id`, `link-channel-name`, `invite-url`.
   Set `language: "ru"` for Russian (default `en`). All texts are in `plugins/DiscordTgAuth/lang/en.yml` and `ru.yml`;
   `config.yml` contains settings only. Add your own language by copying a file, e.g. `lang/de.yml`.
6. Restart. The bot posts the "Link" panel in the channel if it is not there yet (`/tgdiscord panel` posts a new one).

## Commands

* `/tgdiscord panel` - post the link panel again · `/tgdiscord adminpanel` - post the admin panel again · `/tgdiscord reload` (permission `tgdiscord.admin`, default op).
* Unlinking and link info are TgAuth's own: `/tgauth unlink <player>` and `/tgauth userinfo <player>` work for Discord links.

## Notes

* If the Discord bot is offline it is not offered to new players (they get the Telegram code). Players who linked Discord
  get a "Discord login is unavailable" message and are kicked by TgAuth's timeout, so nobody can take over their name
  through Telegram. Licensed players and trusted IPs still get in.
* The panel message is found again by looking at the latest 50 messages of the channel, so nothing is stored in a file.
* Change the JDA version with `jda.version` in `pom.xml` if you need a newer one.
* This was not compiled against the real Paper/JDA libraries in the environment it was written in; if `mvn package`
  reports an error, send the output.

## Proxy

If the server cannot reach Discord directly, set `proxy.enabled: true`:

- **Manual:** fill in `proxy.type` (HTTP, SOCKS4 or SOCKS5), `host`, `port` and optionally `username` / `password`.
- **From TgAuth:** leave `proxy.host` empty and the bot uses the proxy TgAuth uses (its own `proxy.*` config or
  an addon that supplies proxies through the TgAuth API; needs TgAuth API version 5). Turn this off with
  `proxy.use-tgauth-proxy: false`.

`proxy.enabled: false` is the master switch: the bot then connects directly, and the proxy from TgAuth is
ignored as well. The proxy is read when the bot starts, so restart the server after changing it.

## Admin panel in Discord

Set `discord.admin-channel-id` to a **private** channel; the bot posts an "Open admin panel" button there
(`/tgdiscord adminpanel` posts it again). Pressing it opens a private (ephemeral) menu: **Find player**, then
Kick / Ban / Warn / Unban / Remove premium / Unlink Telegram or a provider. Reason and duration are entered in a
Discord form (modal), not in the next message; the duration is `30m`, `12h`, `7d`, `2w` or `p` (empty = permanent).
The actions go through TgAuth's API (v4) and behave like the Telegram `/admin` buttons, including TgAuth's
`admin-commands` for punishment plugins; the Telegram admins are told about every action.

Who may use it: every Discord user whose linked game account has the `tgauth.admin` permission (checked when the
panel is opened, then remembered for `discord.admin-session-minutes`), plus the ids in `discord.admin-ids`.
After the admin check comes TgAuth's admin PIN (when `admin-pin.enabled` is on in TgAuth): a keypad of buttons whose
digits are shown only as dots; the first time you set a PIN (typed twice). The PIN, the wrong-guess lockout with its
alerts and the unlocked session (`admin-pin.session-minutes`) are TgAuth's settings and are separate from the
Telegram PIN. `/tgauth pinreset <player>` removes it too. Keep the admin channel private. Needs TgAuth with API version 4.
