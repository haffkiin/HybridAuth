# HybridAuth 1.2.0

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

Cracked records use the exact entered case. An exact registered cracked name is checked before Mojang's case-insensitive lookup, allowing a deliberate pair such as `ReMure` (premium) and `remure` (cracked). The licensed player must use the canonical Mojang case. Exact-name and UUID conflicts are never silently merged.

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

## Security notes

- Passwords are hashed with PBKDF2-HmacSHA256 at **310,000 iterations**. Older records (e.g. 65,536 iterations) remain valid and are transparently re-hashed on the next successful login.
- Rate limiting is two-level: identity (`uuid|name|ip`) locks after `maxLoginAttempts` failures, and a global per-name counter (3× the threshold) blocks brute-force attempts even when the attacker rotates IPs.
- Failed attempts count for unregistered names too, so username probing is also throttled.
- Login names are validated (`[A-Za-z0-9_]{3,16}`) in the login phase before any Mojang request, because the mod replaces the vanilla handshake.
- Audit log (`config/hybridauth/logs/auth.log`) rotates by size (5 MB) and keeps up to 5 archives.
- Sessions remain IP-bound: the same IP that authenticated may resume without a password until the session expires. This is safe on dedicated IPs; beware shared NATs/proxies.

## Commands and API

The public HybridAuth API exposes identity resolution and server-thread whitelist operations to the Discord companion. HybridAuth has no dependency on Discord or JDA; if the Discord bot is disabled, Minecraft authentication is unaffected.

## Changelog 1.2.0

- **New**: always-visible license-nick notifications (chat + title) for premium logins and for cracked players holding a licensed nickname.
- **New**: `/changepassword`, admin commands `info`, `unregister`, `list`, `status`.
- **New**: audit log rotation; all player-facing messages moved to config (no hardcoded English strings left).
- **Security**: username validation in the replaced login handshake; `/login` no longer kicks already-authenticated players after exhausted attempts; PBKDF2 work factor raised to 310k with transparent rehash-on-login; per-name rate limiting defeats IP rotation; unregistered-name attempts are throttled.
- **Fixes**: `/hybridauth reload` now applies Mojang timeout/cache settings; thread-safety (volatile state in the login mixin, null-safe server callbacks); removed dead config (`hideUnauthenticated`, `storageType`) and dead code.
