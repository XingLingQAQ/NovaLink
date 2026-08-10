# novachat-client-core — Design

**Status:** Architecture B confirmed. Helper adoption FULL/partial as before. **`CoreNetworkClient`:** extracted + **ALL 7 platform facades wired** (velocity, bungee, nukkit, sponge, folia, pnx, bukkit). **`ChannelCommandService`:** wired on bungee, nukkit, velocity, sponge, pnx, folia, bukkit. **Not:** mod (own `ChatMode`). See §5.1.
**Date:** 2026-07-30  
**Scope:** Plugin-side shared runtime (connection lifecycle helpers, reconnect policy, client state, optional Netty engine). **Not** used by `novalink-core`.

---

## 0. Architecture B — three layers

| Layer | Module | Role | Consumers |
|-------|--------|------|-----------|
| **Shared protocol** | `novachat-common` | NovaProtocol packets, codecs (VarInt framing, encode/decode), mentions, extensions | **Backend + all clients** |
| **Plugin runtime** | `novachat-client-core` | Connection lifecycle helpers, reconnect policy, pending-request tracking, per-player channel/chat state | **NovaChat plugins/mods only** |
| **Production backend** | `novalink-core` | Java production NovaLink server (routing, auth, channels, persistence, WS/REST) | Standalone process |

### Explicit non-goals for this module

- **`novalink-core` must not depend on `novachat-client-core`.** Backend networking stays server-side; it only shares `novachat-common`.
- **No NetworkClient extraction into the backend or into a dual-use transport library.** Client Netty bootstraps may later consolidate *inside* this module for plugins; that work is plugin-only and does not change `novalink-core`.
- Protocol packets and codecs stay in `novachat-common` — do not move them here.

### Dependency direction

```text
novalink-core ──────────────► novachat-common
                                    ▲
novachat-bukkit / velocity / … ─────┤
         │                          │
         └────► novachat-client-core ┘
```

`novachat-client-core` → `novachat-common` (+ Netty for future transport helpers).  
Platform plugins → `novachat-client-core` + `novachat-common`.  
`novalink-core` → `novachat-common` only.

---

## 1. What lives where today

### `novachat-common` (backend + clients)

- Protocol: `Packet`, `PacketRegistry`, `NovaProtocol`, packet types
- Codecs: `PacketEncoder` / `PacketDecoder`, `Varint21FrameDecoder` / `Varint21LengthFieldPrepender`
- Mentions / item-display chat helpers
- Extension loader, event bus, command registry

### `novachat-client-core` (plugins only)

Under `com.nova.chat.client`:

| Package | Responsibility |
|---------|----------------|
| `network` | `ClientConnectionConfig`, `ReconnectPolicy` / `ExponentialBackoffReconnectPolicy`, `PendingRequestTracker`, `PasswordHasher`, `SchedulerBridge`, `ClientLogger`, `CoreNetworkClient`, `CoreClientChannelHandler` |
| `state` | `PlayerChannelState`, `ChatMode` |
| `command` | `CommandIntent`, `CommandResult`, `PacketSender`, `ChannelCommandService` (skeleton; not platform-wired) |
| `format` | `FormatTemplateEngine` (curly-brace placeholders; missing keys left unreplaced), `LegacyColorCodes` (pure `&#RRGGBB` → `&x&R…` / `§x§R…`; no Adventure) |

**Velocity** uses a thin `NetworkClient` facade over `CoreNetworkClient`. Other platform modules still own full `NetworkClient` / channel handlers; migrate incrementally (see §5 / §5.1).

### `novalink-core` (production backend)

- Netty server, client session auth, channel routing, mute/kick/spy/title
- Config, DB (MySQL/Redis/memory), REST, WebSocket gateway
- Depends on `novachat-common` for the wire protocol only

---

## 2. Inventory of platform NetworkClients (plugin-side only)

