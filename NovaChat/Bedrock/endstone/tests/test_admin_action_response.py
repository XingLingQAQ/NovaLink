"""
FEATURE-002 tests for the Endstone /nc auth + /nc announce path.

Covers the two guarantees the Endstone client must uphold:

1. The dead 0x0A AnnouncementPacket / handler are fully removed; the backend
   never sends 0x0A and the client no longer registers a handler for it.
2. AdminActionResponsePacket (0x0C) is routed by request_id back to the
   originating player, with the NC-403 + STATUS branch surfacing the
   super-admin guidance instead of generic NC-403 text — mirroring the
   bukkit handleAdminActionResponse / isSuperAdminRequired flow.
"""

from __future__ import annotations

import hashlib
import uuid

import pytest

from novachat_endstone.protocol.packet import (
    AdminAction,
    AdminActionPacket,
    AdminActionResponsePacket,
    PacketIds,
    PACKET_REGISTRY,
)


# ---------------------------------------------------------------------------
# Dead 0x0A Announcement path is gone.
# ---------------------------------------------------------------------------

def test_announcement_packet_id_enum_removed():
    """FEATURE-002: PacketIds.ANNOUNCEMENT must not exist."""
    assert not hasattr(PacketIds, "ANNOUNCEMENT"), (
        "PacketIds.ANNOUNCEMENT should be removed (dead 0x0A path)"
    )


def test_announcement_packet_class_removed():
    """FEATURE-002: AnnouncementPacket class must be gone from the module."""
    from novachat_endstone.protocol import packet as packet_mod

    assert not hasattr(packet_mod, "AnnouncementPacket"), (
        "AnnouncementPacket class should be removed"
    )


def test_announcement_not_in_packet_registry():
    """No decoder is registered for 0x0A; an inbound 0x0A frame must fall
    through to UnknownPacket rather than decode as an Announcement."""
    assert 0x0A not in PACKET_REGISTRY
    assert PacketIds.ADMIN_ACTION_RESPONSE == 0x0C


def test_announcement_packet_not_exported():
    from novachat_endstone import protocol as pkg

    assert not hasattr(pkg, "AnnouncementPacket")
    assert "AnnouncementPacket" not in getattr(pkg, "__all__", [])


def test_chat_handler_no_announcement_registration():
    """The ChatHandler must not register a handler for 0x0A; it must register
    one for ADMIN_ACTION_RESPONSE (0x0C) instead."""
    from novachat_endstone.chat.handler import ChatHandler

    # A minimal fake network client capturing register_handler calls.
    class FakeNet:
        def __init__(self):
            self.handlers = {}

        def register_handler(self, packet_id, handler):
            self.handlers[packet_id] = handler

    class FakePlugin:
        server = None

    net = FakeNet()
    handler = ChatHandler.__new__(ChatHandler)
    handler._plugin = FakePlugin()
    handler._network_client = net
    handler._config_manager = None
    handler._logger = __import__("logging").getLogger("test")
    handler._player_channels = {}
    handler._chat_enabled = {}
    handler._player_locales = {}
    handler._known_channels = []
    handler._pending_actions = {}
    from novachat_endstone.i18n import I18n

    handler._i18n = I18n()
    handler._register_packet_handlers()

    assert 0x0A not in net.handlers, "0x0A handler must be removed"
    assert PacketIds.ADMIN_ACTION_RESPONSE in net.handlers, (
        "ADMIN_ACTION_RESPONSE handler must be registered"
    )


# ---------------------------------------------------------------------------
# requestId correlation in AdminActionResponse.
# ---------------------------------------------------------------------------

def test_admin_action_response_request_id_routing_success(monkeypatch):
    """A successful AdminActionResponse pops the pending request_id and
    delivers the success message to the originating player."""
    from novachat_endstone.chat.handler import ChatHandler

    player_uuid = str(uuid.uuid4())
    request_id = uuid.uuid4()

    sent: list[tuple[str, str]] = []

    class FakeNet:
        def __init__(self):
            self.handlers = {}

        def register_handler(self, packet_id, h):
            self.handlers[packet_id] = h

    class FakePlugin:
        server = None

    class FakePlayer:
        def __init__(self, name):
            self.name = name

        def send_message(self, msg):
            sent.append((self.name, msg))

    handler = ChatHandler.__new__(ChatHandler)
    handler._plugin = FakePlugin()
    handler._network_client = FakeNet()
    handler._config_manager = None
    handler._logger = __import__("logging").getLogger("test")
    handler._player_channels = {}
    handler._chat_enabled = {}
    handler._player_locales = {player_uuid: "zh_CN"}
    handler._known_channels = []
    handler._pending_actions = {str(request_id): player_uuid}
    from novachat_endstone.i18n import I18n

    handler._i18n = I18n()

    monkeypatch.setattr(
        handler, "_find_player_by_uuid", lambda u: FakePlayer("p")
    )

    response = AdminActionResponsePacket(
        action=AdminAction.STATUS,
        success=True,
        error_code="",
        message="Announcement sent",
    )
    # Frame-level request_id is stamped onto the packet by _read_packet.
    response.request_id = request_id
    handler._handle_admin_action_response(response)

    # Pending entry consumed.
    assert str(request_id) not in handler._pending_actions
    # Player received the backend message.
    assert sent and "Announcement sent" in sent[0][1]


