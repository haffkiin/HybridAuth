# HybridAuth 1.1.0

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

## Commands and API

The existing `/register`, `/login`, and `/recover` flow remains available. The public HybridAuth API exposes identity resolution and server-thread whitelist operations to the Discord companion. HybridAuth has no dependency on Discord or JDA; if the Discord bot is disabled, Minecraft authentication is unaffected.
