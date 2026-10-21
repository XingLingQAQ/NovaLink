# JOINT E2E — Coverage Plan for Ten Uncovered Areas

**Status**: plan only. No scenario below is wired into `run-joint.ps1` yet.
**Baseline read**: `test/joint/run-joint.ps1` (4583 lines, working-tree copy),
`test/joint/bots/joint-bot.js` (1305 lines),
`.e2e-artifacts/joint/20261021-113425/joint-summary.json` (lite, 44 assertions),
`.e2e-artifacts/joint/20261021-111749/joint-summary.json` (relay, 49 assertions).
**Product code** was read to decide, per area, whether a failure would be a real
bug or a harness artefact; file:line references are to the current tree.

---

## 0. Ground rules this plan is written against

### 0.1 Observation primitives actually available

| Primitive | What it reads | Proven to work | Caveat |
|---|---|---|---|
| `expect {token}` | receiver's `messagestr` / `text:*` recording | yes (both modes) | token must be **declared before the line arrives** (`expectedTokens` gate in `joint-bot.js` `noteInbound`) |
| `expectAbsent {token}` | same, verdict computed at bot exit | yes | same ordering rule — declare it early |
| `expectEvent kind=title` | `set_title_text` / `set_title_subtitle` raw packets (Java), `set_title` (Bedrock) | yes (`bob/expect-mention-title` PASS, evidence `title: E2ELiteAlice`) | Java text is `title: X` / `subtitle: X`; Bedrock is the raw locale-rendered string |
| `expectEvent kind=titleTimes` | Java `title_times` event / Bedrock `set_durations` | yes | Java only reliably; Bedrock folds durations into `set_title` |
| `expectEvent kind=sound` | `sound_effect` + `entity_sound_effect` (Java), `play_sound` (Bedrock) | **only on a direct-connect plugin leg** | see area 2 |
| `expectEvent kind=actionBar` | `actionBar` event + raw `action_bar` packet | yes (`expect-join-actionbar` PASS) | renderer output is **locale-dependent** |
| `expectEventAbsent` | effect stream, scoped to a time window | yes | window, not whole recording — a slow arrival evades |
| `expectKicked` | `state.kicked` | yes | channel kick ≠ disconnect; the product only implements the former |
| `expectState` | tab-list name set | yes | a channel kick does **not** remove a player from the tab list |
| `handoffSet` | scrapes `received[].raw` by regex | yes | **bot-side only** — the harness cannot publish a value the bot never saw |
| backend log line | `Read-LogText` | yes | the only evidence for several facts (create id, invite code, mute/ban apply) |
| REST record | `GET /api/messages` via `Assert-RelayAttributionAndDelivery` | yes (relay) | relay only; `database.type: memory` |

### 0.2 Two constraints that shape every sketch below

1. **A bot only records tokens its plan declared.** For every negative assertion
   the observing bot must arm `expectAbsent` / `expectEventAbsent` *before* the
   producing action. Every sketch therefore starts with an "arm" step and uses a
   handoff to release the actor, rather than a fixed sleep.
2. **`handoffSet` only reads the bot's own recording.** It cannot publish a value
   that exists only in the backend log or in a response the client never renders.
   Where an id has to cross that boundary, the *harness* must publish it
   (`Set-Handoff`, `run-joint.ps1:711`), which is a `run-joint.ps1` change.

### 0.3 Config surfaces the prerequisites refer to

* lite channels: `Write-ProxyPluginConfig` `[lite.channels]` (`run-joint.ps1:1634`)
  — already declares **two**: `global` and `local`.
* relay channels: `Write-BackendConfig` `global_channels` (`run-joint.ps1:1072`)
  — declares **one**: `global`. `max_capacity` is **required**
  (`ConfigLoader.parseGlobalChannels`, `ConfigLoader.java:578`); `permission` and
  `slow_mode` are optional.
* relay backend console: `ConsoleCommandHandler` (`StarLink/.../console/ConsoleCommandHandler.java:140`)
  has `title`, `announce`, `create`, `delete`, `mute`, `ban`, `kick`, … The
  backend's stdin pipe is deliberately held open for the process's whole life
  (`run-joint.ps1:561-568`, strong ref at `:568`), so **the harness can already
  write console commands** — it just has no helper that does.

---

## Area 1 — Private messaging

### What to assert

1. **A DM reaches the target.** `expect` on the target's recording, matching the
   receiver-side line. Live string: `E2ELiteBob whispers to you: <token>`
   (`chat.msg.received`, `messages_en_US.properties:188`). This is already
   covered (lite `round1/lite-direct-message`, PASS) — keep it as the control.
2. **The sender gets an echo.** `expect` on the sender, `You whisper to <target>: <token>`
   (`chat.msg.sent`, `:187`). Already covered in lite.
3. **A third party sees nothing.** `expectAbsent` on a third bot's recording for
   the DM token. **Not covered anywhere.**
4. **`/nc r` replies to the last partner.** After receiving a DM, the target runs
   `/nc r <reply-token>`; the *original sender* must record
   `<target> whispers to you: <reply-token>`, and **nobody else** may.
   `replyTargets` is populated on receive (`PrivateMessageService.java:156`).
5. **Offline-target refusal is rendered to the sender.** `/nc msg NoSuchPlayer <token>`
   must produce `Player NoSuchPlayer is not online`
   (`chat.msg.offline`, `:193`) in the *sender's* recording, and the DM token
   must not appear anywhere.
6. **relay only, bonus**: the refusal is a `reason=private_message` response
   (`PrivateMessageHandler.sendError`, `:371-385`) that bypasses
   `ChannelResponseDispatcher` and goes to `PrivateMessageService.renderError`
   — so it is *not* subject to the `pending == null` dropout that hides player
   moderation refusals (`ChannelResponseDispatcher.java:72`). Asserting it here
   also proves that bypass.

### Why it can fail

* **Recipient widening.** `handlePrivateMessage` must emit at most two lines. If
  a future change routed DMs through the channel fan-out (`MessagePipeline`) or
  dropped the `isLocalOnline` predicate, the DM would be rendered to every player
  whose state matched — a privacy defect. The third-party `expectAbsent` is the
  only assertion that catches it.
* **Cross-process leakage.** In lite, `ProxyChatCore.handlePrivateMessage`
  renders locally *and* the packet is **not** `broadcastDownstream`ed (only
  `ChatMessagePacket` is, `ProxyChannelHandler.java:308-313`). If someone ever
  adds DM to the downstream broadcast, the sub-server's `handlePrivateMessage`
  would re-render it. Third-party absence on the direct-connect bot catches it.
* **Offline refusal regressing to silence.** `renderError` returns
  `Optional.empty()` when `senderId` is missing or the sender is not local
  (`PrivateMessageService.java:193-196`). Both lite and relay attach `senderId`
  today (`LocalAuthority.java:1264`, `PrivateMessageHandler.java:378`); dropping
  it would turn "target offline" into a silent no-op, which is exactly the class
  of bug the moderation-refusal finding already documented.
* **`/nc r` retargeting.** `recordConversation` is keyed per player and overwritten
  on *both* roles. A bug that records the partner on the echo side only (or on
  the wrong side) makes `/nc r` reply to the sender themselves; the assertion
  "original sender receives it, third party does not" catches that.

### Topology

**Both.** lite exercises `LocalAuthority.handlePrivateMessage`
(`LocalAuthority.java:1205`) and relay exercises `PrivateMessageHandler`
(`PrivateMessageHandler.java:207`). They are different implementations of the
same contract — this area is exactly where a parity split hides.

### Prerequisites

* **A third bot in the same channel.** lite already has three processes
  (`alice`, `bob`, `downstream`); relay has `java`, `bedrock`, `mod`. **No new
  bots needed.**
