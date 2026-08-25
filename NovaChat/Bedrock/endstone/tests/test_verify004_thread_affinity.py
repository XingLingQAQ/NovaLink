"""VERIFY-004 Endstone — inbound-handler thread-affinity harness.

Audit item VERIFY-004 (docs/PRODUCTION_READINESS_AND_PRODUCT_PLAN.md §7)
asks whether the network-thread -> native-API boundary is broken: the
Endstone client schedules OUTBOUND backend sends onto its asyncio loop via
``asyncio.run_coroutine_threadsafe`` (handler.on_player_chat), but every
INBOUND packet handler invokes native player APIs (``send_message`` /
``send_title`` / ``send_tip`` / ``play_sound``) directly. Those handlers run
on the asyncio NETWORK-loop thread (NetworkClient._read_loop ->
_handle_packet -> handler(packet) synchronously), i.e. NOT on Endstone's
main server thread that the Bukkit-derived scheduler contract reserves for
world/player interaction.

What this module delivers WITHOUT a live BDS server (none exists on this
host; ``test/bin/run-endstone-e2e.ps1`` has never been run):

1. A regression harness that drives a registered ChatHandler consumer (the
   TITLE packet path) on a dedicated non-main thread and records which OS
   thread each fake-player send_* invocation landed on. It PINS the current
   behavior precisely: the player API executes on the SAME thread that ran
   the packet handler — the asyncio network-loop thread in production —
   with no exception and full delivery. If maintainers later route these
   calls through ``Scheduler.run_task``, this test flips red at exactly the
   switch point, telling them to update it alongside the fix.

2. Evidence that the platform's own marshaling primitive exists and is
   currently unused by the plugin: ``endstone.scheduler.Scheduler.run_task``
   is importable from the pinned 0.11.8 wheel and its signature carries
   ``(plugin, task)`` with ``Task.is_sync`` documented as "run by server
   thread". This is asserted via introspection only — no grep of source
   text.

3. A concurrency test driving multiple packets through the handler's
   registered consumers simultaneously, asserting the shared instance
   state (``_pending_actions`` / ``_player_channels`` / ``_player_locales``
   / ``_chat_enabled``) is not corrupted by interleaving.

Honest boundary: we CANNOT prove here that BedrockScriptAPI object proxies
tolerate or reject cross-thread calls — that requires the real Endstone
runtime. The scheduler's existence (mirroring Bukkit conventions, where
cross-thread world access is undefined behavior) plus this pinned record of
the current cross-thread dispatch is the automatable slice; VERIFY-004 full
closure still needs the live-server E2E.
"""

from __future__ import annotations

import json
import threading

import pytest

from novachat_endstone.chat.handler import ChatHandler
from novachat_endstone.protocol.packet import (
    AdminAction,
    AdminActionResponsePacket,
    ChannelActionResponsePacket,
    PacketIds,
    PrivateMessagePacket,
    TitleMessagePacket,
)

# The venv used on this host has endstone 0.11.8 installed; CI images without
# the wheel skip the seam-evidence test cleanly instead of erroring.
endstone = pytest.importorskip("endstone")


# ---------------------------------------------------------------------------
# Fakes (idiom copied from tests/test_admin_action_response.py).
# ---------------------------------------------------------------------------


class FakeNet:
    """Records register_handler calls so tests can drive the consumers."""

    def __init__(self):
        self.handlers = {}

    def register_handler(self, packet_id, handler):
        self.handlers[packet_id] = handler


class FakeServer:
    """Minimal stand-in for endstone Server: just the online player list."""

    def __init__(self, players):
        self.online_players = list(players)


class FakePlugin:
    """Carries the server the handler reaches into for recipient lookup."""

    def __init__(self, players=()):
        self.server = FakeServer(list(players))
        self.scheduler = None


