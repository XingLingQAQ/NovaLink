# NovaLink-Go is Frozen

## Production backend

**Java (`novalink-core`) is the production NovaLink backend.**  
Deploy and operate Minecraft networks against Java only.

This Go tree is kept as a protocol-reference / experimental port. It is **not** a drop-in replacement for Java.

## Why frozen

NovaLink-Go packages exist for protocol, channels, auth, mute, filter, and storage, but the default runtime path is incomplete or diverges from Java. Shipping it as “stable” misleads operators and splits maintenance effort.

## What would be needed to unfreeze

Before considering production use, Go must reach behavioral parity with Java and pass the same acceptance suite. At minimum:

1. **Handshake auth** — client connect / auth flow matching Java (credentials, failure codes, session binding).
2. **Mute / filter on the chat path** — mute and sensitive-word filtering applied on the live message path, not only as unused packages.
3. **Scope routing parity** — GLOBAL / SERVER / PRIVATE (and world-scoped) routing consistent with Java.
4. **Admin actions** — mute, kick, announce, title, spy, and related admin commands wired end-to-end.
5. **Shared acceptance tests** — the same behavioral suite used for `novalink-core` must pass against Go (protocol fixtures + multi-client chat scenarios).

Until those items land and stay green, treat this directory as **frozen / experimental**. Prefer contributing to Java when fixing production bugs.