* The third bot must be able to *see* the DM if it leak — i.e. it must be a
  receiver of the same transport. `downstream` (lite) and `mod` (relay) are
  direct-connect on the Java leg, so a leak through plugin messaging would be
  visible to them. That is the interesting case.
* Tokens must be pre-armed by the observer (rule 0.2.1).

### Gating vs diagnostic

**Gating** for 1-5. All five are plain chat text on a recording that already
exists; nothing here is inferred.

**Diagnostic** for one sub-case: "the DM reaches a target that is on a *different*
leg". In lite the authority resolves names against the proxy roster only
(`LocalAuthority.findOnlineByName`), so a proxy bot DMing the direct-connect bot's
name is refused as offline — which is correct for lite's model but is a
topology-shape statement, not a product defect. Record it, do not gate it.

### Plan sketch

```
# observer (downstream / mod) — arm FIRST
- id: arm-dm-absent        action: expectAbsent   token: {{runId}}-r{{n}}-dm-leak
- id: arm-reply-absent     action: expectAbsent   token: {{runId}}-r{{n}}-dm-reply
- id: publish-ready        action: handoffSet     key: ready   pattern: '^spawned$'

# sender (alice / java)
- id: wait-ready           action: handoffGet     key: ready
- id: settle               action: wait           ms: 2000
- id: send-dm              action: cmd            text: "/nc msg E2ELiteBob dm {{runId}}-r{{n}}-dm"
- id: expect-echo          action: expect         token: "You whisper to E2ELiteBob"
- id: send-offline         action: cmd            text: "/nc msg NoSuchPlayer dm {{runId}}-r{{n}}-dm-offline"
- id: expect-offline       action: expect         token: "is not online"

# target (bob / bedrock)
- id: expect-dm            action: expect         token: {{runId}}-r{{n}}-dm
- id: reply                action: cmd            text: "/nc r dm {{runId}}-r{{n}}-dm-reply"
- id: expect-echo          action: expect         token: {{runId}}-r{{n}}-dm-reply
```

Orchestrator side (no new helper needed):

```
Assert-Delivered  …/dm-third-party-clean  $dmToken      $observerRes   # must be FALSE
Assert-Absent     …/dm-reply-third-party  $replyToken   $observerRes
Assert-Delivered  …/dm-offline-refused    'is not online' $senderRes
```

### Unobservable today

* **Whether the *target* is told it was ignored.** The DM-toward-ignorer path
  deliberately answers NC-404 `not_online` (privacy split,
  `PrivateMessageHandler.java:244-263`). That is indistinguishable from a genuine
  offline target — by design — so it cannot be a separate assertion; assert the
  *refusal*, not its reason.
* **`/nc r` immediately after reconnect.** `onPlayerQuit` clears the reply map
  (`PrivateMessageService.onPlayerQuit`), and the joint bot has no reconnect step
  (see area 9).

---

## Area 2 — Mentions (title + sound on the mentioned player only)

### What to assert

1. **The mentioned player gets a title naming the mentioner.**
   `expectEvent kind=title match=<mentioner name>`. **Already gated in lite**
   (`bob/expect-mention-title`, PASS).
2. **The mentioner does not notify themselves.** `expectEventAbsent kind=title
   match=<own name>`. **Already covered** (`alice/alice-no-self-mention-title`).
3. **Nobody else gets a mention notification.** A third bot in the *same* channel
   must record no title naming the mentioner and no mention sound.
   **Not covered.**
4. **A player whose active channel is a DIFFERENT channel is not pinged.**
   `expectEventAbsent kind=title` on that player. **Not covered, and this is the
   highest-value assertion in the whole plan** (see "Why it can fail").
5. **The sound plays on the mentioned player.** `expectEvent kind=sound
   match=experience_orb`. Today: lite `bob/expect-mention-sound` is
   `diagnostic` and FAILS. **See "Unobservable today" — the current failure is
   partly a stale diagnosis and partly a real routing loss.**

### Why it can fail

* **Named mentions are resolved network-wide, not per channel.**
  `MentionNotifier.createMentionPackets` resolves each `@name` through
  `PlayerResolver` and only filters self and duplicates
  (`MentionNotifier.java:97-116`). Neither lite
  (`LocalAuthority.resolvePlayerUUID` → `findOnlineByName`, proxy roster only,
  `:381-384`) nor the backend (`MentionResolver.resolvePlayerUUID` scans **all**
  cached player states, `:335-346`) consults channel membership. Delivery has the
  same gap: `ProxyChatCore.handleMention` checks server scope and ignore, never
  `state.getActiveChannel()` (`ProxyChatCore.java:413-433`), and
  `MentionResolver.deliverMention` checks only that a cached state and an active
  connection exist (`:298-327`).
  → **A player in channel B is pinged by a mention in channel A, as long as the
  message itself was delivered in A.** That is a real defect and assertion 4 is
  the only thing that catches it. It needs a second channel, nothing else.
* **`@all` uses a different, correct expansion.** `createAllMentionPackets` is
  fed `recipientClientIds` / `activeMembers` (`MentionResolver.java:176-182`,
  `LocalAuthority.java:369-376`), i.e. who actually received the fan-out. The
  asymmetry between `@name` (network-wide) and `@all` (channel-scoped) is itself
  evidence that the `@name` behaviour is unintended — assert both to pin it.
* **Dedup window collapses legitimate notifications.** `notifyOrSkip` suppresses
  repeats within 3 s per (mentioned, mentioner) pair
  (`MentionNotifier.java:31`, `:182-196`). A plan that mentions the same player
  twice inside 3 s will see only one title; a bug that *widened* the key (e.g.
  keyed on the mentioned player alone) would silently drop a second mentioner's
  notification. Assertion: two different mentioners within 3 s must both notify.
* **Sound id resolution.** `VelocityProxyPlatform.toResourcePath` (`:159-178`)
  maps `ENTITY_EXPERIENCE_ORB_PICKUP` → `entity.experience_orb.pickup`, so the
  resource id *is* valid. The remaining loss is routing, not naming (below).

### Topology

**Both**, with different force:

* **lite** — the mention is produced by the proxy authority and rendered by the
  proxy for proxy-connected players; a direct-connect downstream player is
  **never** notified because `handleLite` broadcasts only `ChatMessagePacket`
  downstream (`ProxyChannelHandler.java:306-313`). So in lite the mention's
  reach is exactly the proxy roster — assertions 3/4 are strongest there.
* **relay** — the backend emits `MentionPacket` to the mentioned player's own
  cached connection (`MentionResolver.deliverMention`). A **direct-connect**
  player therefore gets the packet in the sub-server plugin, where
  `NetworkClient.handleMention` renders title **and** `playMentionSound`
  (`NetworkClient.java:339-378`). This is the only topology where the sound is
  reachable at all (see below).

### Prerequisites

* A **second channel** for assertion 4: lite has `local` already; relay needs a
  second `global_channels` entry (`max_capacity` required).
* Assertion 3 needs a third in-channel observer: lite `downstream` works only for
  the *channel-scoped* case (it is not proxy-visible); relay `mod` works.
* Assertion 5 (sound) needs the mentioned player to be **direct-connect on a
  Bukkit-family leg** — relay + `bukkit`/`folia` behind the proxy, with `mod`
  (or a second direct java bot) as the mentioned player.

### Gating vs diagnostic

* 1, 2: **gating** — already proven, keep.
* 3, 4: **gating**. Both are pure effect-absence on recordings that exist. 4 is
  expected to be **RED today**; it should gate anyway, because a red that names a
  real defect is the point of the harness. If the team decides the network-wide
  ping is intended, flip it to diagnostic *with the reason written down* rather
  than deleting it.
* 5 (sound): **diagnostic until one sound packet is observed on any platform**,
  then gating. Justification below.

### Unobservable today