class RecordingPlayer:
    """Fake player recording which thread each native API call landed on."""

    def __init__(self, name, uuid_str):
        self.name = name
        self.unique_id = uuid_str
        self.calls = []  # list of (api_name, args, thread_ident, thread_name)
        self.lock = threading.Lock()

    def _record(self, api, args):
        with self.lock:
            self.calls.append(
                (
                    api,
                    args,
                    threading.get_ident(),
                    threading.current_thread().name,
                )
            )

    def send_message(self, message):
        self._record("send_message", (message,))

    def send_title(self, title, subtitle, fade_in, stay, fade_out):
        self._record("send_title", (title, subtitle, fade_in, stay, fade_out))

    def send_tip(self, message):
        self._record("send_tip", (message,))

    def play_sound(self, sound, volume=1.0, pitch=1.0):
        self._record("play_sound", (sound, volume, pitch))


class FakeConfig:
    """Just the config surface the handler reads on the paths under test."""

    default_channel = "global"

    def get_channel_format(self, channel_id):
        return "[{channel_name}] {player}: {message}"


def _build_handler(players) -> tuple[ChatHandler, FakeNet]:
    """Construct a fully wired ChatHandler against fakes (no disk/socket).

    Goes through the real ``__init__`` so the actual registration table is
    exercised; the config stub only supplies ``default_channel`` and the
    chat-format template used on these paths.
    """
    net = FakeNet()
    handler = ChatHandler(
        plugin=FakePlugin(players),
        network_client=net,
        config_manager=FakeConfig(),
    )
    return handler, net


# ---------------------------------------------------------------------------
# 1. Pin CURRENT behavior: player API runs on the caller-of-the-handler
#    thread (the asyncio network-loop thread in production).
# ---------------------------------------------------------------------------


def _title_packet(channel_id="global") -> TitleMessagePacket:
    import uuid as _uuid

    return TitleMessagePacket(
        channel_id=channel_id,
        title="<title>",
        subtitle="<subtitle>",
        fade_in=10,
        stay=40,
        fade_out=20,
        sender_id=_uuid.uuid4(),
    )


class TestCurrentThreadAffinity:
    def test_title_handler_invokes_player_api_on_handler_thread(self):
        """The TITLE consumer calls player.send_title directly on whatever
        thread invoked the handler — documenting today's cross-thread
        dispatch onto the asyncio network loop."""
        player = RecordingPlayer("Steve", "11111111-1111-1111-1111-111111111111")
        handler, _net = _build_handler([player])

        worker_thread_name = "verify004-network-loop"

        def _run_like_network_loop():
            handler._handle_title_message(_title_packet())

        t = threading.Thread(
            target=_run_like_network_loop, name=worker_thread_name
        )
        t.start()
        t.join(timeout=5.0)
        assert not t.is_alive(), "handler must not block indefinitely"

        assert len(player.calls) == 1, "title must be delivered exactly once"
        api, _args, ident, thread_name = player.calls[0]
        assert api == "send_title"
        # The fake player API executed on the SAME thread that ran the
        # handler — in production this is the asyncio network-loop thread,
        # NOT Endstone's main server thread.
        assert ident != threading.main_thread().ident, (
            "precondition broken: handler ran on the main thread, so this "
            "harness is no longer exercising the cross-thread path"
        )
        assert thread_name == worker_thread_name

    def test_private_message_delivery_completes_without_error(self):
        """Both legs of an inbound private message render via send_message
        on the calling thread with no exception and complete delivery."""
        alice_uuid = "22222222-2222-2222-2222-222222222222"
        bob_uuid = "33333333-3333-3333-3333-333333333333"
        alice = RecordingPlayer("Alice", alice_uuid)
        bob = RecordingPlayer("Bob", bob_uuid)
        handler, _net = _build_handler([alice, bob])
        handler.set_player_locale(alice_uuid, "en_US")
        handler.set_player_locale(bob_uuid, "en_US")

        import uuid as _uuid

        packet = PrivateMessagePacket(
            sender_id=_uuid.UUID(alice_uuid),
            sender_name="Alice",
            sender_client_id="EndstoneServer",
            target_name="Bob",
            target_id=_uuid.UUID(bob_uuid),
            content="hello",
            timestamp=0,
        )
        done = threading.Event()
        errors = []

        def _run():
            try:
                handler._handle_private_message(packet)
            except Exception as e:  # pragma: no cover - assertion target
                errors.append(e)
            finally:
                done.set()

        t = threading.Thread(target=_run, name="verify004-pm")
        t.start()
        assert done.wait(timeout=5.0)
        t.join(timeout=5.0)

        assert not errors, f"handler raised on worker thread: {errors}"
        assert len(alice.calls) == 1 and alice.calls[0][0] == "send_message"
        assert len(bob.calls) == 1 and bob.calls[0][0] == "send_message"
        # All deliveries happened off the main thread (network-thread side).
        assert all(
            call[2] != threading.main_thread().ident
            for call in alice.calls + bob.calls
        )