| Module | Class | Lines (approx) | PlatformType | Maturity |
|--------|-------|----------------|--------------|----------|
| `novachat-bukkit` | `NetworkClient` | ~891 | `BUKKIT` | **richest** (pending requests, ConfigSync, Title, channel/admin UX) |
| `novachat-velocity` | `NetworkClient` | thin facade | `VELOCITY` | **delegates to `CoreNetworkClient`** |
| `novachat-bungee` | `NetworkClient` | ~380 | `BUNGEECORD` | pure core skeleton |
| `novachat-nukkit` | `NetworkClient` | ~386 | `NUKKIT` | core + Nukkit scheduler |
| `novachat-pnx` | `NetworkClient` | ~379 | `POWERNUKKITX` | core + extra handler-side chat/title |
| `novachat-sponge` | `NetworkClient` | ~386 | `SPONGE` | core + Sponge async sleep reconnect |
| `novachat-folia` | `AsyncNetworkClient` | ~350+ | `FOLIA` | core + FoliaSchedulerAdapter; async connect + async packet dispatch |
| `novachat-mod/common` | `NetworkClient` (iface) + `NettyNetworkClient` | different API | FABRIC/NEOFORGE/… | **divergent** pipeline/API; second-wave migration |

Each Netty plugin also has a near-identical `ClientChannelHandler` (`SimpleChannelInboundHandler<Packet>` → `handlePacket` / `onDisconnect`).

`settings.gradle` already includes `novachat-client-core`; module depends on `novachat-common` + Netty + Mockito.

---

## 3. Shared client algorithm (plugin runtime)

All Java plugin clients (except mod) implement the **same lifecycle**. Pieces of this may move into `novachat-client-core` over time; they remain **client-only**.

### 3.1 Construction

1. Accept platform deps (today: plugin + config) → tomorrow: ports listed in §4.
2. `packetRegistry = NovaProtocol.createRegistry()` (from `novachat-common`).
3. Register **default** handlers: `HandshakeResponsePacket`, `KeepAlivePacket`.
4. Platform may register **additional** handlers after construction (or via `PacketHandlerRegistry`).

### 3.2 Connect pipeline

```
if connected → complete(true)
authFuture = new CompletableFuture<>()
workerGroup = new NioEventLoopGroup()
Bootstrap:
  channel = NioSocketChannel
  TCP_NODELAY = true
  SO_KEEPALIVE = true
  CONNECT_TIMEOUT_MILLIS = 5000  (Folia: config.getNetworkTimeout())
  pipeline:
    frameDecoder  → Varint21FrameDecoder          // novachat-common
    framePrepender → Varint21LengthFieldPrepender // novachat-common
    packetDecoder → PacketDecoder(registry)       // novachat-common
    packetEncoder → PacketEncoder(registry)       // novachat-common
    handler       → ClientChannelHandler(engine)
connect(host, port):
  success → channel=…; connected=true; attempts=0; sendHandshake()
  fail    → authFuture.complete(false); scheduleReconnect()
return authFuture  // completes on HandshakeResponse, not TCP alone
```

### 3.3 Handshake

```
passwordHash = SHA-256 hex(UTF-8 password)
send HandshakePacket(
  NovaProtocol.PROTOCOL_VERSION,
  username,          // some platforms rewrite to username@instanceId
  passwordHash,
  PlatformType.*
)
```

### 3.4 Handshake response

| Outcome | Actions |
|---------|---------|
| success | `authenticated=true`; log; `authFuture.complete(true)` |
| fail NC-401 | log credentials hint (config file name varies) |
| fail NC-420 | log protocol mismatch banner + local protocol version |
| other | log error code + message; `authFuture.complete(false)` |

Auth failure does **not** currently force disconnect; reconnect still follows TCP loss / connect fail.

### 3.5 Keepalive

On `KeepAlivePacket`: echo new `KeepAlivePacket(timestamp)` with same `requestId`. Runs on Netty thread (no scheduler hop).

### 3.6 Send / receive

- `sendPacket`: if channel active → `writeAndFlush` (+ Bukkit-only pending-request tracking).
- `handlePacket`: lookup `Class → Consumer` in `ConcurrentHashMap`; missing → debug log.
- Folia only: dispatch handler via `scheduler.runAsync` (off Netty loop).

### 3.7 Disconnect (explicit)

```
reconnecting=false; authenticated=false
close channel syncUninterruptibly
connected=false
shutdownGracefully workerGroup
```

Does **not** schedule reconnect (intentional shutdown).

### 3.8 Disconnect (unexpected) / reconnect

```
onDisconnect / connect fail:
  connected=false; authenticated=false
  if !reconnecting → scheduleReconnect()

scheduleReconnect:
  if reconnecting → return
  attempts++
  if attempts > 10 → severe log; reset attempts; stop
  reconnecting=true
  delay = min(2^(attempts-1), 30) seconds
  schedule after delay (platform scheduler):
    reconnecting=false
    shutdown old workerGroup
    connect(config host, port)
```