* **Mention sound from a proxy-connected player.** Velocity's
  `ConnectedPlayer#playSound` routes the sound through the player's backend
  server entity and returns early when the client is client-only / the player has
  no server. The joint bot instruments all three shapes (`sound_effect`,
  `entity_sound_effect`, raw `sound_effect`-named packet probe,
  `joint-bot.js:636-669`) and the 2026-10-21 lite run recorded **zero** sound
  packets at the mention instant. So: not observable in lite, and not observable
  for a proxy-connected player in relay either.
* The comment at `run-joint.ps1:2630-2641` blames
  `DEFAULT_SOUND` not being a registry id. **That half is stale**:
  `toResourcePath` maps it explicitly. Only the routing cause survives. The
  comment should be corrected when the assertion is revisited.
* **PMMP's mention uses different timings from Java's** — `10, 40, 20`
  (`ChatHandler.php:599-605`) vs `10, 40, 10` (`MentionNotifier.DEFAULT_*`). If a
  Bedrock bot is the mentioned player, `titleTimes` cannot be used to tell a
  mention title from a `/nc title`; on Java it can (40 vs 70). Not a blocker, but
  do not write a timing assertion that assumes parity.
* Nukkit's mention renders title **and an action bar** (`ChatInterceptor.java:471-482`)
  but no sound; Bukkit renders title + sound. A cross-platform "mention always
  pings audibly" claim is not observable.

---

## Area 3 — Title packets (fadeIn / stay / fadeOut carried through)

### What to assert

1. **The title and subtitle arrive on the receiving player.**
   `expectEvent kind=title match='title: <token>'` and
   `match='subtitle: <token2>'`.
2. **The timings arrive and match the packet defaults.**
   `expectEvent kind=titleTimes match='fadeIn=10 stay=70 fadeOut=20'`
   (`TitlePacket` constructor defaults, `TitlePacket.java:46-48`).
   **The 70 is the discriminator**: mention titles are `stay=40`, so a
   `stay=70` row proves the title came from a `TitlePacket`, not from a mention
   path. The live relay recording already contains both shapes.
3. **A player in another channel does not see it.**
   `expectEventAbsent kind=title match=<token>` on a player whose active channel
   is the other one. Both renderers do filter: Bukkit by
   `state.getActiveChannel()` (`NetworkClient.java:253`), proxy likewise
   (`ProxyChatCore.java:340`).
4. **The title is not duplicated** for a proxy-connected player (proxy renders
   it, sub-server must exclude that player: `NetworkClient.handleTitle(packet,
   excluded)` `:249`).

### Why it can fail

* **Timing pass-through is spread over four layers.** `/nc title` →
  `AdminActionPacket` extras (`TitleCommand.java:80-88`) → backend
  `AdminActionHandler.applyIntExtra` (`:493-496`) → `TitlePacket` fields →
  proxy/plugin `showTitle(player, title, subtitle, fadeIn, stay, fadeOut)`. Any
  layer that drops the extras falls back to the packet defaults **silently** —
  the title still shows, so only a `titleTimes` assertion catches it.
* **No client sets the extras.** See "Unobservable today" — this is why the
  assertion is pinned to the defaults rather than to custom values.
* **Cross-channel bleed on the title path is the mirror of area 2's bug.** If the
  active-channel filter were dropped, a title would reach every player on the
  leg. Assertion 3 catches it; it is cheap because it reuses the same run.
* **Duplicate render.** In lite the proxy shows the title and also sends it
  downstream with the proxy players excluded. Dropping the exclusion list shows
  it twice. `expectEvent` matches the *first* row, so this needs a count, not a
  presence check (see "harness note" below).

### Topology

**relay only** — and only via the backend console.

* lite: the proxy's `ProxyCommandCore.SUBCOMMANDS` has no `title`
  (`ProxyCommandCore.java:54-56`), so a proxy player's `/nc title` falls through
  `default -> handleChannelMessage` and is read as a message to a channel named
  "title". A direct-connect player's `/nc title` stops at `checkConnection()`
  because lite starts no backend. **No title can be produced in lite.**
* relay via RCON **does not work either**: `TitleCommand.isPlayerOnly() == false`
  and it sets `CONSOLE_SENTINEL_UUID` (`TitleCommand.java:52-58`, `:76-84`), but
  `AdminActionHandler.handleTitle` is gated on
  `permissionManager.hasSuperAdminSession(playerId)` (`:391`) and the console
  has no session. RCON title is therefore NC-403 by design.
* **Working path**: the backend's own console has `title <channel> <text> [subtitle]`
  (`ConsoleCommandHandler.handleTitle`, `:634`) and it constructs the packet with
  the console sentinel **without** a session check (`:646`). The harness already
  holds the backend's stdin open; it needs a writer.

### Prerequisites

* **NEW: `Send-BackendConsole` helper in `run-joint.ps1`** — write a line to
  `$proc.StandardInput` and wait for a marker in `backend/stdout.log`. This is a
  `run-joint.ps1` change (blocked in this task). It is also the prerequisite for
  areas 5, 6 and 10's operator half, so it is worth building once.
* A second channel (assertion 3).
* Two observers on different legs (assertion 4) — relay `mod` is direct-connect,
  the java bot is proxy-connected.

### Gating vs diagnostic

* 1, 2, 4: **gating**. Timing defaults are a stable, documented contract
  (`TitlePacket` fields), so a change there is a real change.
* 3: **gating** — same reasoning as area 2.4.
* **Custom timings: cannot gate** (see below).

### Unobservable today

* **Non-default fadeIn/stay/fadeOut.** No client sends the extras:
  `TitleCommand` sets only `title`/`subtitle` (`:87-88`); the backend console
  `title` command accepts no timing arguments (`:634-658`). The only other
  generators (`LiteBroadcasts.title`, `MentionNotifier` constants) use fixed
  values. Until some command exposes `fadeIn=…`, an assertion on custom timings
  would be asserting a value the harness itself chose — vacuous. Pin the
  assertion to `10/70/20` and state that it proves *pass-through*, not
  *configurability*.
* **Subtitle vs title ordering.** `title.js` emits `times → text → subtitle`, and
  the bot attaches the most recent `titleTimes` to the next text row
  (`joint-bot.js:607-632`). An assertion should read `titleTimes` as its own
  row, not infer from correlation.
* **Duplicate title count.** `expectEvent` returns on first match; there is no
  `expectEventCount` primitive. Duplicate titles are therefore not assertable
  with the current vocabulary — they would need a new step action or an
  orchestrator-side count over `effects[]` (which *is* available offline:
  `results.effects` is written out). Recommend the offline count, in
  `Assert-BotExpectations`-adjacent code, rather than a new bot step.

---

## Area 4 — Action bar (channel status bar after join / leave)

### What to assert

1. **Join flashes the bar naming the joined channel.**
   `expectEvent kind=actionBar match='Current channel: global'`.
   **Already gated** (`alice/expect-join-actionbar`, `bob/expect-join-actionbar`).
2. **Leave flashes the bar naming the REMAINING channel.**
   After `/nc leave <second>`, `expectEvent kind=actionBar match=<remaining channel>`.
   **Not covered.** `sendLeaveChannelStatusBar` resolves
   `state.getActiveChannel()` *after* the local leave, which falls back to the
   first remaining joined channel (`PlayerChannelState.leaveChannel`, `:126-137`).
3. **No invented channel.** If the player leaves their only channel, no bar is
   emitted (`ProxyChatCore.java:1064-1070` returns when `channelId` is null).
   `expectEventAbsent kind=actionBar` within a window.
4. **The bar is not sent to anyone else.** `expectEventAbsent kind=actionBar`
   on the other bot.

### Why it can fail

* **Wrong channel named.** The leave path reads the active channel *after* the
  optimistic local mutation (`ChannelCommandService.leave` → `state.leaveChannel`).
  If the fallback picked "the first joined channel" in an order that no longer
  matches `default_channel`, the player is told they are in a channel the
  authority does not agree with. That is precisely the class of bug the join
  assertion cannot see, because on join the active channel is the one just set.
