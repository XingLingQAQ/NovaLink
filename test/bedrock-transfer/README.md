# Native Bedrock transfer

This entry tests PowerNukkitX 3.0.6 `Player.transfer(InetSocketAddress)` and its native Bedrock `TransferPacket`. It runs two native PNX processes and one NovaLink backend. There is no Velocity Bedrock transport, Geyser, or proxy process.

Both servers use the same SHA-256-pinned `powernukkitx` runtime from `test/versions.lock.ps1`, with distinct game endpoints and NovaLink client identities. The driver reuses `test/bot/pnx-client.js` and the existing `test/bot/package-lock.json` (`bedrock-protocol` 3.61.0, Minecraft 1.26.60). The pinned PNX client uses native NetherNet with HTTP signalling at the game endpoint.

## Run after server coordination

Run from the repository root on Windows with JDK 21, a Gradle launcher JDK, Node 24, and the locked bot dependencies installed:

```powershell
./test/bedrock-transfer/run-bedrock-transfer.ps1 -Jdk21 $env:JAVA_HOME_21_X64 -BuildJdk $env:JAVA_HOME_25_X64
```

This builds only the required PNX/backend artifacts and fixture, skips Gradle tests, and starts the three processes. Every project build directory is redirected under this run's `.e2e-artifacts/bedrock-transfer/<runId>/build`; it does not overwrite shared dependency jars. A new run directory is mandatory. Defaults use game ports 19362/19363, backend TCP 28962 and HTTP/WebSocket 28963. The original `RconA`/`RconB` arguments still reserve 26362/26363 for invocation compatibility; the pinned PNX 3.0.6 jar has no built-in RCON listener. Its commands run through held stdin. A runner mutex and port checks reject overlapping runs. All owned processes are stopped on exit; evidence remains on disk.

The pinned downloader, bot dependency check and fatal-log assertion are reused. The shared `start-server.ps1 -PrepareOnly -LaunchSpec <file>` resolves the pin, writes base configuration and returns a JSON launch specification without starting Java. Existing calls retain their startup behaviour. The native runner applies loopback `pnx.yml`, then starts the specified Java command itself with held stdin and continuous stdout/stderr capture. Loopback configuration uses explicit TCP/UDP ports, builtin signalling, empty ICE/STUN lists, and Spark/metrics disabled. The backend launch is local because the shared backend launcher writes a single-client configuration; this entry requires two authenticated server identities.

The normal entry builds into a new isolated run directory using Gradle's normal incremental and cache behavior. `plan.json` records the Git revision and working-tree status before the build; a changed revision or status during the build aborts. A dirty status identifies paths, not the contents of the changes. Avoid editing source during the build.

For externally supplied artifacts, explicitly identify their origin and revision:

```powershell
./test/bedrock-transfer/run-bedrock-transfer.ps1 -Jdk21 $env:JAVA_HOME_21_X64 -SkipBuild `
  -ArtifactsFile C:/build/artifacts.json -ExternalBuildSource 'CI build 123, revision abc123'
