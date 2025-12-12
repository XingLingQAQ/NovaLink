# NovaChat-LeviLamina

NovaChat plugin for LeviLamina (Bedrock Dedicated Server) - Part of the NovaChat & NovaLink distributed cross-platform Minecraft chat infrastructure.

## Requirements

- LeviLamina SDK
- xmake build system
- C++20 compatible compiler (MSVC recommended for Windows)
- Windows 10/11 or Windows Server 2019+

## Building

### Prerequisites

1. Install [xmake](https://xmake.io/#/guide/installation):
   ```powershell
   winget install xmake
   ```

2. Install Visual Studio 2022 with C++ workload

### Build Commands

```bash
# Configure project
xmake f -m release

# Build
xmake

# Build debug version
xmake f -m debug
xmake
```

### Output

The built plugin will be located at:
- `build/bin/novachat-levilamina.dll`
- `build/bin/manifest.json`

## Installation

1. Copy `novachat-levilamina.dll` and `manifest.json` to your LeviLamina plugins directory:
   ```
   bedrock_server/plugins/novachat-levilamina/
   ```

2. Start the server to generate the default configuration

3. Edit `plugins/novachat-levilamina/config.json` with your NovaLink backend settings

4. Restart the server

## Configuration

```json
{
    "backend": {
        "host": "127.0.0.1",
        "port": 8888,
        "username": "LeviLamina_Server",
        "password": "your-password-here",
        "reconnect_delay": 5
    },
    "chat": {
        "replace_vanilla": false,
        "default_channel": "local"
    },
    "format": {
        "prefix": "§8[§bNovaChat§8]§r ",
        "error": "§c错误: {message}",
        "success": "§a成功: {message}",
        "default": "§7[{channel_name}] {player}§f: {message}",
        "channels": {
            "global": "§c[全服] §7{player}§f: {message}",
            "local": "§e[本地] §7{player}§f: {message}"
        }
    },
    "debug": false
}
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/nc help` | Show help | - |
| `/nc join <channel>` | Join a channel | - |
| `/nc leave` | Leave current channel | - |
| `/nc toggle` | Toggle chat mode | - |

## License

MIT License