Constants shared: `MAX_RECONNECT_ATTEMPTS=10`, `MAX_RECONNECT_DELAY=30s` (see `ClientConnectionConfig` / `ExponentialBackoffReconnectPolicy`).

### 3.9 Auth hashing

SHA-256 → lowercase hex lives in client-core as `PasswordHasher`. Major plugin `NetworkClient`s should call it instead of local hash copies; any remaining duplicates are cleanup debt, not design.

---

## 4. Divergences table (plugin platforms)

| Concern | Shared default | Platform divergences |
|---------|----------------|----------------------|
| **Pipeline codecs** | Varint21 + PacketDecoder/Encoder from `novachat-common` | **mod** uses custom inner encoder/decoder; often missing `Varint21LengthFieldPrepender`; connect timeout 10s |
| **EventLoopGroup lifetime** | new group per `connect()`; shutdown on reconnect/disconnect | **mod** keeps one long-lived group; reconnect via `eventLoopGroup.schedule` |
| **Connect entry thread** | caller thread starts Netty connect | **Folia** wraps entire bootstrap in `scheduler.runAsync` |
| **Reconnect delay unit** | exponential seconds | Bukkit/Nukkit/PNX/Folia: `delay * 20` **ticks**; Velocity/Bungee: `TimeUnit.SECONDS`; Sponge: `Thread.sleep` on async executor; mod: ELG schedule with `reconnectDelay * 2^n` (base 5s, cap 2^5) |
| **Reconnect after max** | stop + tell user `/nc reload` | mod sets `ConnectionStatus.ERROR` |
| **PlatformType** | per module enum constant | some platforms append `@instanceId` to username via an adapter |
| **Auth credentials API** | `config.getUsername()` / `getPassword()` | PNX: `getBackendUsername()` / `getBackendPassword()` |
| **Config file in error text** | `config.yml` | Velocity: `config.toml` |
| **Logger API** | JUL-like `info/warning/severe` | Velocity/Sponge/Nukkit/PNX: SLF-style `info/warn/error` |
| **Default handlers** | Handshake + KeepAlive | **Bukkit only:** Title, ConfigSync, ChannelActionResponse, AdminActionResponse + pending request map |
| **Outbound tracking** | none | Bukkit `trackPendingRequest` for Channel/Admin action correlation (30s TTL) — helper may live in client-core (`PendingRequestTracker`) |
| **ConfigSync / world maps** | none | Bukkit parses JSON for world-restricted channels + known channel IDs (tab complete) |
| **Title handling** | none in core | Bukkit: filter by active channel + colorize on main thread; PNX: all online players via handler (not NetworkClient registry) |
| **Chat inbound** | registered outside NetworkClient by platforms | PNX dual-path: registry + hard-coded in `ClientChannelHandler` |
| **Packet dispatch thread** | Netty EL | Folia: always `runAsync` for handlers |
| **Handler registration model** | `Map<Class, Consumer>` | mod: `List<PacketHandler>` fan-out |
| **Public API surface** | `connect/disconnect/sendPacket/registerHandler/isConnected/isAuthenticated/getPacketRegistry` | mod: `sendChatMessage`, `getStatus`, no `isAuthenticated` on iface |
| **Channel handler logging** | singleton plugin debug | PNX takes explicit plugin ref; Velocity null-checks singleton |
| **CONNECT_TIMEOUT** | 5000 ms | Folia configurable; mod 10000 ms |

### What must stay platform-local

- Player messaging, titles, chat interceptors, world monitors, platform adapters.
- Bukkit pending-request UX / optimistic channel rollback (tracker can be shared; UX stays local).
- Any main-thread / region-thread player API.
- Config file formats and plugin lifecycle.

### What may move into `novachat-client-core` (plugins only)

- Connection config + reconnect policy (**done** as types; platform wiring partial — see §5.1).
- Per-player channel/chat state (**types done**; full adoption on all plugins still open).
- Password hash (**done** as `PasswordHasher`).
- Optionally later: Bootstrap + pipeline assembly (`CoreNetworkClient`), handshake/reconnect state machine, keepalive echo, class-keyed packet handler registry, shared channel handler — **still plugin-only; never imported by novalink-core**.

### What stays in `novachat-common`