* **Bar attributed to the wrong player.** `sendLeaveChannelStatusBar(UUID)` looks
  the player up and resolves *their* state; a copy-paste that passed the
  requester's id from a shared adapter would flash the wrong player's bar.
  Assertion 4 catches a broadcast; a mis-target needs a second bot with a
  different channel — worth adding as its own step.
* **Locale regression is not a bug, but it breaks assertions.** The Bedrock run
  recorded `§7当前频道：§bglobal §7（Channel mode）` while the Java run recorded
  `Current channel: global (Channel mode)`. The bar text comes from
  `PlayerMessages.currentChannelBar(playerId, …)` in the *player's* locale. A
  `match='Current channel'` assertion on a Bedrock bot is a **false failure**.

### Topology

**Both.** lite's `LocalAuthority.leave` → dispatcher → adapter → bar
(`ProxyChatCore.java:1053`); relay's bukkit plugin runs the same shared
dispatcher (`NetworkClient` → `dispatcher.handle` → `BukkitChannelResponseAdapter`).

### Prerequisites

* A second channel so "leave" leaves something behind: lite `local` (present),
  relay needs a second `global_channels` entry.
* `default_channel` must be known to the plan so the expected remaining name is
  the config value, not a guess.
* Bedrock observers: match on the **channel id substring** (`global`), not the
  English label, or restrict the assertion to Java observers.

### Gating vs diagnostic

* 1: **gating** (already).
* 2: **gating**. The expected remaining channel is config-derived and exact.
* 3: **diagnostic**. "No bar at all" is a weak signal — it also passes when the
  bar is lost for an unrelated reason. Record it; gate the positive in 2 instead.
* 4: **gating** for the broadcast case.

### Unobservable today

* **Which renderer produced the bar.** Java records two sources (`systemChat`
  and the raw `action_bar` packet) and both fire for the same bar; the run
  evidence shows one of each for one join. A count-based duplicate assertion
  would need to treat the two `source` values as one logical bar — the bot tags
  `data.source`, so an offline assertion can filter on it, but a bot-side
  `expectEvent` cannot.
* **Timing/duration of the bar.** Not sent — `showChannelStatusBar` is a
  one-shot with no durations (`VelocityProxyPlatform.java:222-233`).

---

## Area 5 — Channel isolation

### What to assert

1. **A line in channel A does not reach a player whose active channel is B.**
   `expectAbsent` for the A-token on B's recording. **Not asserted anywhere.**
2. **A line in channel A DOES reach another player whose active channel is A.**
   The control that makes 1 non-vacuous.
3. **The sender still reaches everyone they should** — i.e. the A-subscriber
   receives, and the *speaker's own* echo (if any) is exactly one line.
4. **Backend-side corroboration (relay)**: `GET /api/messages` shows the message
   persisted once for the run's token with the expected `channelId`
   (`Assert-RelayAttributionAndDelivery` already knows how to read this).
5. **Rebroadcast scope**: in lite, the proxy pushes chat downstream to every
   server that has players (`ProxyChannelHandler.broadcastDownstream`). The
   sub-server must filter by active channel. Assert B-observation on the
   direct-connect bot too, so the exclusion is proven on both renderers.

### Why it can fail

* **Two independent filters, either can be wrong.** The proxy filters by
  `channelId.equals(state.getActiveChannel())` (`ProxyChatCore.java:305-309`);
  the sub-server filters again on its own state (`ChatInterceptor.java:253-263`).
  One of them silently dropping the check means a cross-channel leak.
* **Backend recipient selection does not filter by channel at all.** For a GLOBAL
  channel `calculateRecipients` returns **every authenticated connection**
  (`MessagePipeline.java:501-517`). Correctness rests entirely on each platform
  filtering at render time. A new platform adapter that renders what it receives
  leaks every line to every player — and no current assertion would notice,
  because the existing chat assertions only prove *arrival*, never *exclusion*.
* **`/nc <channel> <message>` vs plain chat.** `ProxyCommandCore.execute`
  treats an unknown subcommand as a channel name (`:120`). A typo'd subcommand
  therefore posts to a channel instead of erroring. Isolation assertions should
  use *both* the plain-chat path and the `/nc <channel> msg` path, because they
  reach the fan-out through different entry points.
* **`scopeServers` mishandling.** In relay, `handleIncomingMessage(packet,
  scopeServers)` skips players outside the packet's server scope
  (`ProxyChatCore.java:485-499`) — the empty-set-means-nobody convention is easy
  to get backwards.

### Topology

**Both, and this is the cheapest high-value area.** lite needs **no config
change** (`local` is already declared in `[lite.channels]`). relay needs one
extra `global_channels` entry.

### Prerequisites

* lite: none. Two proxy bots can `/nc join global` / `/nc join local`.
* relay: a second `global_channels` entry with `max_capacity` (required).
  Optionally a `format.channels` entry on the proxy so the line is formatted
  distinctly, but a token is sufficient.
* Both bots must join their channel and **settle** before the control line, or
  the assertion races the join ack. The moderation group already uses
  `expect {token: 'Joined channel'}` as a deterministic settle
  (`run-joint.ps1:3913`) — reuse that idiom.

### Gating vs diagnostic

**Gating**, all of it. This is the single most valuable *new* gating group:
it converts an untested invariant into a red/green on both topologies, and the
negative half is not vacuous because the control line proves delivery works.

### Unobservable today

* **A message in a channel nobody declared.** lite answers NC-404 locally
  (`lite-undeclared-channel-rejected` covers it); relay answers NC-404 from the
  backend. One assertion per topology, already partly covered — do not duplicate.
* **Whether the *sender* is a member of the channel they posted in.** In relay
  this is gate 4c (`SENDER_NOT_IN_CHANNEL`, `MessagePipeline.java:290-307`,
  added by `2b7c86f`); in lite there is no membership check on the send path at
  all (`LocalAuthority.handleChatMessage` checks existence, permission, mute,
  slow-mode — not membership). So "a non-member cannot post" is **topology-
  dependent and would gate differently per mode**; keep it out of this area and
  treat it as its own parity finding.
* See §11 "harness defects" — `JOINT-SCENARIO-COVERAGE.md:53` still claims
  `MessagePipeline` has no membership check; that is stale as of `2b7c86f`.

---

## Area 6 — Channel lifecycle (create → invite → accept → read/write)

### What to assert

1. **Create returns a channel id**, and the creator becomes `CHANNEL_ADMIN`
   (`ChannelActionHandler.handleCreate:384-430`, `grantChannelAdmin` at `:410`).
2. **Invite produces a code** and is refused for a non-admin
   (`handleInvite:456-458`) and for a non-private channel (`:451-453`).
3. **Accept puts the invitee into the channel**: `upsertPlayerState(...,
   channelId, ...)` (`handleAccept:531`) so the invitee's active channel is the
   private channel, and `PrivateChannelManager` logs the join
   (`:371`).
4. **The invitee can read and write it.** The creator's line reaches the
   invitee; the invitee's line reaches the creator. `expect` on both.
5. **A player with neither invite nor password is refused.**
   `/nc join <id>` → password mismatch → NC-402
   (`PrivateChannelManager.validateAccess:267-274`), rendered to the joiner.
6. **A cross-client join is refused even with the right password.** Private
   channels are client-bound (`validateAccess:256-265`,
   `InvitationManager.acceptInvitation:214-219`). A player on a different leg
   gets "Cannot join private channel from different client".

### Why it can fail

