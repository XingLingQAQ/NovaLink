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
backend:
  host: "127.0.0.1"
  port: 8888
  username: "PMMP_Server"
  password: "your-password-here"
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
| `/nc reload` | Reload configuration | `novachat.admin.reload` |
| `/nc debug` | Toggle debug mode | `novachat.debug` |

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `novachat.use` | Basic NovaChat usage | true |
| `novachat.join` | Join channels | true |
| `novachat.leave` | Leave channels | true |
| `novachat.create` | Create private channels | op |
| `novachat.invite` | Invite players to channels | op |
| `novachat.admin` | Administrative commands | op |
| `novachat.admin.mute` | Mute players | op |
| `novachat.admin.kick` | Kick players from channels | op |
| `novachat.admin.announce` | Send announcements | op |
| `novachat.admin.reload` | Reload configuration | op |
| `novachat.debug` | Toggle debug mode | op |

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