- All protocol packets and codecs.
- Mentions, item display, extension framework.

---

## 5. Migration order (plugin NetworkClient consolidation)

### 5.1 Architecture B migration status

Three-layer Architecture B is unchanged (see §0): protocol in `novachat-common`, plugin runtime helpers here, production backend in `novalink-core` (no reverse dep). Platforms still own Netty `NetworkClient` bodies.

Shared slice inventory: `PasswordHasher`, config → `ReconnectPolicy` (`ClientConnectionConfig` / `ReconnectPolicy` / `ExponentialBackoffReconnectPolicy`), `ChatMode`, and `PlayerChannelState` where applicable.

| Status | Modules | Notes |
|--------|---------|-------|
| **FULL** | `bukkit`, `velocity`, `bungee`, `nukkit` | `PasswordHasher` + config→`ReconnectPolicy` + `ChatMode` + `PlayerChannelState` where applicable. |
| **FULL network** (nested state kept) | `pnx` | Network helpers adopted (`PasswordHasher`, reconnect policy); nested local player state kept (not swapped to shared `PlayerChannelState`). |
| **FULL network + `ChatMode`** | `folia`, `sponge` | Network helpers + `ChatMode`; local `PlayerChatState` wrappers OK (not full `PlayerChannelState` adoption). |
| **Not** | `mod` | Own `ChatMode` model; divergent API/pipeline (second wave). |
| **FULL** | `CoreNetworkClient` | Engine + ports in client-core; **ALL 7 platform facades delegate fully** (velocity, bungee, nukkit, sponge, folia, pnx, bukkit). Local `ClientChannelHandler.java` deleted on every platform. |
| **FULL** | `ChannelCommandService` | Wired on bungee, nukkit, velocity, sponge, pnx, folia, bukkit. PNX delegates join/leave/reload only — toggle kept local (PNX `chatEnabled` ≠ `ChatMode`). |

#### Next slices

1. ~~`CoreNetworkClient` + `SchedulerBridge` (Velocity-first)~~ **done — all 8 platforms**
2. ~~Format template engine platform migration~~ **done — bukkit/nukkit/velocity/sponge/folia**
3. ~~Wire platforms to `ChannelCommandService`~~ **done — 7/7**

### Recommendation: **Velocity first**, then Bungee, then Bukkit

When consolidating duplicated client transport into `novachat-client-core`, keep this order:

| Order | Module | Why |
|-------|--------|-----|
| 1 | **Velocity** | Smallest pure skeleton (~380 LOC), second-based scheduler (maps 1:1 to a seconds-based `PlatformScheduler`), SLF logger, no pending-request/ConfigSync/Title entanglement. Proves reconnect + handshake end-to-end with minimal platform code. |
| 2 | Bungee | Nearly identical to Velocity; only scheduler/logger/PlatformType differ. Cheap second validation. |
| 3 | Nukkit / PNX / Sponge | Same core; tick vs sleep schedulers exercise adapter. PNX: move chat/title out of `ClientChannelHandler` into registry handlers while swapping engine. |
| 4 | **Bukkit** | Largest delta: pending requests, ConfigSync JSON, Title, channel/admin UX. Migrate by **composition** — thin facade delegates transport to shared runtime, keeps UX helpers local. Highest regression risk; benefits from already-stable helpers. |
| 5 | Folia | Same as Bukkit conceptually but already has `FoliaSchedulerAdapter`; keep async handler dispatch as registry decorator. |
| 6 | mod (`NettyNetworkClient`) | Divergent API/pipeline; either adapt or leave as consumer of shared codecs only. Do **not** block plugin work on mod. |

### Why not Bukkit first?

- Bukkit is the **reference for product features**, but a poor extraction seed: large non-transport surface would tempt pulling UX into client-core.
- Velocity is the **reference for transport purity**. Extract transport patterns from Velocity, then re-attach Bukkit features as handlers.
- Risk: if Bukkit-first, reviewers may “extract” pending-request UX into core by accident; Velocity-first makes the boundary obvious.

### Why not Folia first?

Folia already has a good scheduler port, but async-everywhere + renamed class (`AsyncNetworkClient`) adds noise before the shared state machine is frozen.

### Migration steps per module (template)

