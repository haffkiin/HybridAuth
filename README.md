# HybridAuth 2.0.0

Server-only hybrid authentication for NeoForge 1.21.1. The server remains `online-mode=false`: premium accounts authenticate through Mojang session verification, while cracked accounts use the existing password/recovery flow.

HybridAuth is also the single source of truth for Minecraft identity profiles and whitelist operations. `SMPIdentityDiscord` calls its API instead of calculating UUIDs independently.

## Data safety

The existing `config/hybridauth/players.json` format is preserved. Password hashes, recovery hashes, registration timestamps, login timestamps, and audit logs are not removed. Authentication records are never silently deleted when a UUID differs; conflicts are logged and left for explicit review.

On startup, verified premium entries that were previously written with an offline UUID can be repaired. The original `whitelist.json` is copied to `config/hybridauth/backups/` before any change, and a JSON report is written beside it. Unknown or ambiguous entries are left untouched.

## Important settings

```toml
[premium]
	enablePremiumAutologin = true
	onMojangApiFailure = "KICK"
	mojangApiTimeoutMs = 5000
	cacheExpirationMinutes = 10
	autoRepairVerifiedWhitelist = true
```

The Mojang profile cache is bounded and shared by login and Discord whitelist requests. Requests run off the server thread; repeated concurrent checks for one name are joined into one HTTP request.

## Case policy

Cracked records use the exact entered case. A deliberate pair such as `ReMure` (premium) and `remure` (cracked) is allowed. The licensed player must use the canonical Mojang case. Exact-name and UUID conflicts are never silently merged.

If a cracked record already holds the exact canonical licensed nick (`ReMure`), the cracked owner keeps logging in with the password. The licensed owner cannot enter: the cracked record is not replaced, and the login prompt and timeout message show `licensedNameOccupied`. That message tells the cracked owner to move the account and the licensed owner to contact support. Support moves the cracked account with `/hybridauth transfer` (see below), after which the licensed owner can enter. (Not yet verified on a live server.)

## License nick visibility

Players always see whether their nickname is a licensed one — both in chat and on screen (title/actionbar), so the notice is visible in every launcher:

- **Premium players** get a confirmation on every login: the nickname is licensed and the entry was verified via Mojang session servers.
- **Cracked players whose nickname is registered at Mojang** get a warning that the nickname belongs to a licensed account (checked through the shared Mojang cache, so it does not add API load).
- All these messages (including kick reasons such as `premiumKick`, `mojangApiError`, `invalidUsername`) are configurable in the `[messages]` section of `config/hybridauth-server.toml`.

## Commands

Player commands (permission level 0):