* **The `clientId` binding is the whole access model and nothing tests it.** Every
  private-channel entry point (join by password, accept by invite, `who`) compares
  `connection.getClientId()` with `channel.getClientId()`
  (`handleJoin:276-282`, `handleWho:892-900`). In a relay topology each leg has
  its **own** client id (`E2E_Relay_<server>` vs `E2E_JobBukkit`), so a regression
  that compared the wrong id — or that let a proxy link's identity stand in for
  the server's — would open private channels network-wide. Assertion 6 is the
  only thing that pins it.
* **Invite codes must be single-use and channel-scoped.** `claimInvitationUse` is
  an atomic claim (`InvitationManager:226-249`). Re-accepting the same code must
  fail (`NC-411` `INVITE_USED`, `ErrorCode.java:52`). A non-atomic regression
  would let two players share one code.
* **Bans must refuse an accept.** `handleAccept` pre-checks `banManager`
  (`:495-511`) specifically so a banned player cannot walk back in on an
  outstanding invite. Nothing tests it today.
* **The creator's admin grant is what makes invite possible.** If
  `grantChannelAdmin` were dropped, invite would answer NC-403 for the owner —
  assertion 2's positive half catches it.

### Topology

**relay only.** lite cannot run any of it:

* `LocalAuthority.handleChannelAction` dispatches
  `JOIN/LEAVE/WHO/MUTE/UNMUTE/KICK/BAN/UNBAN` and everything else falls to
  `default -> NOT_IMPLEMENTED` (`LocalAuthority.java:393-406`) — so CREATE,
  INVITE and ACCEPT answer **NC-501**.
* Even if they did, `ProxyCommandCore.SUBCOMMANDS` has no `create`/`invite`/
  `accept` (`:54-56`), so a proxy player's `/nc create` is interpreted as a
  message to a channel named "create".

### Prerequisites

1. **Two proxy-connected Java bots on the SAME downstream.** The backend's
   "client" is the link identity. A proxy player's packets ride the proxy's
   dedicated link for that server (`E2E_Relay_bukkit`); a direct-connect player's
   ride the plugin's own link (`E2E_JobBukkit`). They are different clients, so
   the invitee must also be proxy-connected **to the same server**.
   → **NEW BOT ROLE needed** (the relay round has exactly one java bot).
2. **The created channel id must cross from the backend to the bots.** Today it
   cannot go through `handoffSet`: **no client renders it.** The response carries
   `password`, `passwordGenerated`, `displayName` (`handleCreate:425-429`), but
   `ChannelResponseDispatcher.handle` has branches only for
   JOIN/LEAVE/WHO/moderation (`:80-125`) and silently returns for CREATE. The
   i18n keys that would render it (`chat.create.result`,
   `chat.invite.code`, `messages_en_US.properties:141-143`) have **zero call
   sites** in any language.
   → Workaround that keeps it runnable: create with an **explicit password**
   (so the harness already knows it), then scrape `channelId` from the backend
   log — `PrivateChannelManager` logs
   `Created private channel: {} (owner={}, client={}, passwordGenerated={})`
   at INFO (`:119`). Same for the invite code:
   `Created invitation {} for channel {} by {} (maxUses={})` (`InvitationManager:127`).
   The harness then publishes both with `Set-Handoff` (harness → bot), which
   needs a small `run-joint.ps1` addition: wait for the log line after RCON/console
   create, regex it, publish.
3. **The create must be issued from a place that can reach the backend.** RCON
   `/nc create` from the bukkit console: `CreateCommand.isPlayerOnly() == true`
   (`CreateCommand.java:45-47`), so the console cannot run it. A proxy player
   cannot either (no subcommand). So the creator is a **proxy-connected java
   bot** running `/nc create` — which requires prerequisite 1 to also be the
   invitee's leg.

### Gating vs diagnostic

* 1, 2, 3, 4, 5: **gating once prerequisites 1-3 exist.** Each is a chat line or
  a backend log line.
* 6: **gating** — it is the security-relevant one, and the refusal is rendered
  to the joiner (`ChannelResponseDispatcher` failure branch →
  `adapter.sendErrorMessage`), so it is observable.
* **The "create result is never rendered" defect: diagnostic and separate.** It is
  a real product defect (keys exist, no call site) but it is not a lifecycle
  assertion; record it as its own `diagnostic` row so it stays visible without
  failing a round that is otherwise correct.

### Unobservable today

* **The created channel's id and password, from the player's perspective.** As
  above: unrendered. Until a client renders `chat.create.result`, a *player*
  cannot learn the id — which means the product feature is unusable without an
  operator reading the backend log. That is a finding, not just a harness gap.
* **The invite code, from the inviter's perspective.** Same
  (`chat.invite.code` unreferenced).
* **Whether the invitee was *told* they were invited.** `handleInvite` creates a
  code and returns it; there is no push to the target. So "invite then accept"
  is really "admin hands a code to a player out of band". Do not write an
  assertion that the target is notified.
* **`/nc delete`.** `handleDelete` exists in the backend (`:812`) but
  `NovaChatCommand` registers no `delete` subcommand
  (`NovaChatCommand.java:45-70`), so delete is unreachable from either topology.

---

## Area 7 — Ignore

### What to assert

1. **B ignores A; A speaks; B does not receive.** `expectAbsent` on B.
2. **A third observer still receives it.** The control that proves A's line was
   sent at all — without it, "B ignored A" and "the channel broke" are identical.
3. **`/nc unignore` restores delivery.** A speaks a second token; B receives.
4. **Ignore suppresses the mention notification too.** A mentions B; B gets no
   title. Both the proxy (`ProxyChatCore.handleMention:424-427`) and Bukkit
   (`NetworkClient.handleMention:357-361`) consult the ignore list.
5. **Ignore is directional.** B ignores A must not suppress A's *view* of B's
   lines. (A second token pair, opposite direction.)
6. **Ignore suppresses the received DM** (`PrivateMessageService.java:152-155`)
   while the *sender still gets an echo* — the asymmetry is deliberate and easy
   to break.

### Why it can fail

* **Ignore is a client-side filter with no backend enforcement on chat.** The
  backend `MessagePipeline` has **no** ignore check on the fan-out path
  (grep: `ignoreLookup` appears only in `MentionResolver` and
  `PrivateMessageHandler`). So correctness depends entirely on each renderer
  calling `isIgnored`. A platform adapter that forgets it re-exposes every
  ignored player — and the existing chat assertions would still be green,
  because they only check delivery.
* **Two different processes hold two different ignore lists.** In lite the proxy
  holds the list for proxy players and the bukkit process for direct-connect
  players; `/nc ignore` writes only to the process that ran it. A player who
  ignores someone and then gets moved to another leg (area 8) loses the filter —
  unasserted, and a plausible real bug.
* **Ignore keyed by name, not UUID.** `IgnoreListService` stores **names**
  (`normalize(targetName)`, `:137`), and the renderers call
  `isIgnored(viewerId, senderName)`. Two players with names differing only by
  case, or a display-name change, break it. Case-insensitive matching is applied
  (`normalize`); a rename is not handled. Low priority but worth knowing.
* **The mention path double-filters but with a different source.** Backend
  `MentionResolver.shouldSkipMention` consults a persisted `ignoreLookup`
  (`:245-258`) *and* the platform consults its in-memory list — so in relay a
  mention is filtered twice. A divergence (in-memory only vs persisted only)
  produces "chat is hidden but the mention still pings", which assertion 4
  catches.

### Topology

**Both.** lite: bob ignores alice, downstream as the control observer — no
prerequisites. relay: java (proxy) ignores bedrock (direct) — the filter runs in
the proxy process; the mod peer is the control observer.

### Prerequisites

* None beyond the existing bots. The ignore command is local-only
  (`IgnoreCommand.java` — "No backend packet"), so no config channel is needed.
* A settle after `/nc ignore` before the actor speaks (the receipt line is
  synchronous, but the debounced save is not — only the in-memory set matters
  here).

### Gating vs diagnostic