1. Add `implementation project(':novachat-client-core')`.
2. Adopt shared config/reconnect/state types; later implement any scheduler/logger/auth ports (~30–80 LOC).
3. Replace local `NetworkClient` body with shared runtime + platform handlers; keep class name as facade if call sites are wide.
4. Delete local pipeline/handshake/reconnect/hash duplication and local `ClientChannelHandler` if unused.
5. Run platform smoke: connect, auth fail, kill backend (reconnect), keepalive under load, disconnect on disable.
6. Bukkit extra: regression on `/nc` channel actions, ConfigSync world maps, titles.

**Reminder:** this migration is entirely on the client side. `novalink-core` is unchanged and must not gain a dependency on `novachat-client-core`.

---

## 6. Proposed interfaces (future plugin runtime)

Package root: `com.nova.chat.client` (implementation under `com.nova.chat.client.netty` if/when Netty engine lands here).

These ports apply only to **NovaChat plugins**. They are not a backend API.

### 6.1 `ClientNetworkEngine` (optional future façade)

```java
public interface ClientNetworkEngine extends AutoCloseable {
    CompletableFuture<Boolean> connect(String host, int port);
    /** Explicit shutdown; cancels reconnect. */
    void disconnect();
    void sendPacket(Packet packet);
    boolean isConnected();
    boolean isAuthenticated();
    PacketRegistry getPacketRegistry();
    PacketHandlerRegistry handlers();
    /** Optional: reset attempt counter after /nc reload. */
    void resetReconnectBudget();
    @Override default void close() { disconnect(); }
}
```

Builder sketch (plugin-only):

```java
ClientNetworkEngine engine = NettyClientNetworkEngine.builder()
    .credentials(authSource)
    .platformType(PlatformType.VELOCITY)
    .scheduler(platformScheduler)
    .logger(clientLogger)
    .packetRegistry(NovaProtocol.createRegistry()) // optional override
    .connectTimeoutMillis(5000)
    .maxReconnectAttempts(10)
    .maxReconnectDelaySeconds(30)
    .usernameTransformer(u -> u) // e.g. u + "@" + instanceId
    .build();
```

**Threading contract:** engine methods are thread-safe; inbound handlers run on Netty event loop unless platform registers wrappers that hop threads. Folia adapter may wrap `PacketHandlerRegistry.register` to `runAsync`.

### 6.2 `PlatformScheduler`

Abstracts reconnect delay and optional async hops. **Seconds-based API** (not ticks) so client-core never multiplies by 20.

```java
public interface PlatformScheduler {
    void runAsync(Runnable task);
    void runAsyncLater(Runnable task, long delay, TimeUnit unit);
    default void cancelAll() {}
}
```

Core reconnect uses only `runAsyncLater`. Core does **not** call `runSync`. Platform packet handlers call sync themselves.

### 6.3 `PacketHandlerRegistry`

```java
public interface PacketHandlerRegistry {
    <T extends Packet> void register(Class<T> type, Consumer<T> handler);
    <T extends Packet> void unregister(Class<T> type);
    void dispatch(Packet packet);
}
```

Pending-request tracking stays in a **Bukkit** (or shared “command UX”) helper that wraps `sendPacket`, not in protocol common.

### 6.4 `AuthCredentialsSource` / `ClientLogger`

Platform adapters map config (standard vs PNX names, username transform). Logging abstracts JUL vs SLF.

---

## 7. Test strategy (client-core, no Minecraft)

All tests live in `novachat-client-core/src/test` with JUnit 5 + AssertJ + Mockito (+ jqwik for backoff properties). **No Bukkit/Velocity/Nukkit deps. No novalink-core deps.**

### 7.1 Unit focus (current + planned)

1. **`ReconnectPolicy` / backoff** — attempts 1..N → delays `[1,2,4,8,16,30,30,…]`; property: always `1 ≤ d ≤ 30`.
2. **`PendingRequestTracker`** — track/complete/fail/cleanup/clear; concurrency.
3. **`PlayerChannelState` / `ChatMode`** — membership and mode transitions.
4. **`ClientConnectionConfig`** — defaults and builder validation.
5. **`PasswordHasher`** — SHA-256 hex vectors (present).
6. **`FormatTemplateEngine` / `LegacyColorCodes`** — placeholder replace, missing-key policy, hex expand/strip (present).
7. Later: handler registry; loopback Netty if `CoreNetworkClient` lands here.

### 7.2 What not to test in client-core