def test_admin_action_response_nc403_surfaces_super_admin_guidance(monkeypatch):
    """On STATUS + NC-403, the generic NC-403 text must be replaced with the
    super-admin guidance (mirrors bukkit isSuperAdminRequired)."""
    from novachat_endstone.chat.handler import ChatHandler

    player_uuid = str(uuid.uuid4())
    request_id = uuid.uuid4()
    sent: list[str] = []

    class FakeNet:
        def __init__(self):
            self.handlers = {}

        def register_handler(self, packet_id, h):
            self.handlers[packet_id] = h

    class FakePlugin:
        server = None

    class FakePlayer:
        name = "p"

        def send_message(self, msg):
            sent.append(msg)

    handler = ChatHandler.__new__(ChatHandler)
    handler._plugin = FakePlugin()
    handler._network_client = FakeNet()
    handler._config_manager = None
    handler._logger = __import__("logging").getLogger("test")
    handler._player_channels = {}
    handler._chat_enabled = {}
    handler._player_locales = {player_uuid: "zh_CN"}
    handler._known_channels = []
    handler._pending_actions = {str(request_id): player_uuid}
    from novachat_endstone.i18n import I18n

    handler._i18n = I18n()
    monkeypatch.setattr(
        handler, "_find_player_by_uuid", lambda u: FakePlayer()
    )

    response = AdminActionResponsePacket(
        action=AdminAction.STATUS,
        success=False,
        error_code="NC-403",
        message="Super admin authentication required for status",
    )
    response.request_id = request_id
    handler._handle_admin_action_response(response)

    assert sent, "player must receive a message"
    combined = " ".join(sent)
    # zh_CN guidance text.
    assert "超级管理员会话" in combined
    assert "/nc auth" in combined


def test_admin_action_response_unknown_request_id_is_dropped(monkeypatch):
    """A response with no matching pending request_id is logged and dropped,
    never raising."""
    from novachat_endstone.chat.handler import ChatHandler

    sent: list[str] = []

    class FakeNet:
        def __init__(self):
            self.handlers = {}

        def register_handler(self, packet_id, h):
            self.handlers[packet_id] = h

    class FakePlugin:
        server = None

    handler = ChatHandler.__new__(ChatHandler)
    handler._plugin = FakePlugin()
    handler._network_client = FakeNet()
    handler._config_manager = None
    handler._logger = __import__("logging").getLogger("test")
    handler._player_channels = {}
    handler._chat_enabled = {}
    handler._player_locales = {}
    handler._known_channels = []
    handler._pending_actions = {}
    from novachat_endstone.i18n import I18n

    handler._i18n = I18n()
    monkeypatch.setattr(
        handler,
        "_find_player_by_uuid",
        lambda u: pytest.fail("should not look up a player for an unknown request"),
    )

    response = AdminActionResponsePacket(
        action=AdminAction.STATUS,
        success=True,
        error_code="",
        message="orphan",
    )
    response.request_id = uuid.uuid4()
    # Must not raise.
    handler._handle_admin_action_response(response)
    assert not sent


# ---------------------------------------------------------------------------
# /nc auth packet shape.
# ---------------------------------------------------------------------------

def test_auth_command_builds_auth_packet_with_sha256_hex():
    """The /nc auth command must send an AdminAction AUTH packet carrying a
    SHA-256 lowercase-hex password hash + playerName extra, mirroring bukkit
    AuthCommand.hashPassword."""
    from novachat_endstone.command.commands import NovaChatCommand

    player = uuid.uuid4()
    password = "hunter2"
    expected_hash = hashlib.sha256(password.encode("utf-8")).hexdigest()

    captured: dict = {}

    class FakePlugin:
        class _NC:
            is_connected = True
            loop = None

            async def send_packet(self, packet):
                captured["action"] = packet.action
                captured["player_id"] = packet.player_id
                captured["password_hash"] = packet.password_hash
                captured["target"] = packet.target
                captured["extra"] = dict(packet.extra)
                # send_packet stamps request_id onto the packet.
                packet.request_id = uuid.uuid4()

        network_client = _NC()
        chat_handler = None
        config_manager = None

        @property
        def server(self):
            return None

    class FakeSender:
        unique_id = player
        name = "Steve"

    cmd = NovaChatCommand.__new__(NovaChatCommand)
    cmd._plugin = FakePlugin()
    cmd._logger = __import__("logging").getLogger("test")
    # _cmd_auth dispatches the send onto the network loop; since loop is None
    # it short-circuits with a network-unavailable message. Patch the loop via
    # a real event loop instead so the coroutine actually runs.

    import asyncio

    loop = asyncio.new_event_loop()
    try:
        FakePlugin.network_client.loop = loop

        # Make register_pending_admin_action a no-op (chat_handler is None in
        # the plugin stub, so _send_admin_action guards on it).

        async def _run():
            return cmd.on_command(FakeSender(), None, "nc", ["auth", password])

        ran = loop.run_until_complete(_run())
        # Drain the run_coroutine_threadsafe task.
        loop.run_until_complete(asyncio.sleep(0.05))
    finally:
        FakePlugin.network_client.loop = None
        loop.close()

    assert captured.get("action") == AdminAction.AUTH
    assert captured.get("player_id") == player
    assert captured.get("password_hash") == expected_hash
    assert captured.get("target") == ""
    assert captured.get("extra", {}).get("playerName") == "Steve"


# ---------------------------------------------------------------------------
# i18n keys present in both bundles.
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "key",
    [
        "chat.auth.progress",
        "chat.auth.success",
        "chat.auth.failed",
        "chat.announce.progress",
        "chat.announce.usage",
        "chat.error.super_admin_required",
        "chat.error.super_admin_required_suggestion",
        "chat.command.help.line_auth",
        "chat.command.help.line_announce",
    ],
)
def test_i18n_keys_present_in_both_locales(key):
    from novachat_endstone.i18n import I18n

    i18n = I18n()
    for locale in ("zh_CN", "en_US"):
        bundle = i18n._bundles.get(locale, {})
        assert key in bundle, f"missing i18n key {key} in {locale}"
        # The key must not resolve to itself (would mean no real value).
        assert i18n.get(key, locale) != key
