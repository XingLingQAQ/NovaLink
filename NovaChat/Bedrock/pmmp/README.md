# NovaChat PocketMine-MP Plugin

Cross-server chat system for PocketMine-MP Bedrock servers.

## Requirements

- PHP 8.1+
- PocketMine-MP 5.x
- ext-sockets extension

## Installation

1. Download the latest release from the releases page
2. Place the plugin in your `plugins/` directory
3. Start your server to generate the default configuration
4. Edit `plugins/NovaChat/config.yml` to configure your backend connection
5. Restart your server

## Configuration

```yaml
config-version: 1

backend:
  host: "127.0.0.1"
  port: 8888
  username: "PMMP_Server"
  password: "your-password-here"
  server-version: "5.0.0"
  reconnect-delay: 5

chat:
  replace_vanilla: false
  default_channel: "local"

format:
  prefix: "§8[§bNovaChat§8]§r "
  channels:
    global: "§c[全服] §7{player}§f: {message}"
    local: "§e[本地] §7{player}§f: {message}"
  default: "§7[{channel_name}] {player}§f: {message}"

debug: false
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/nc help` | Show help information | `novachat.use` |
| `/nc join <channel>` | Join a channel | `novachat.join` |
| `/nc leave` | Leave current channel | `novachat.leave` |
| `/nc toggle` | Toggle chat on/off | `novachat.use` |
| `/nc mute <player> [minutes]` | Mute a player | `novachat.admin.mute` |
| `/nc unmute <player> [channel]` | Lift a mute | `novachat.admin.mute` |
| `/nc kick <player>` | Kick a player from your channel | `novachat.admin.kick` |
| `/nc ban <player> [duration] [channel]` | Ban a player (all channels when omitted) | `novachat.ban` |
| `/nc unban <player\|UUID> [channel]` | Lift a ban | `novachat.ban` |
| `/nc auth <password>` | Start a super-admin session | `novachat.use` |
| `/nc announce <channel> <message>` | Send an announcement | `novachat.admin.announce` |
| `/nc title <channel> <title> [subtitle]` | Send a title to a channel | `novachat.title` |
| `/nc reload` | Reload configuration | `novachat.admin.reload` |
| `/nc debug` | Toggle debug mode | `novachat.debug` |

`/nc ban` takes the duration and the channel in either order, and converts the
duration to seconds (`30m` → `1800`) exactly like the Java shells. Omitting the
channel means a **network-wide** ban: it deliberately does not fall back to your
active channel. `/nc unban` accepts a UUID as well as a name and does not need
the target to be online.

`/nc announce` and `/nc title` require an **active super-admin session** — the
backend rejects them with `NC-403` otherwise, and console cannot authenticate.
See the note under [Super-admin commands](#super-admin-commands).

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `novachat.use` | Basic NovaChat usage | true |
| `novachat.join` | Join channels | true |
| `novachat.leave` | Leave channels | true |
| `novachat.create` | Create private channels | op |
| `novachat.invite` | Invite players to channels | op |
| `novachat.admin` | Administrative commands | op |
| `novachat.admin.mute` | Mute and unmute players | op |
| `novachat.admin.kick` | Kick players from channels | op |
| `novachat.ban` | Ban and unban players | op |
| `novachat.admin.announce` | Send announcements | op |
| `novachat.title` | Send title messages | op |
| `novachat.admin.reload` | Reload configuration | op |
| `novachat.debug` | Toggle debug mode | op |

### Super-admin commands

`/nc announce` and `/nc title` need an active **super-admin session** on the
StarLink backend, not just the local permission node:

1. A player runs `/nc auth <password>`, then `/nc announce ...` or `/nc title ...`.
2. Console/RCON cannot do this. `/nc auth` is player-only (the backend binds the
   session to a player UUID), so console-sentinel announce/title requests are
   rejected with `NC-403` unless the backend operator has separately registered
   a super-admin session for the sentinel. On a stock backend they will fail.
3. Both commands therefore print a *progress* line, not a success line; the
   real result arrives asynchronously as an `AdminActionResponse`.

## Development

### Building from source

```bash
composer install
```

### Running tests

```bash
composer test
```

### Static analysis

```bash
composer analyze
```

## License

MIT License
