# NovaLink-Go

Go implementation of the NovaLink backend server for cross-server Minecraft chat.

## Features

- Full NovaProtocol implementation compatible with Java version
- Multi-platform client support (Bukkit, Velocity, BungeeCord, Nukkit, Fabric, NeoForge, Quilt, Forge, PMMP, Endstone, PNX, LeviLamina)
- Channel system with GLOBAL, SERVER, and PRIVATE scopes
- Authentication with SHA-256 password hashing
- Permission system with four levels (USER, MOD, ADMIN, SUPER)
- IP ban mechanism for failed authentication attempts
- JWT-based web panel authentication
- Multiple storage backends (Memory, MySQL, Redis)
- Sensitive word filtering
- Player muting system

## Requirements

- Go 1.21 or higher

## Building

```bash
go build -o novalink ./cmd/novalink
```

## Running

```bash
./novalink -config novalink.yml
```

## Configuration

See `novalink.yml` for configuration options. The configuration format is compatible with the Java version.

## Project Structure

```
novalink-go/
├── cmd/novalink/       # Main entry point
├── pkg/
│   ├── auth/           # Authentication and JWT
│   ├── channel/        # Channel management
│   ├── config/         # Configuration loading
│   ├── filter/         # Sensitive word filtering
│   ├── mute/           # Player muting
│   ├── network/        # TCP server and client handling
│   ├── protocol/       # NovaProtocol implementation
│   └── storage/        # Data persistence
└── test/               # Integration tests
```

## License

MIT License
