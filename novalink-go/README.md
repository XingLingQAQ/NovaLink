# NovaLink-Go

> ⚠️ **STATUS: FROZEN / EXPERIMENTAL**
>
> The **production NovaLink backend is Java** (`novalink-core`).
> This Go tree is retained as a protocol-reference / future port.
> It is **not** feature-complete versus Java (handshake auth, mute/filter on the
> chat path, scope routing, and admin actions are incomplete or diverge).
>
> Do **not** deploy `novalink-go` for production Minecraft networks until it
> passes the same behavioral acceptance suite as `novalink-core`.
>
> See [FROZEN.md](FROZEN.md) for freeze rationale and unfreeze checklist.

Go implementation of the NovaLink backend server for cross-server Minecraft chat.

## Features (library packages; wiring incomplete)

- NovaProtocol codecs intended to be compatible with the Java version
- Channel packages with GLOBAL, SERVER, and PRIVATE scopes
- Auth / mute / filter packages exist but are **not fully wired** on the default chat path
- Storage backends (Memory, MySQL, Redis) sketched
- WebSocket/JWT packages present

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

**Prefer Java for production:**

```bash
java -jar novalink-core.jar
```

## Configuration

See `novalink.yml`. Format aims to be compatible with the Java version, but
runtime behavior is not guaranteed to match.

## Project Structure

```
novalink-go/
├── cmd/novalink/       # Main entry point (if present)
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