**Gating**, all six. Every one has a positive control or is itself a positive
assertion. This is a cheap, fully deterministic group.

### Unobservable today

* **Persistence across a process restart.** `IgnoreListService` writes a JSON
  file with a 500 ms debounce (`:82-84`), but the harness never restarts a
  process mid-round, so persistence is not observable (see area 9).
* **`/nc ignore list` output correctness.** It is local text and *is* observable
  (`IgnoreCommandService.LIST_ARG`), but it is a formatting assertion, not a
  behavioural one; fold it into area 10 rather than duplicating.
* **Whether the backend *would* have filtered it.** There is no backend
  enforcement to observe on the chat path, so "the backend also enforces ignore"
  must not be asserted — it would fail for a reason that is not a regression.

---

## Area 8 — Cross-server player switch

### What to assert

1. **After the switch, the player is still a member of their channel.**
   `/nc who <channel>` from another player must still list them exactly once.
2. **After the switch, their next line still reaches observers.**
   `expect` on an observer that did **not** move.
3. **Nothing is duplicated.** The moved player's line must appear exactly once
   per observer (the switch re-sends a JOIN; a bug that also re-sent the old
   channel's membership could duplicate).
4. **Attribution follows the new server (relay).** The backend stamps
   `PlayerState#clientId` from the connection that carried the JOIN
   (`ProxyServerSwitchCore.handleChannelTransition` comment, `:91-93`), so
   `GET /api/messages` for the post-switch token must name the **new**
   server's link identity. `Assert-RelayAttributionAndDelivery` already reads
   exactly this field.
5. **Diagnostic: the proxy logged the re-request.**
   `platform.logDebug("Requested channel join after server switch: …")`
   (`:110-111`).

### Why it can fail

* **Silent no-op when the new server has no authenticated link.**
  `handleChannelTransition` returns **without doing anything** if
  `getNetworkClientFor(newServer)` is null or unauthenticated
  (`ProxyServerSwitchCore.java:94-97`). The player keeps talking on their old
  attribution; in relay the backend's fan-out is per-connection, so the player
  may still receive GLOBAL chat via the old link while the proxy's
  `getCurrentServer()` says otherwise — a mismatch that produces either lost or
  duplicated lines depending on which path wins. Assertion 2 is the behavioural
  detector; assertion 4 names the cause.
* **The JOIN is the only notification.** There is deliberately no switch packet
  (`ServerSwitchHandler` javadoc, `:17-24`). A regression that reintroduced a
  LEAVE-before-JOIN would drop the membership for the duration of the switch and
  silence the player — assertion 2 catches it only if the line is sent promptly
  after the switch, which is why the sketch uses the `/nc who` result as the
  settle rather than a sleep.
* **First-connection claim.** `claimFirstConnection` is an atomic set add
  (`ProxyChatCore.java:849-851`); on a *second* connection it returns false and
  the transition path runs. If it wrongly returned true (e.g. because
  `firstConnections` was cleared on a transient disconnect), the player would be
  put back into the **default** channel, losing their chosen one. Assertion 1
  with a **non-default** channel is what makes this visible.

### Topology

**relay only.** It needs a proxy with more than one downstream server and the
verbatim `/server <name>` proxy command. Today's default run list has no mod leg,
so `[servers]` has one entry and there is nowhere to switch to.

### Prerequisites

1. **Two downstreams behind the proxy** — `-Platforms velocity,bukkit,fabric,nukkit`
   (or `velocity,bukkit,…,fabric`), so `Write-VelocityToml` writes two `[servers]`
   keys.
2. **A verb to switch**: the java bot sends `/server <other>` through
   `bot.chat` (`sendCommand`). Velocity's built-in `/server` must be enabled; the
   generated `velocity.toml` sets `announce-proxy-commands = false` but does not
   disable the command itself.
3. The mod leg must be a *working* NovaChat client (its own relay link), or
   assertion 4 is meaningless.
4. A non-default channel to be a member of before the switch — i.e. a second
   `global_channels` entry (shared prerequisite with areas 5 and 10).

### Gating vs diagnostic

* 1, 2, 3: **gating.** Behavioural, on recordings that exist.
* 4: **gating in relay**, because the REST record is authoritative and the
  harness already parses it.
* 5: **diagnostic.** `Write-ProxyPluginConfig` sets `debug = false` for relay
  (`:1646`) precisely because packet-level debug floods the log and starves the
  harness's poll. The switch line is `logDebug`, so it is unavailable at the
  configured verbosity. This is the same reason
  `relay-chat-attribution-proxy-log` is diagnostic today.

### Unobservable today

* **The proxy-side decision**, as above: `logDebug` under `debug = false`.
  Enabling debug for one round would be possible but would also re-enable the
  keep-alive flood that the comment at `run-joint.ps1:1273-1284` documents as
  harness-starving. Not worth it; keep diagnostic.
* **Bedrock-side switching.** Velocity does not front Bedrock servers
  (`run-joint.ps1` "Proxy downstreams are Java+Mod only"), so a Bedrock player
  can never switch and this area is inherently Java-only.
* **`ServerPreConnectEvent`-based LEAVE.** The handler was removed
  (`ServerSwitchHandler` javadoc). There is nothing to observe; do not write an
  assertion for a LEAVE that the product no longer sends.

---

## Area 9 — Reconnect

### What to assert

1. **After a disconnect and reconnect, the player is a member again.**
   `/nc who <channel>` lists them.
2. **After the reconnect, their next line reaches observers.**
3. **Which channel they are active in.** With a *non-default* channel chosen
   before the drop, the honest assertion is: does the reconnect restore the
   chosen channel, or reset to `default_channel`?

### Why it can fail

Read from the code, this area is likely to be **RED on the strong reading**, and
the plan should say so up front:

* On disconnect, `ProxyChatCore.onPlayerDisconnect` **destroys** the state:
  `states.removeState(playerId)` and `firstConnections.remove(playerId)`
  (`:779-787`). On reconnect, `claimFirstConnection` therefore returns **true**
  and `joinDefaultChannel` runs (`ProxyServerSwitchCore.java:70-73`), which sets
  `state.setActiveChannel(defaultChannel)`. **The player's previously active
  channel is not restored.**
* Membership *may* come back from persistence: `LocalAuthority.join` calls
  `restoreOnce(playerId)` before recording membership (`:432-437`), and the lite
  storage layer is `memory` in this harness, so within a run it survives only if
  the same process held it — which it does, since the disconnect happens at the
  proxy and the authority is in-process. So in lite: **membership restored,
  active channel reset to the default.** In relay: neither is restored unless a
  JOIN re-establishes it (which `joinDefaultChannel` does, for the default
  channel only).
* The bug this catches: "a player who chose a private/local channel silently
  falls back to global on any network hiccup". That is a real user-visible
  defect, and the existing coverage doc's G14 ("reconnect logic exists") does not
  distinguish membership from active channel.

### Topology

**Both** would work behaviourally, but see prerequisites — the *mechanism* to
reconnect does not exist yet.

### Prerequisites

**Blocked on orchestration.** The joint bot has no reconnect step: the only
disconnect verb is `quit`, which is terminal (`joint-bot.js:1037-1040`). Two ways
to get a reconnect:

* (a) **New bot process with the same username after the first exits.** This is
  expressible with today's plan vocabulary (two roles, same `username`, distinct
  `role` so `handoffPath` does not collide — `joint-bot.js:264`). But it needs
  the orchestrator to start bot 2 only after bot 1 exits, which is a
  `run-joint.ps1` change (`Start-Bot` / `Wait-Bots` are per-role and started
  together, `:4024-4032`).
* (b) **A `reconnect` step in `joint-bot.js`.** Cleaner (one process, one
  recording), but `joint-bot.js` lives under `test/joint/**` and is off-limits in
  this task.