```

The description is recorded as supplied, not verified or attributed to the runner's current revision. `-ArtifactsFile` and `-ExternalBuildSource` are accepted only with `-SkipBuild`.

Startup requires the pinned native/API banner, enabled NovaChat and fixture, signalling on the expected port, and the actual English `nukkit.server.startFinished` message (`Done (<seconds>s)! For help, type "help" or "?"`). ANSI sequences are stripped before matching; a listening socket alone never satisfies readiness. Native `Server.start()` emits this message immediately before its `ServerStartedEvent` and the network STARTED transition. `readiness.json` records these startup markers; it cannot replace any of the 22 gameplay IDs.

## Evidence contract

The traveler starts on A, transfers to B, then returns to A. A witness stays on each server. Every phase checks actual receipt of a unique chat token by the witness on the opposite server, and a reply received by the traveler. Sent rows cannot satisfy receipt assertions.

The console-only fixture exposes these narrow commands through the runner's held stdin pipe:

| Command | Action |
| --- | --- |
| `bttransfer <player> <nonce>` | Calls the production `Player.transfer` API using the peer endpoint from that server's fixture settings. Its console line only records the call. |
| `btprobe <player> <nonce>` | Sends a node-specific message and the real player UUID through `Player.sendMessage`. The driver must receive it on the current connection. |
| `btstate <player> <nonce>` | Reads PNX's online player lookup; records the UUID or absence. |

The bot writes a run-scoped command request into `console/`. The runner validates the node, player, command and nonce before submitting it to stdin. A submission acknowledgement only confirms the write; the bot also waits for a fresh nonce-bearing receipt from that PNX process's stdout. Transfer and probe assertions still require the actual Bedrock packets.

The driver arms the expected endpoint before the console call. Only a decoded `transfer` event with the exact address and port authorizes one new connection. Missing, unsolicited, duplicate or wrong-target packets fail. A closed or unspawned destination fails without retry. The source closes only after the packet is validated, since this version's `Player.transfer` sends the packet without closing its session.

Each new connection must receive its own `start_game` and `spawn`, a destination-only probe, and the same player UUID. After each move, the source's native online lookup must show absence. Read-only production `GET /api/players` and `GET /api/channels/global/members` must show exactly one matching identity in `global`, assigned to the destination client ID. The driver issues `/nc join global` only during initial setup; it does not repair membership after a transfer. REST observation uses a short-lived local test JWT signed by this disposable backend's generated secret; no external credentials are needed.

| Artifact | Purpose |
| --- | --- |
| `plan.json` | Runtime pin, build provenance, endpoints, server identities, names, resource count, ordered steps. |
| `events.jsonl` | Raw transfer, `start_game`, spawn, server text, close, console replies and backend snapshots, with connection IDs. |
| `bot.results.json` | Receiver recordings, all five connection attempts and per-ID evidence. |
| `summary.json` | 22 IDs independently reconstructed from raw evidence, provenance and final pass. |
| `failure.json`, `*.log` | Runner and process diagnostics. |
| `a/launch-spec.json`, `b/launch-spec.json`, `a/readiness.json`, `b/readiness.json`, `console/*.json` | Launch pin, native startup markers and console submission audit. |

`node test/bedrock-transfer/assert-evidence.js <run-directory> [server-jar]` replays the retained directory without using the original machine's paths. It reconstructs IDs from ordered raw events, including connection-specific `start_game`/spawn, nonce-bearing console requests and native log receipts, packet targets, probes, backend snapshots, source absence and sent/received chat. Assertions and connection summaries in `bot.results.json` are not proof. The runtime pin and launch specifications are checked against the verifier's PNX 3.0.6 pin. Supplying a server jar additionally hashes that file against the pin; without it, the summary says the jar was not independently verified. A separately supplied jar verifies those bytes, not which bytes a historical process actually loaded. New runs hash the jar before launch and invoke the verifier with it. Generated connection secrets stay out of the workflow's artifact selection. The workflow is manual-only: `.github/workflows/e2e-bedrock-transfer.yml`.

This is a bot that reconnects after decoding the actual `TransferPacket`; it is not an official Bedrock client test.

Error strings are redacted by exact replacement of this run's `BT_BACKEND_SECRET` and `BT_RCON_PASSWORD` values before recording or printing. The latter holds the disposable server-client password even though control uses stdin. Public process logs receive the same replacement after shutdown; normal transfer and command evidence stays intact. The generated backend config uses `debug: false`.

## Small check without servers

With the pinned PNX jar already cached:

```powershell
./test/bedrock-transfer/verify-contract.ps1 -Jdk21 $env:JAVA_HOME_21_X64
```

This parses this entry's scripts, round-trips a real 1.26.60 transfer packet through the locked protocol codec, checks transfer/spawn failure paths, and compiles the fixture against the pinned native server jar in an isolated artifact directory. It starts no servers and produces no real-server pass summary.

`./test/bedrock-transfer/verify-native-runtime.ps1` separately checks native loopback configuration, strict readiness, held stdin and continuous output capture with an owned echo subprocess. Its console receipt contract rejects submission-only acknowledgements and stale output. It starts no PNX or backend process.

Local native acceptance on 2026-11-02 passed **22/22** required IDs, including the independent evidence verifier. Evidence: `.e2e-artifacts/bedrock-transfer/acceptance-final-baaa0b02/summary.json`, `events.jsonl`, bot recordings and both native PNX logs. Three players had distinct server UUIDs; the traveler retained its UUID through A → B → A. Earlier failed attempts remain recorded. This routing-only entry disables content filtering in its disposable backend config so words in correlation markers are not rewritten. The manual workflow has not been run for this revision.

The retained 2026-11-02 run did not record its source revision or build origin; replay labels it `historical-unrecorded`, not a test of the current checkout. To exercise the verifier's missing/altered-raw negative cases against a retained run without changing it:

```powershell
$env:BT_ACCEPTANCE = (Resolve-Path .e2e-artifacts/bedrock-transfer/acceptance-final-baaa0b02).Path
node --test test/bedrock-transfer/assert-evidence.test.js
```
