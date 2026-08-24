# NovaChat-Endstone

NovaChat plugin for Endstone - Cross-server chat integration for Minecraft Bedrock Edition.

## Requirements

- Python 3.10+
- Endstone 0.11.0+ (current stable: 0.11.8, supports BDS 1.26.40)
- Minecraft Bedrock 1.26.40 (BDS paired with endstone 0.11.7+)

## Installation

1. Download the latest release
2. Place the plugin folder in your Endstone plugins directory
3. Configure `config.yml` with your NovaLink server details
4. Restart your server

## Configuration

Edit `plugins/NovaChat/config.yml` after the first server start:

```yaml
backend:
  host: "127.0.0.1"
  port: 8888
  username: "EndstoneServer"
  password: "your-password"
  reconnect-delay: 5

chat:
  replace_vanilla: false
  default_channel: "local"

format:
  channels:
    global: "§c[全服] §7{player}§f: {message}"
    local: "§e[本地] §7{player}§f: {message}"
  default: "§7[{channel_name}] {player}§f: {message}"

debug: false
```

## Commands

- `/novachat help` - Show help
- `/novachat join <channel>` - Join a channel
- `/novachat leave` - Leave current channel
- `/novachat toggle` - Toggle chat mode
- `/novachat reload` - Reload configuration (admin)
- `/novachat debug` - Toggle debug mode (admin)

## Development

```bash
# Install dependencies
pip install -e ".[dev]"

# Run tests
pytest

# Run property-based tests
pytest tests/ -v
```

## License

MIT License