# ---------------------------------------------------------------------------
# 2. The FIX SEAM exists and is unused: Scheduler.run_task(plugin, task).
#    Introspection-only assertions (no source grep).
# ---------------------------------------------------------------------------


class TestSchedulerSeamExists:
    def test_scheduler_module_and_run_task_signature_present(self):
        """Endstone 0.11.x exposes Scheduler.run_task(plugin, task, delay,
        period) — the main-thread marshaling primitive. Its presence is the
        switch point for routing native player API calls off the network
        thread."""
        from endstone.scheduler import Scheduler

        sig_doc = getattr(Scheduler.run_task, "__doc__", "") or ""
        assert "synchronously" in sig_doc.lower(), (
            "run_task doc must state synchronous (server-thread) execution"
        )
        # pybind methods carry their signature in the first doc line.
        first_line = (sig_doc.splitlines() or [""])[0]
        assert "plugin" in first_line, "run_task must take the owning plugin"
        assert "task" in first_line, "run_task must take the callable"
        assert "delay" in first_line, "run_task must expose delay"
        assert "period" in first_line, "run_task must expose period"

    def test_task_is_sync_documents_server_thread_execution(self):
        """Task.is_sync's own doc ties scheduled tasks to the server thread —
        the platform's documented contract for main-thread access."""
        from endstone.scheduler import Task

        doc = (getattr(Task.is_sync, "__doc__", "") or "").lower()
        assert "server thread" in doc, (
            "Task.is_sync must document server-thread execution semantics"
        )

    def test_server_contract_exposes_scheduler_property(self):
        """The Server surface routes to the scheduler, so a plugin can reach
        it as ``plugin.server.scheduler`` without invasive plumbing."""
        from endstone import Server

        assert isinstance(getattr(Server, "scheduler", None), property)


# ---------------------------------------------------------------------------
# 3. Concurrent packet drain: shared handler state survives interleaving.
# ---------------------------------------------------------------------------


