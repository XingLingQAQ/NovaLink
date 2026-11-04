# Authenticated proxy/Bukkit render coordination

Upgrade the proxy and Bukkit plugin together, then enable coordination for each
upgraded downstream. Existing configurations keep the `novachat:main` legacy
path and its cold-start limits. Version support is explicit; a timeout never
turns a configured coordinated peer into a legacy peer.

## Enable an upgraded pair

Generate a random secret of at least 32 UTF-8 bytes and keep it out of source
control, bot plans and published artifacts. Use the same secret on both peers.
It is separate from NovaLink backend login passwords.

Velocity `config.toml`:

```toml
[render-coordination.secrets]
bukkit = "<random shared secret, at least 32 UTF-8 bytes>"
```

Bungee `config.yml`:

```yaml
render-coordination:
  secrets:
    bukkit: "<random shared secret, at least 32 UTF-8 bytes>"
```

Bukkit `config.yml`:

```yaml
plugin-messaging:
  enabled: true
  server-id: bukkit
  render-coordination-secret: "<the same random shared secret>"
```

The proxy map key is the downstream server name, not the backend client ID.
Omit a server from the map, and leave its Bukkit secret empty, to retain the
legacy profile. Do not enable the Bukkit coordinated profile against a legacy
proxy: that profile intentionally stops trusting persistent v1 UUID rosters.
Old peers remain usable without configuring new secrets or upgrading together.

## Confirm the connection and decide each message

`novachat:render` uses HMAC-authenticated control frames. The existing
`novachat:main` v1 envelope remains unchanged. Proxy listeners consume both
channels before checking their source; client-originated control traffic never
reaches Bukkit. Replies must arrive from the exact current server connection,
not merely a connection with the same server name or player UUID.

1. `PROBE` carries the player UUID and a fresh proxy challenge.
2. Bukkit returns `HELLO` with a nonce bound to its actual `Player` object.
3. `CONFIRM` echoes both nonces; Bukkit returns `CONFIRMED`. A probe alone
   cannot suppress a newly reconnected direct player's messages.
4. Each eligible proxy render sends `CLAIM` with the packet ID and request UUID.
   Bukkit atomically reserves that recipient in its shared render ledger and
   returns `DECISION`: render on the proxy, or skip because Bukkit served it.
5. After confirmation, a backend packet for that player is deferred **per
   message**, not discarded by a global ownership flag. Bukkit sends a signed
   offer carrying the complete original NovaProtocol packet. The proxy runs its normal
   recipient, active-channel and ignore filters for that player. Eligible
   messages join the same claim/dedup path; filtered messages receive `SKIP`.
   Thus a missing proxy backend copy can still render without bypassing proxy
   filtering. Only the backend handlers can create signed offers; ordinary v1
   downstream traffic cannot invoke this signing path.

Chat, title, item display, mention and private message packets use this path.
Direct players keep their Bukkit backend renderers. Copies and claims share a
512-request recipient ledger. Repeated claims retain their original decision,
including after a lost reply or bridge re-negotiation.

Control frames are 105 bytes: a 73-byte body (`NR`, version, kind, four UUIDs,
packet ID, owner flag), followed by HMAC-SHA256. Only `DECISION` may set the
owner flag. Production offers use `OFFER_PART`: total length, offset and chunk
length precede at most 32650 raw PacketRegistry bytes, keeping every plugin
message at or below 32767 bytes. Complete packets retain the backend's 4 MiB
ceiling. Reassembly accepts reordered/repeated chunks, bounds memory and
expires incomplete transfers. Inner packet/request IDs must match the signed
header. A single-envelope `OFFER` codec is also defined for small v1 payloads.

The MAC covers the entire body and payload and is verified before parsing.
Only the current authenticated backend generation can enter the signed-offer
path. An ordinary v1 packet cannot bypass coordination for controlled players,
even with a stale exclusion roster; its carrier must be the actual current
Bukkit `Player`, not just a matching UUID.

A recovery retry starts a fresh challenge while retaining pending requests and
completed decisions on the same physical connection. Bukkit owns pending
offers at plugin scope, so rebuilding its bridge retains unsent backend-only
messages and their retries. Old `HELLO`, `CONFIRMED`
and `DECISION` frames cannot roll it back. Accepted render callbacks survive
ordinary configuration reloads and handshake recovery; a disconnect or server
switch retires them. Bukkit join/quit retires the old `Player` session.

## Failure and retention boundaries

Upgrade both binaries while leaving coordination disabled, then configure the
matching key on both ends during a quiet/drained period. Keep old pairs on the
legacy profile. Enabling only one end is not a supported rolling activation:
configured proxy peers wait and retry instead of guessing that a silent peer
is legacy. Restore both configurations to legacy if rolling back.

Each player has at most 512 pending proxy renders and 512 Bukkit offers. Bukkit
pending packet bytes and proxy reassembly are each capped at 16 MiB per player;
incomplete assemblies expire after 30 seconds when further chunks arrive.
Recent claims/decisions retain 512 request keys. Full queues reject additional
work with a warning. Packets above the backend's frame limit are rejected.
Pending controls and offers retry while the physical connection remains current. A missing plugin
or mismatched key produces a warning rather than an uncoordinated fallback.

These guarantees cover live connections within the retention and payload
bounds. Process crashes, plugin unloads that destroy the in-memory ledger,
key changes with in-flight messages, and replay beyond the retention window
require a drained transition; this protocol is not durable delivery.
Administrative channel responses keep their existing dispatcher.

## Validate the deployed path

```powershell
./test/joint/run-joint.ps1 -Platforms 'bungee,bukkit,pnx' -ProxyMode relay `
  -Rounds 1 -ScenarioGroups 'core,custom-effects' -RequireRenderCoordination `
  -KeepArtifacts
./test/joint/assert-joint-ci.ps1 -JointRoot .e2e-artifacts/joint `
  -ProxyMode relay -ProxyPlatform bungee -BedrockPlatform pnx `
  -ScenarioGroups 'core,custom-effects' -RequireRenderCoordination
```

Use a fresh `JointRoot` when it already contains summaries. The manual joint
workflow exposes the equivalent `require_render_coordination` input. It
generates private keys per run and requires six additional non-diagnostic
gates: both custom-effect receivers must have matching authenticated sessions
in proxy/Bukkit logs, plus current-session title and chat decisions. Existing
bot recordings still require exactly-once titles, exact timings, intended
delivery and non-target exclusion.

Debug logs expose `NOVA_RENDER_EVENT` JSON records with public generation and
request IDs, never shared secrets or message contents. Logs prove that the new
path was active; deterministic codec/coordinator/renderer integration tests
separately exercise both cold-start arrival orders and stale-session rejection.