| Command | Description |
|---|---|
| `/register <password> <confirm>` (`/reg`) | Register a password account (cracked players) |
| `/login <password>` (`/l`) | Log in with the password |
| `/changepassword <old> <new> <confirm>` | Change the password of a logged-in password account; also invalidates the IP session |
| `/recoverycode` | Re-issue the one-time recovery code (requires prior login) |
| `/recover <code> <password> <confirm>` | Reset the password with a one-time recovery code |
| `/skin nick <name>` (`/skin <name>`) | Take the skin of a licensed Mojang account |
| `/skin url <link> [classic|slim]` | Make a skin from a PNG link (needs a MineSkin key, see [Skins](#skins)) |
| `/skin reset` | Drop the chosen skin (licensed players get their Mojang skin back) |
| `/skin info` | Show the chosen skin |
| `/skin help` | In-game instructions (also shown by `/skin`); the text is the `[skinMessages] help` list |

Admin commands (permission level 3):

| Command | Description |
|---|---|
| `/hybridauth reload` | Fully reloads the config, including Mojang API timeout and cache settings |
| `/hybridauth backup` | Create an immediate backup of the auth database |
| `/hybridauth recovery <username>` | Issue a one-time recovery code for a player |
| `/hybridauth info <username>` | Inspect an account: type, UUID, registration/last-login dates, IP |
| `/hybridauth unregister <username>` | Delete an account; a backup is created automatically, online player is kicked |
| `/hybridauth list` | List all accounts (capped output) |
| `/hybridauth status` | Account totals, premium/cracked split, active sessions, authenticated players online |
| `/hybridauth transfer <old> <new>` | Preview moving a cracked account to a new nick (nothing changes) |
| `/hybridauth transfer <old> <new> confirm` | Move the account to the new nick; see [Account transfer](#account-transfer) |
| `/hybridauth skin <account> nick <name>` / `url <link>` / `reset` / `info` | Set, drop or inspect the skin of any account (no cooldown), online or offline |

## Skins

Cracked players have no skin on an offline server: everyone is Steve or Alex. HybridAuth gives them one.

- `/skin nick <name>` takes the skin of a licensed Mojang account (signed textures from the Mojang session server; no key needed).
- `/skin url <link> [classic|slim]` makes a skin from a PNG link. The client only accepts textures from Mojang domains, so the link is sent to [MineSkin](https://mineskin.org), which uploads the skin to a Mojang account and returns signed textures. This needs a MineSkin API key in `[skins] mineskinApiKey` (https://account.mineskin.org/keys). The link and the image go to MineSkin; skins are created `unlisted`. Without a key `/skin url` says it is not configured and `/skin nick` still works.
- The chosen skin is saved per UUID in `config/hybridauth/skins.json` (atomic writes, `.bak` copy of the previous file) and applied at every join before the player is announced to others. Changing it while online updates the skin for everyone nearby at once and re-sends the world state to the player (same packet sequence as a respawn that keeps all data).
- `/hybridauth transfer` moves the skin together with the account; a failed transfer rolls it back.
- Licensed players are not touched by default (they keep their Mojang skin). They may use `/skin` too, and `/skin reset` restores the skin Mojang gave them at login.
- Limits: `cooldownSeconds` (1) between `nick`/`reset`, `urlCooldownSeconds` (1) between `url` requests, one request at a time per player, `urlAllowedDomains` (empty = any public site; `localhost`, IP addresses and internal names are always refused), `requestTimeoutSeconds` for MineSkin. Mojang answers are cached for 10 minutes.
- Nothing is fetched automatically by nick: a cracked `ReMure` would otherwise get the skin of the licensed `ReMure`.
- Messages are in `[skinMessages]`. Audit events: `SKIN_SET`, `SKIN_RESET`.

The skin refresh packet sequence is adapted from [SkinRestorer](https://github.com/Suiranoil/SkinRestorer) (MIT, © Lionarius); see `THIRD_PARTY_NOTICES.md`.

## Security notes

- Passwords are hashed with PBKDF2-HmacSHA256 at **310,000 iterations**. Older records (e.g. 65,536 iterations) remain valid and are transparently re-hashed on the next successful login. Hashing runs on a separate thread pool, not on the server thread.
- Rate limiting is two-level: identity (`uuid|name|ip`) locks after `maxLoginAttempts` failures, and a global per-name counter (3× the threshold) blocks brute-force attempts even when the attacker rotates IPs. The address of the last successful login for that name is exempt from the per-name lock, so an attacker cannot lock the owner out from the owner's own address. The identity lock still applies to it.
- Failed attempts count for unregistered names too, so username probing is also throttled. `/recover` answers the same way for unregistered names and for wrong codes.
- Login names are validated (`[A-Za-z0-9_]{3,16}`) in the login phase before any Mojang request, because the mod replaces the vanilla handshake.
- Protocol order is enforced: a hello or key packet received in the wrong login state disconnects the client.
- Duplicate logins: vanilla kicks the online session with the same UUID before the login completes. A cracked newcomer from a different address is rejected while an authenticated session is online. The newcomer is not allowed to kick it. A reconnect from the same address still replaces the stale session, and a Mojang-verified premium login is never rejected this way.
- Audit log (`config/hybridauth/logs/auth.log`) rotates by size (5 MB) and keeps up to 5 archives.
- IP sessions: the session lifetime is counted from the last password or license login (`sessionDurationMinutes`, default 720). Using the session does not extend it. The session is bound to the IP. On shared NATs or mobile carriers, anyone on the same IP can resume the session until it expires. Set `enableIpSession = false` if that matters for your players.
- `/hybridauth unregister` and `/hybridauth info` match the exact nick only. If the nick is not found but a case variant exists, the command reports it. `unregister` aborts if the backup cannot be created.

## Account transfer

`/hybridauth transfer <old> <new>` moves a cracked account to a new nick. Use it to free a nick that a licensed owner now holds, for example when support resolves a cracked record on a nick that was later claimed on Mojang. The command first shows a preview. `confirm` runs the transfer.

Both players must be offline. The old account must be a cracked account. The new nick must be valid, must not be licensed on Mojang (if Mojang cannot be reached, the transfer is refused), and must not already have an account, world data, or a whitelist/op/ban conflict.

What is moved:

- **Auth record**: the password hash, recovery code hash, and login dates, under the new UUID.
- **World data**: `playerdata/<uuid>.dat` (the `UUID` field is rewritten), `.dat_old`, `stats/<uuid>.json`, `advancements/<uuid>.json`. This includes inventory, position, experience, and mod attachments stored in the player file.
- **Whitelist, ops (with level), and ban list entries**.
- **Data of other mods**: handled through `com.hybridauth.api.AccountTransfers` (see below). HybridAuth does not know these files.

Safety: before any change, the affected files and lists are copied to `config/hybridauth/backups/transfer-<timestamp>-<old>-to-<new>/`, and `players.json` gets a backup as well. If any step fails, the completed steps are rolled back in reverse order. The backup stays on disk.

### Handlers for other mods

```java
AccountTransfers.register(new AccountTransferHandler() {
    public String name() { return "SMPIdentity"; }
    public Runnable transfer(AccountTransferPlan plan) throws Exception {
        // move this mod's data keyed by plan.fromId() to plan.toId()
        return () -> { /* undo: restore the old keys */ };
    }
});
```

The handler runs on the server thread with both accounts offline. It must either finish or throw without leaving changes behind, and it returns a `Runnable` that undoes its work. If a later step fails, that `Runnable` is called.

## Commands and API

The public HybridAuth API exposes identity resolution and server-thread whitelist operations to the Discord companion. HybridAuth has no dependency on Discord or JDA; if the Discord bot is disabled, Minecraft authentication is unaffected.

The `AccountTransfers` registry is a second extension point for other mods (see [Account transfer](#account-transfer)).

## Changelog 2.0.0

- **New: skins for cracked players.** `/skin nick`, `/skin url`, `/skin reset`, `/skin info`, admin `/hybridauth skin`. Skins are stored in `skins.json`, applied at join and refreshed live, and move with `/hybridauth transfer`. See [Skins](#skins).
- New config sections `[skins]` and `[skinMessages]`. `/skin url` stays off until `mineskinApiKey` is set.
- Three new mixins (`PlayerList.placeNewPlayer`, `ChunkMap` and `ChunkMap$TrackedEntity` accessors).
- The transfer preview shows the skin line; the transfer backup includes `skins.json`.
- **Fix**: the "your nick is licensed, automatic login" notice was sent twice on a premium login; now once. The licensed-nick message calls the account "пиратка" and uses the red/yellow colours again.

## Changelog 1.3.1

- **Fix**: kick and chat messages no longer show a stray glyph where a line break was in the config (CR/LF characters are now stripped; line breaks become spaces).

## Changelog 1.3.0

- **Security**: duplicate logins by nick are rejected when the online session is authenticated and comes from another address (`duplicateLogin`). Previously vanilla kicked it before HybridAuth could check anything.
- **Security**: a cracked record on a nick that is licensed on Mojang no longer lets the licensed owner in. The owner gets the `licensedNameOccupied` prompt and timeout message. Before, the owner was routed into the cracked record without a Mojang check. The cracked owner keeps logging in with the password until the account is moved.
- **New**: `/hybridauth transfer <old> <new> [confirm]` moves a cracked account (auth record, world data, whitelist, ops, bans) to a new nick, with a preview, backups, and rollback. The `AccountTransfers` API lets other mods move their data too.
- **Security**: IP sessions no longer extend on use (absolute lifetime).
- **Security**: the per-name lock no longer blocks the last successful login address.
- **Security**: `/recover` throttles unknown nicks. Protocol state is checked in the login mixins.
- **Fixes**: whitelist repair matches the nick exactly (case variants are kept). Admin `info`/`unregister` no longer fall back to case-insensitive matches. `unregister` aborts without a backup. Password hashing runs off the server thread. A second password operation for the same player is refused while one is pending (`passwordCheckPending`).
- **New**: `WhitelistGateway.AddOutcome.warning` and `IdentityResolver.licensedNickWarning` warn when a cracked nick matches a licensed account.

## Changelog 1.2.0

- **New**: always-visible license-nick notifications (chat + title) for premium logins and for cracked players holding a licensed nickname.
- **New**: `/changepassword`, admin commands `info`, `unregister`, `list`, `status`.
- **New**: audit log rotation; all player-facing messages moved to config (no hardcoded English strings left).
- **Security**: username validation in the replaced login handshake; `/login` no longer kicks already-authenticated players after exhausted attempts; PBKDF2 work factor raised to 310k with transparent rehash-on-login; per-name rate limiting defeats IP rotation; unregistered-name attempts are throttled.
- **Fixes**: `/hybridauth reload` now applies Mojang timeout/cache settings; thread-safety (volatile state in the login mixin, null-safe server callbacks); removed dead config (`hideUnauthenticated`, `storageType`) and dead code.