Either way this area **cannot be implemented without touching `test/joint/`**.
Report it as sequence-after rather than inventing a workaround.

### Gating vs diagnostic

* 1, 2: **gating** once the mechanism exists.
* 3: **gating, but the expectation must be written down first.** If the product
  intends "reconnect returns you to the default channel", then asserting
  "restores the chosen channel" would encode a wish, not a contract. The
  recommendation is to gate 1 and 2 and record 3 as `diagnostic` with the
  observed behaviour spelled out, then let the team decide whether the reset is a
  defect.

### Unobservable today

* **The whole area**, until the reconnect mechanism exists.
* **The proxy's `LEAVE`-on-disconnect** is observable in the backend log only
  indirectly (no INFO line for a LEAVE). `notifyBackendDisconnect` sends one
  LEAVE per joined channel (`ProxyChatCore.java:806-820`) with `logout=true`;
  `handleLeave` handles it but logs nothing at INFO. So "the departing player was
  evicted from the backend roster" is only observable via a later `/nc who`.
* **`firstConnections` clearing** is internal state; only its *effect*
  (default-channel reset) is observable.

---

## Area 10 — `/nc who` and `/nc list` correctness

### What to assert

1. **`/nc who <channel>` lists exactly the members.** Exact CSV body, not a
   single-name absence. The body is the second line after the header
   (`chat.who.list_header` / `chat.who.list_body`,
   `messages_en_US.properties:202-203`); the existing relay assertion already
   scrapes it by "the next `messagestr` after `online members`"
   (`run-joint.ps1:3408-3434`).
2. **The count matches the body.** `online members (N)` must equal the number of
   names. `handleWho` derives both from the same `TreeSet` (`:905-920`), so a
   divergence means the count was taken from a different source.
3. **A refused join must not appear as joined in `/nc list`.**
   `/nc list` marks each channel `✓`/`○` from the player's **local** joined set
   (`ListCommandService.formatChannelList:44-68`). **This is expected to be RED
   today** — see below.
4. **After a leave, `/nc who` no longer lists the player** (already partly
   covered on relay via the kick assertion) and `/nc list` flips the marker.
5. **`/nc who` of a channel the requester is not in (relay, private) is refused
   with NC-403** (`handleWho:892-900`) — the cross-client read rule.

### Why it can fail

**Assertion 3 is a live defect, and it is the strongest finding in this plan.**

* `ChannelCommandService.join` performs an **optimistic** local mutation on send
  success: `state.setActiveChannel(channelId)` (`:198`), and `setActiveChannel`
  **adds** to `joinedChannels` (`PlayerChannelState.java:73-79`).
* When the authority refuses, `ChannelResponseDispatcher` calls
  `adapter.rollbackJoin(playerId, attempted, previous)` (`:131-136`), and the
  adapter calls `state.setActiveChannel(previousChannel)`
  (`ProxyChatCore.java:999-1012`). `setActiveChannel` **adds** the previous
  channel; nothing **removes** the attempted one.
* `PlayerChannelState` has `leaveChannel` (which does remove) but the rollback
  path never calls it.
* `ListCommandService.formatChannelList` then **appends joined-but-unknown
  channels** so the player's own view is "always reflected" (`:57-65`).
* → After `/nc join nosuchchannel` is refused with NC-404, `/nc list` still shows
  `✓ nosuchchannel`. The lite run already proves the refusal path executes
  (`round1/lite-undeclared-channel-rejected` PASS, and bob's recording shows
  `Success: Joining channel nosuchchannel...` followed by `Error: [NC-404]`).
  So the phantom membership is already in the recordings of the last run — it
  simply was never asserted.

Other concrete failure modes:

* **Body/count divergence** (`handleWho` builds both from one set, but
  `formatMemberList` re-parses the count string and can render `(0)` + a body, or
  `(N)` + `list_empty`, `WhoCommandService:103-109`).
* **UUID fallbacks in the body.** `handleWho` appends members whose state is not
  cached as raw UUID strings (`:907-917`). A player who joined but whose state
  has not propagated shows as a UUID — visible, but it means a "body contains
  name X" assertion can fail spuriously on a slow run. Assert the *set* after a
  settle, and treat a UUID-looking token as a distinct (diagnostic) finding.
* **lite's `who` is scoped to the proxy roster.** `LocalAuthority.who` iterates
  `platform.getOnlinePlayers()` and intersects with its member set (`:526-534`),
  so a direct-connect player who is a member is **omitted**. That is correct for
  lite's model but means the expected body differs between topologies — write two
  expectations, not one.
* **`/nc list` in relay depends on ConfigSync.** The proxy's
  `KnownChannelRegistry` is filled from `ConfigSyncPacket`; before the first sync
  it is empty and `/nc list` shows only joined channels. A `✓`/`○` assertion that
  runs too early is a false failure. In lite the registry is seeded directly from
  `[lite.channels]` (`NovaChatVelocity.java:464`), so lite is the safer place for
  the list assertion.

### Topology

* **3, 4 (list markers): lite**, because the registry is seeded synchronously
  from config and the optimistic-mutation bug is in the *shared* client core
  (`ChannelCommandService`), so lite proves it for every platform.
* **1, 2, 5 (who): both.** lite for the proxy-scoped body, relay for the
  backend-scoped body plus the cross-client refusal.

### Prerequisites

* **Assertion 3: none.** The existing lite plan already issues
  `/nc join nosuchchannel` (`run-joint.ps1:2644`). Adding `/nc list` after it and
  asserting the refused id is absent needs no new bot, no new channel, no config
  change. It is the cheapest real bug this plan can surface.
* Assertion 1/2 with an exact CSV: a **settle** so the who response has arrived,
  and knowledge of `default_channel` so the expected set is exact.
* Assertion 5: a second channel that is private (i.e. depends on area 6).

### Gating vs diagnostic

* 1, 2, 4: **gating.** Exact strings, existing recordings.
* 3: **gating**, and expect it to be **RED on the first run**. That is the point:
  it is a real defect with a precise reproduction.
* 5: **gating** once area 6's prerequisites exist.
* **UUID-in-body**: **diagnostic** — it is a propagation-timing artefact, not a
  correctness statement.

### Unobservable today

* **`/nc list` channel *display names*.** `formatChannelList` prints the id
  (`&f<channelId>`, `:72`); display names are only used by `who`. Do not assert
  names in list.
* **Who the authority *thinks* is online vs who the proxy thinks.** The two views
  diverge by design in lite (proxy roster) and cannot be compared with the
  current bots — a comparison would need a second `/nc who` from a
  direct-connect player in the same round, which is possible but only meaningful
  on relay.
* **A kicked player's absence from `who` in lite.** lite's `kick` removes from
  the authority's `members` map (`LocalAuthority.java:768-779`), but the lite
  moderation group never executes (documented at `run-joint.ps1:3009-3022`), so
  the kick→who chain is only assertable on relay.

---

## 11. Prerequisites the harness is missing (consolidated)

Ordered by how many areas each one unblocks.

| # | Prerequisite | Unblocks | Cost |
|---|---|---|---|
| P1 | **A second `global_channels` entry in `Write-BackendConfig`** (`capacity`, optional `permission`) | 2.4, 3.3, 4.2, 5, 10.5 | one config block |
| P2 | **A second proxy-connected Java bot in the relay round** (new role in `Invoke-Round`) | 6 (create/invite/accept), 8 (a same-server peer) | new bot process + ~50 lines of plan |
| P3 | **`Send-BackendConsole` helper** — write a line to the backend's held-open stdin, wait for the marker in `backend/stdout.log` | 3 (titles), 6 (console create as an alternative operator path), 10 (operator-driven who) | one helper; the stdin pipe already exists (`run-joint.ps1:561-568`) |
| P4 | **Backend-log → bot handoff**: regex `Created private channel: (\S+)` / `Created invitation (\S+)` after a console/command create, then `Set-Handoff` | 6 | one helper, reuses `Wait-LogMarker` |
| P5 | **A mod (fabric) leg in the relay round** so the proxy has two downstreams | 8 | `-Platforms velocity,bukkit,fabric,nukkit` + a working fabric relay link |
| P6 | **A reconnect mechanism** (second process with the same username, or a bot step) | 9 | **`test/joint/**` change — blocked in this task** |
| P7 | **An `expectEventCount` / offline effect count** (duplicate titles, duplicate bars) | 3.4, 4, 5.3 | can be done **offline** over `results.effects[]` — no bot change |
| P8 | **A sound that survives the proxy** | 2.5 | product/routing, not harness |