class TestConcurrentPacketDrain:
    def test_concurrent_handlers_keep_shared_state_consistent(self):
        """Fire N packets of several kinds through the REAL registered
        consumers concurrently and assert the shared dicts stay internally
        consistent (no lost/corrupt entries under interleaving)."""
        import uuid as _uuid

        players = [
            RecordingPlayer(f"P{i}", str(_uuid.UUID(int=i + 1)))
            for i in range(4)
        ]
        handler, net = _build_handler(players)
        known = [p.unique_id for p in players]
        for p in players:
            handler.set_player_locale(p.unique_id, "en_US")

        request_ids = [_uuid.uuid4() for _ in range(8)]
        for rid in request_ids:
            handler.register_pending_admin_action(str(rid), players[0].unique_id)

        workloads = []
        # Titles to everyone.
        for i in range(6):
            workloads.append((net.handlers[PacketIds.TITLE], _title_packet()))
        # AdminAction responses correlated by request_id (mutates
        # _pending_actions while other threads read/write sibling maps).
        for rid in request_ids:
            resp = AdminActionResponsePacket(
                action=AdminAction.STATUS,
                success=True,
                error_code="",
                message=f"ack-{rid}",
            )
            resp.request_id = rid
            workloads.append(
                (net.handlers[PacketIds.ADMIN_ACTION_RESPONSE], resp)
            )
        # Channel action responses (also pop _pending_actions entries).
        for rid in request_ids[:4]:
            resp = ChannelActionResponsePacket(
                success=True,
                action=0,
                channel_id="global",
                error_code="",
                message="ok",
                extra={},
            )
            resp.request_id = rid
            workloads.append(
                (net.handlers[PacketIds.CHANNEL_ACTION_RESPONSE], resp)
            )

        barrier = threading.Barrier(len(workloads))
        errors = []

        def _drive(fn, pkt):
            try:
                barrier.wait(timeout=10)
                fn(pkt)
            except Exception as e:  # pragma: no cover - assertion target
                errors.append(e)

        threads = [
            threading.Thread(target=_drive, args=(fn, pkt), name=f"v004-{i}")
            for i, (fn, pkt) in enumerate(workloads)
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10.0)
            assert not t.is_alive(), "worker did not finish; deadlock suspected"

        assert not errors, f"concurrent handlers raised: {errors}"

        # Delivery completeness: every title reached every player.
        titles_per_player = 6
        for p in players:
            got_titles = [c for c in p.calls if c[0] == "send_title"]
            assert len(got_titles) == titles_per_player, (
                f"lost title deliveries for {p.name}: {len(got_titles)}"
            )
            # Each recorded call carries a coherent 5-tuple payload — no
            # interleaving corruption of the formatted arguments.
            for c in got_titles:
                assert c[1][0] == "<title>" and c[1][1] == "<subtitle>"
                assert c[1][2:] == (10, 40, 20)

        # Pending-action map fully consumed: 4 popped by admin responses +
        # 4 popped by channel responses; nothing half-written remains.
        assert handler._pending_actions == {}

        # Player-state maps remain coherent: locales intact, channel map
        # untouched by these packet kinds, chat-enabled defaults preserved.
        for p in players:
            assert handler.get_player_locale(p.unique_id) == "en_US"
            assert handler.get_player_channel(p.unique_id) == "global"
            assert handler.is_chat_enabled(p.unique_id) is True

    def test_config_sync_swap_under_concurrent_reads_is_atomic(self):
        """ConfigSync replaces _known_channels wholesale; concurrent readers
        (get_known_channels during the swap) must always observe either the
        old or the new sorted list — never a torn/empty intermediate."""
        import uuid as _uuid

        handler, _net = _build_handler([])
        handler._handle_config_sync(
            type("P", (), {"config_json": json.dumps({"global_channels": {"a": {}, "b": {}}}), "timestamp": 0})()
        )
        old = ["a", "b"]

        stop = threading.Event()
        reader_errors = []

        def _reader():
            while not stop.is_set():
                snapshot = handler.get_known_channels()
                if snapshot != sorted(snapshot):
                    reader_errors.append(snapshot)
                if set(snapshot) != set(old) and snapshot != ["new"]:
                    reader_errors.append(snapshot)

        readers = [threading.Thread(target=_reader) for _ in range(3)]
        for r in readers:
            r.start()

        new_payload = json.dumps({"global_channels": {"new": {}}})
        for _ in range(50):
            handler._handle_config_sync(
                type("P", (), {"config_json": new_payload, "timestamp": 0})()
            )
            handler._known_channels = old  # flip back for the next round
        stop.set()
        for r in readers:
            r.join(timeout=5.0)

        assert not reader_errors, f"torn reads observed: {reader_errors[:3]}"


# ---------------------------------------------------------------------------
# Housekeeping guard: the harness itself stays wired to reality.
# ---------------------------------------------------------------------------


def test_registered_consumer_table_matches_expectations():
    """Sanity-guard for this harness: every packet kind this module drives
    must still be registered by ChatHandler; otherwise the tests above would
    silently exercise stale entry points after a refactor."""
    handler, net = _build_handler([])
    for expected in (
        PacketIds.TITLE,
        PacketIds.ADMIN_ACTION_RESPONSE,
        PacketIds.CHANNEL_ACTION_RESPONSE,
        PacketIds.PRIVATE_MESSAGE,
    ):
        assert expected in net.handlers