- Player titles, adventure components, world restriction application.
- Config YAML/TOML parsing.
- Backend routing, auth managers, DB (those are `novalink-core` tests).
- Instance detection (test transformer function only: `u -> u+"@node1"`).

---

## 8. Module layout

```text
novachat-client-core/
  build.gradle                 # already present
  DESIGN.md                    # this file
  src/main/java/com/nova/chat/client/
    package-info.java
    network/
      ClientConnectionConfig.java
      ReconnectPolicy.java
      ExponentialBackoffReconnectPolicy.java
      PendingRequestTracker.java
      PasswordHasher.java
      SchedulerBridge.java
      ClientLogger.java
      CoreNetworkClient.java
      CoreClientChannelHandler.java
    state/
      ChatMode.java
      PlayerChannelState.java
    command/
      CommandIntent.java
      CommandResult.java
      PacketSender.java
      ChannelCommandService.java
    format/
      FormatTemplateEngine.java
      LegacyColorCodes.java
  src/test/java/...
```

Do **not** move protocol packets here; keep depending on `novachat-common`.  
Do **not** add a dependency from `novalink-core` to this module.

---

## 9. Non-goals / risks

| Risk | Mitigation |
|------|------------|
| Backend accidentally depending on client-core | Architecture B: `novalink-core` → `novachat-common` only; CI/review reject reverse deps |
| Dragging Bukkit UX into client-core | Velocity-first; core PR rejects player imports |
| Tick vs seconds bugs | Scheduler API in `TimeUnit`; adapters own `*20` |
| EventLoopGroup leak on rapid reconnect | single shutdown path; tests with virtual clock |
| Folia region safety | client-core never touches players; Folia keeps async dispatch decorator |
| mod divergence | second wave; may only share codecs initially |
| Confusing “common” vs “client-core” | common = wire + extensions; client-core = plugin runtime only |

---

## 10. Key recommendations (summary)

1. **Architecture B:** `novachat-common` (protocol/codecs/mentions/extensions) shared by backend + clients; `novachat-client-core` is **plugin runtime only**; `novalink-core` is the Java production backend and does **not** use client-core.
2. **Grow client-core incrementally** — network helpers + `ChatMode`/`PlayerChannelState` adopted per §5.1; optional Netty `CoreNetworkClient` later; do not block backend work.
3. **If consolidating NetworkClient: Velocity → Bungee → other thin clients → Bukkit/Folia (facade + local handlers) → mod last.**
4. **Bukkit-specific pending-request UX / ConfigSync / Title stay in `novachat-bukkit`**, registered as handlers; do not put platform player APIs in client-core.
5. **Test client-core without Minecraft or novalink-core.**
6. Keep public facade names (`NetworkClient`) on platforms during any migration to avoid mass call-site churn.
7. **Next slices (see §5.1):** wire platforms to `ChannelCommandService`; leave **mod** own `ChatMode` as second wave.

---

## 11. Source map (absolute paths)

Canonical “pure” seed (Velocity-first):

- `D:\Project\NovaLink\novachat-velocity\src\main\java\com\nova\chat\velocity\network\NetworkClient.java`
- `D:\Project\NovaLink\novachat-velocity\src\main\java\com\nova\chat\velocity\network\ClientChannelHandler.java`

Feature-rich reference (handlers only):

- `D:\Project\NovaLink\novachat-bukkit\src\main\java\com\nova\chat\bukkit\network\NetworkClient.java`

Other clones:

- `D:\Project\NovaLink\novachat-bungee\src\main\java\com\nova\chat\bungee\network\NetworkClient.java`
- `D:\Project\NovaLink\novachat-nukkit\src\main\java\com\nova\chat\nukkit\network\NetworkClient.java`
- `D:\Project\NovaLink\novachat-pnx\src\main\java\com\nova\chat\pnx\network\NetworkClient.java`
- `D:\Project\NovaLink\novachat-sponge\src\main\java\com\nova\chat\sponge\network\NetworkClient.java`
- `D:\Project\NovaLink\novachat-folia\src\main\java\com\nova\chat\folia\network\AsyncNetworkClient.java`
- `D:\Project\NovaLink\novachat-mod\common\src\main\java\com\nova\chat\mod\network\NettyNetworkClient.java`

Shared protocol (`novachat-common`):

- `D:\Project\NovaLink\novachat-common\src\main\java\com\nova\chat\common\protocol\`

Production backend:

- `D:\Project\NovaLink\novalink-core\`