**Operator identity**: the harness has three working operator paths today —
RCON on the bukkit console (moderation commands, proven), a proxy-connected
player for `/nc create`/`invite`/`accept` (relay only), and the backend console
(P3, not yet wired). There is **no** path that grants a *player* moderation
authority: `novachat.auth` defaults to `false` and Bukkit's `FALSE` is false even
for ops (`plugin.yml:150-152`; `AuthCommand.java:43-45`), and
`registerClientAdmin` has no production call site. Do not write scenarios that
assume a player super-admin; the existing harness comment
(`run-joint.ps1:3119-3166`) already documents this correctly.

---

## 12. Ranking — which areas would catch a real bug today

| Rank | Area | Why it should be RED/buggy now | Cost |
|---|---|---|---|
| 1 | **10.3 — `/nc list` shows a refused channel as joined** | Code-derived and already present in the last run's recordings: the optimistic `setActiveChannel` is never undone by `rollbackJoin`. Zero prerequisites. | add `/nc list` + one assertion to the existing lite plan |
| 2 | **2.4 — a mention pings a player in another channel** | Neither lite nor backend mention delivery consults the mentioned player's active channel (`ProxyChatCore.java:413-433`, `MentionResolver.java:298-327`), while `@all` *is* channel-scoped — an asymmetry that looks unintentional. | P1 (lite: none, `local` exists) |
| 3 | **5 — channel isolation** | Backend `calculateRecipients` does not filter by channel at all; correctness rests on two independent render-side filters that nothing currently tests. | lite: zero; relay: P1 |
| 4 | **7 — ignore** | Purely client-side filtering; a single forgotten `isIgnored` call re-exposes every ignored player and no existing assertion notices. | zero |
| 5 | **1.3/1.4 — DM third-party exclusion and `/nc r`** | Structurally sound today, so lower RED odds — but it is the only privacy invariant with no negative assertion, and the `/nc r` retargeting path is untested. | zero |
| 6 | **4.2 — leave status bar names the remaining channel** | Distinct code path from the proven join bar, with an active-channel fallback that can name the wrong channel. | P1 |
| 7 | **3 — title timings + title isolation** | Four-layer pass-through with silent fallback to defaults; needs P3 first. | P1 + P3 |
| 8 | **6 — channel lifecycle** | Real, heavy: also surfaces the "create result never rendered" defect on the way. | P2 + P4 |
| 9 | **8 — server switch** | Real (the silent `getNetworkClientFor == null` return) but needs a two-downstream relay topology first. | P1 + P5 |
| 10 | **9 — reconnect** | Likely contradicts the stated requirement (active channel resets to default), but **cannot be expressed** without a `test/joint/**` change. | P6 |

---

## 13. Defects found in the existing harness (report, not fixed here)

These are in files this task must not modify. Each is concrete.

1. **`diagnostic` is silently dropped on a fast `expectEvent`.** `expectEffect`
   sets `diagnostic: step.diagnostic` only in the async `finish()` path
   (`joint-bot.js:428-433`). The immediate-hit path
   (`joint-bot.js:411-416`) pushes an assertion **without** the flag, so an
   effect that arrived before the step ran is recorded as a normal gating
   assertion. `Assert-BotExpectations` then maps it to `in-bot-effect` and it
   gates (`run-joint.ps1:2209-2224`). Any `diagnostic: true` `expectEvent` can
   therefore flip from INFO to FAIL depending on packet timing — including
   `bob/expect-mention-sound` (`run-joint.ps1:2641`), the moment the sound starts
   arriving early.

2. **`JOINT-SCENARIO-COVERAGE.md:53` is stale on the kick.** It states
   "`MessagePipeline` has NO membership check on the send path". HEAD contains
   `2b7c86f fix(starlink): make kick and ban bind on the backend`, which adds
   Stage 4c `SENDER_NOT_IN_CHANNEL` (`MessagePipeline.java:290-307`). The
   "kick does not stop speech" finding must be re-run before it is quoted again.

3. **The mention-sound diagnosis at `run-joint.ps1:2630-2641` is half wrong.**
   It says `VelocityProxyPlatform#playSound` builds a non-registry id from
   `DEFAULT_SOUND`. `toResourcePath` (`VelocityProxyPlatform.java:159-178`) maps
   `entity_experience_orb_pickup` → `entity.experience_orb.pickup` explicitly.
   The surviving cause is the routing through the backend server entity, not the
   naming. The comment will mislead the next investigator.

4. **`no-join-title` has a 2 s window and no anchor.** The step runs immediately
   after `/nc join global` with no wait
   (`run-joint.ps1:2574`), so a title arriving at +2.1 s passes an assertion
   whose stated intent is "no title is sent on a join". Cheap fix: arm the
   absence *after* the join ack and widen the window.

5. **`lite-who-lists-members` asserts `online members (2)`,** a count that
   changes the moment a third proxy player joins. It is correct today but it is a
   brittle token; an exact body assertion (area 10.1) is the durable form.

6. **`round$n/mod/mute-is-channel-scoped` claims "Write-BackendConfig declares
   one channel (global)"** as the *only* blocker (`run-joint.ps1:3509-3514`). That
   is true for relay but not for the harness as a whole — lite's proxy config
   already declares `local`. The reason should be per-topology.

7. **`kick-authority-trace` is hardcoded `pass = $false` with
   `kind = 'diagnostic'`** (`run-joint.ps1:3409-3416`). Honest, but it can never
   change state; if a kick ever gains an INFO log line, nothing will notice. It
   should be computed from the log like the mute/ban ones.

8. **`Assert-Delivered` matches `raw` as well as `text`**
   (`run-joint.ps1:1747-1752`). For effect rows `raw` is the JSON payload
   (`joint-bot.js:143-152`), so a token can satisfy a delivery assertion from a
   structured field the player never saw. Prefer `text` for chat assertions.

9. **`relay-chat-attribution-proxy-log` is structurally unreachable, not just
   unlucky.** It is `diagnostic` because relay runs with `debug = false`
   (`run-joint.ps1:1646`), and the line it looks for is emitted through
   `platform.logDebug`. It can never pass while that flag is off — the same
   limitation area 8 hits. Two assertions carried as permanent diagnostics for
   one config decision is worth stating once, centrally, rather than in each
   scenario's comment.

> **Note on citations**: line numbers in this document were re-verified against
> the working tree at the time of writing. `run-joint.ps1` changed under this
> task (the `probePort` experiment in `Start-PlatformServer` was reverted), so
> every reference above was re-located by pattern and corrected. If the file
> moves again, the assertion **ids** and the function names are the stable
> anchors, not the line numbers.

---

## 14. What this plan does **not** claim

* No scenario here has been executed. Every "expected RED" is a code-derived
  prediction with a file:line anchor, not a measurement.
* Areas 6, 8 and 9 have prerequisites that make them expensive; they are written
  so they can be merged later without re-deriving the analysis, not because they
  are ready to run.
* Nothing here asserts sound, custom title timings, create/invite results, or
  reconnect state as **gating**, because the harness (or, in the create case, the
  product) cannot observe them today. Those four are the honest holes left after
  this plan.
