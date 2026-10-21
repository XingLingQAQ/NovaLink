"""
Moderation command tests: /nc mute, kick, ban, unban, title.

The Endstone platform had no moderation commands at all; these pin the shapes
copied from the Java reference implementations (bukkit/folia MuteCommand ..
UnbanCommand) and the backend contract they feed:

* ChannelAction MUTE/KICK/BAN/UNBAN with extras operatorId / console /
  operatorName / targetId / targetName / duration, console senders carrying
  the all-zeros sentinel plus ``console=true``,
* ban with an empty channel id meaning network-wide, mute/kick requiring one,
* AdminAction STATUS with ``type`` = TITLE for /nc title,
* local ``novachat.*`` permission rejection,
* argument validation (missing args, unparseable durations, missing channel).

Everything is driven through the real ``NovaChatCommand.on_command`` against
stub senders/plugin — no Endstone runtime is involved.
"""

from __future__ import annotations

import asyncio
import logging
import uuid

import pytest

from novachat_endstone.command.commands import (
    CONSOLE_SENTINEL_UUID,
    NovaChatCommand,
    is_duration,
    parse_duration,
)
from novachat_endstone.protocol.packet import (
    AdminAction,
    ChannelAction,
    ChannelActionPacket,
)

SENTINEL = uuid.UUID(CONSOLE_SENTINEL_UUID)


# ---------------------------------------------------------------------------
# Stubs
# ---------------------------------------------------------------------------

class RecordingNetwork:
    """Captures every packet handed to send_packet and stamps request_id."""

    is_connected = True
    loop = None

    def __init__(self):
        self.sent: list = []

    async def send_packet(self, packet):
        self.sent.append(packet)
        packet.request_id = uuid.uuid4()


class FakeSender:
    """Stands in for an Endstone Player or ConsoleCommandSender."""

    def __init__(self, name="Steve", player_uuid=None, permissions=None):
        if player_uuid is not None:
            self.unique_id = player_uuid
        self.name = name
        self.messages: list = []
        self._permissions = permissions

    def send_message(self, message):
        self.messages.append(message)

    def has_permission(self, permission):
        if self._permissions is None:
            return True
        return permission in self._permissions


class FakeServer:
    def __init__(self, players=()):
        self.online_players = list(players)

    def get_player(self, name):
        for player in self.online_players:
            if str(player.name).lower() == str(name).lower():
                return player
        return None


class FakeChatHandler:
    def __init__(self, channel="global"):
        self._channel = channel
        self.pending: list = []

    def get_player_channel(self, player_uuid):
        return self._channel

    def get_known_channels(self):
        return ["global", "local"]

    def register_pending_channel_action(self, request_id, player_uuid):
        self.pending.append((request_id, player_uuid))

    def register_pending_admin_action(self, request_id, player_uuid):
        self.pending.append((request_id, player_uuid))


class FakePlugin:
    def __init__(self, players=(), channel="global"):
        self.network_client = RecordingNetwork()
        self.chat_handler = FakeChatHandler(channel)
        self.config_manager = None
        self.server = FakeServer(players)


class TargetPlayer:
    def __init__(self, name, player_uuid):
        self.name = name
        self.unique_id = player_uuid


def build(players=(), channel="global"):
    """Build a command handler whose sends run on a real loop."""
    plugin = FakePlugin(players, channel)
    cmd = NovaChatCommand.__new__(NovaChatCommand)
    cmd._plugin = plugin
    cmd._logger = logging.getLogger("test.moderation")
    return cmd, plugin


def run_command(cmd, sender, args):
    """Execute a subcommand with a loop installed so sends actually run.

    ``_send_channel_action`` schedules onto ``network_client.loop`` via
    run_coroutine_threadsafe; a closed loop can never run that. We install a
    fresh loop, run the (synchronous) command, drain the scheduled coroutine,
    then restore the previous value.
    """
    loop = asyncio.new_event_loop()
    network = cmd._plugin.network_client
    previous = network.loop
    try:
        network.loop = loop
        result = cmd.on_command(sender, None, "nc", args)
        loop.run_until_complete(asyncio.sleep(0.02))
        return result
    finally:
        network.loop = previous
        loop.close()


# ---------------------------------------------------------------------------
# Duration parsing (port of AbstractSubCommand.parseDuration).
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "text,seconds",
    [
        ("30s", 30),
        ("10m", 600),
        ("1h", 3600),
        ("1d", 86400),
        ("45", 45),
        ("90S", 90),
        ("2H", 7200),
        # Negative values parse arithmetically; callers reject them via > 0.
        ("-5m", -300),
    ],
)
def test_parse_duration(text, seconds):
    assert parse_duration(text) == seconds


@pytest.mark.parametrize("text", ["", "abc", "10x", "10w", "1.5h", "h", "0"])
def test_parse_duration_rejects_garbage(text):
    # "0" is -1 in Java too: the numeric part is the empty substring.
    assert parse_duration(text) == -1


@pytest.mark.parametrize(
    "value,expected",
    [
        ("30m", True),
        ("1h", True),
        ("1d", True),
        ("1w", True),
        ("3600", True),
        # Bare "0" matches Java's \d+[smhdw]? and is then rejected by the
        # > 0 duration gate rather than being read as a channel id.
        ("0", True),
        ("global", False),
        ("pvp-eu-1", False),
        # Negative numbers do not match, so they land in the channel slot —
        # verbatim Java behaviour, verified against the real regex.
        ("-5m", False),
        ("", False),
    ],
)
def test_is_duration(value, expected):
    assert is_duration(value) is expected


# ---------------------------------------------------------------------------
# MUTE
# ---------------------------------------------------------------------------

def test_mute_player_sender_packet_shape():
    target_id = uuid.uuid4()
    operator_id = uuid.uuid4()
    target = TargetPlayer("Bob", target_id)
    cmd, plugin = build(players=[target])
    sender = FakeSender("Alice", player_uuid=operator_id)

    run_command(cmd, sender, ["mute", "Bob", "10m"])

    assert len(plugin.network_client.sent) == 1
    packet = plugin.network_client.sent[0]
    assert isinstance(packet, ChannelActionPacket)
    assert packet.action == ChannelAction.MUTE
    # Channel inferred from the sender's active channel.
    assert packet.channel_id == "global"
    assert packet.password == ""
    assert packet.extra == {
        "operatorId": str(operator_id),
        "operatorName": "Alice",
        "targetId": str(target_id),
        "targetName": "Bob",
        # Seconds, not minutes — the backend's parseDurationMs treats the
        # "duration" extra as seconds.
        "duration": "600",
    }
    assert "console" not in packet.extra
    # Progress line shown; success is never claimed before the backend replies.
    assert any("Bob" in m for m in sender.messages)


def test_mute_console_sender_uses_sentinel_and_console_flag():
    target = TargetPlayer("Bob", uuid.uuid4())
    cmd, plugin = build(players=[target])
    sender = FakeSender("Console")  # no unique_id -> not a player

    run_command(cmd, sender, ["mute", "Bob", "1h", "pvp"])

    packet = plugin.network_client.sent[0]
    assert packet.action == ChannelAction.MUTE
    assert packet.channel_id == "pvp"
    assert packet.extra["operatorId"] == CONSOLE_SENTINEL_UUID
    assert packet.extra["console"] == "true"
    assert packet.extra["operatorName"] == "Console"
    assert packet.extra["duration"] == "3600"


def test_mute_console_without_channel_is_rejected():
    cmd, plugin = build()
    sender = FakeSender("Console")

    run_command(cmd, sender, ["mute", "Bob", "10m"])

    assert plugin.network_client.sent == []
    assert any("频道" in m or "channel" in m.lower() for m in sender.messages)


def test_mute_offline_target_still_sent_by_name():
    """An offline target must not abort: the backend resolves names globally."""
    cmd, plugin = build()  # nobody online
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["mute", "Ghost", "10m", "global"])

    packet = plugin.network_client.sent[0]
    assert "targetId" not in packet.extra
    assert packet.extra["targetName"] == "Ghost"


@pytest.mark.parametrize(
    "args",
    [
        ["mute"],
        ["mute", "Bob"],
        ["mute", "Bob", "nonsense"],
        ["mute", "Bob", "0"],
    ],
)
def test_mute_argument_validation_blocks_send(args):
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, args)

    assert plugin.network_client.sent == []
    assert sender.messages, "the operator must be told what went wrong"


def test_mute_permission_denied():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4(),
                        permissions={"novachat.use"})

    run_command(cmd, sender, ["mute", "Bob", "10m"])

    assert plugin.network_client.sent == []
    assert any("权限" in m or "permission" in m.lower() for m in sender.messages)


def test_mute_not_connected_blocks_send():
    cmd, plugin = build()
    plugin.network_client.is_connected = False
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["mute", "Bob", "10m"])

    assert plugin.network_client.sent == []


# ---------------------------------------------------------------------------
# KICK
# ---------------------------------------------------------------------------

def test_kick_player_sender_packet_shape():
    operator_id = uuid.uuid4()
    target_id = uuid.uuid4()
    cmd, plugin = build(players=[TargetPlayer("Bob", target_id)])
    sender = FakeSender("Alice", player_uuid=operator_id)

    run_command(cmd, sender, ["kick", "Bob"])

    packet = plugin.network_client.sent[0]
    assert packet.action == ChannelAction.KICK
    assert packet.channel_id == "global"
    assert packet.extra == {
        "operatorId": str(operator_id),
        "operatorName": "Alice",
        "targetId": str(target_id),
        "targetName": "Bob",
    }


def test_kick_uses_explicit_channel_over_active_channel():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["kick", "Bob", "pvp"])

    assert plugin.network_client.sent[0].channel_id == "pvp"


def test_kick_console_sender_sentinel():
    cmd, plugin = build()
    sender = FakeSender("Console")

    run_command(cmd, sender, ["kick", "Bob", "global"])

    extra = plugin.network_client.sent[0].extra
    assert extra["operatorId"] == CONSOLE_SENTINEL_UUID
    assert extra["console"] == "true"


def test_kick_console_without_channel_is_rejected():
    cmd, plugin = build()
    sender = FakeSender("Console")

    run_command(cmd, sender, ["kick", "Bob"])

    assert plugin.network_client.sent == []


def test_kick_argument_validation():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["kick"])

    assert plugin.network_client.sent == []
    assert sender.messages


def test_kick_permission_denied():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4(),
                        permissions={"novachat.use"})

    run_command(cmd, sender, ["kick", "Bob", "global"])

    assert plugin.network_client.sent == []


# ---------------------------------------------------------------------------
# BAN
# ---------------------------------------------------------------------------

def test_ban_without_channel_is_network_wide():
    cmd, plugin = build(channel="local")
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["ban", "Bob"])

    packet = plugin.network_client.sent[0]
    assert packet.action == ChannelAction.BAN
    # Deliberately NOT the sender's active channel: an omitted channel is a
    # global ban, never a silently narrowed one.
    assert packet.channel_id == ""
    assert "duration" not in packet.extra
    assert packet.extra["targetName"] == "Bob"


def test_ban_duration_then_channel():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["ban", "Bob", "1h", "pvp"])

    packet = plugin.network_client.sent[0]
    assert packet.channel_id == "pvp"
    assert packet.extra["duration"] == "3600"


def test_ban_channel_then_duration():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["ban", "Bob", "pvp", "1d"])

    packet = plugin.network_client.sent[0]
    assert packet.channel_id == "pvp"
    assert packet.extra["duration"] == "86400"


def test_ban_console_sender_sentinel():
    cmd, plugin = build()
    sender = FakeSender("Console")

    run_command(cmd, sender, ["ban", "Bob", "30m"])

    extra = plugin.network_client.sent[0].extra
    assert extra["operatorId"] == CONSOLE_SENTINEL_UUID
    assert extra["console"] == "true"
    assert extra["duration"] == "1800"


def test_ban_invalid_duration_blocks_send():
    """A "w"-suffixed value is routed to the duration slot (is_duration) but
    parse_duration rejects it, matching Java."""
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["ban", "Bob", "1w"])

    assert plugin.network_client.sent == []
    assert sender.messages


def test_ban_non_duration_second_arg_is_treated_as_channel():
    """Java isDuration("10x") is false, so it becomes the channel id."""
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["ban", "Bob", "10x"])

    packet = plugin.network_client.sent[0]
    assert packet.channel_id == "10x"
    assert "duration" not in packet.extra


def test_ban_argument_validation():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["ban"])

    assert plugin.network_client.sent == []


def test_ban_permission_denied():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4(),
                        permissions={"novachat.mute", "novachat.kick"})

    run_command(cmd, sender, ["ban", "Bob"])

    assert plugin.network_client.sent == []


# ---------------------------------------------------------------------------
# UNBAN
# ---------------------------------------------------------------------------

def test_unban_accepts_uuid_for_offline_target():
    target_id = uuid.uuid4()
    cmd, plugin = build()  # target offline
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["unban", str(target_id)])

    packet = plugin.network_client.sent[0]
    assert packet.action == ChannelAction.UNBAN
    assert packet.channel_id == ""
    assert packet.extra["targetId"] == str(target_id)
    assert packet.extra["targetName"] == str(target_id)


def test_unban_offline_name_warns_but_still_sends():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["unban", "Ghost"])

    packet = plugin.network_client.sent[0]
    assert "targetId" not in packet.extra
    assert packet.extra["targetName"] == "Ghost"
    # The offline hint precedes the progress line.
    assert len(sender.messages) >= 2


def test_unban_scoped_to_channel():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["unban", "Bob", "pvp"])

    assert plugin.network_client.sent[0].channel_id == "pvp"


def test_unban_console_sender_sentinel():
    cmd, plugin = build()
    sender = FakeSender("Console")

    run_command(cmd, sender, ["unban", str(uuid.uuid4())])

    extra = plugin.network_client.sent[0].extra
    assert extra["operatorId"] == CONSOLE_SENTINEL_UUID
    assert extra["console"] == "true"


def test_unban_shares_the_ban_permission():
    """Java UnbanCommand.getPermission() is novachat.ban, not a new node."""
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4(),
                        permissions={"novachat.ban"})

    run_command(cmd, sender, ["unban", str(uuid.uuid4())])

    assert len(plugin.network_client.sent) == 1


def test_unban_permission_denied():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4(),
                        permissions={"novachat.mute"})

    run_command(cmd, sender, ["unban", str(uuid.uuid4())])

    assert plugin.network_client.sent == []


def test_unban_argument_validation():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["unban"])

    assert plugin.network_client.sent == []


# ---------------------------------------------------------------------------
# TITLE (AdminAction STATUS + type)
# ---------------------------------------------------------------------------

def test_title_player_sender_packet_shape():
    operator_id = uuid.uuid4()
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=operator_id)

    run_command(cmd, sender, ["title", "global", "Hello", "there", "world"])

    packet = plugin.network_client.sent[0]
    assert packet.action == AdminAction.STATUS
    assert packet.player_id == operator_id
    assert packet.password_hash == ""
    assert packet.target == "global"
    assert packet.extra == {
        "type": "TITLE",
        "operatorName": "Alice",
        "title": "Hello",
        "subtitle": "there world",
    }


def test_title_console_sender_sentinel():
    cmd, plugin = build()
    sender = FakeSender("Console")

    run_command(cmd, sender, ["title", "global", "Hello"])

    packet = plugin.network_client.sent[0]
    assert packet.player_id == SENTINEL
    assert packet.extra["console"] == "true"
    assert packet.extra["subtitle"] == ""


def test_title_argument_validation():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["title", "global"])

    assert plugin.network_client.sent == []
    assert sender.messages


def test_title_permission_denied():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4(),
                        permissions={"novachat.announce"})

    run_command(cmd, sender, ["title", "global", "Hi"])

    assert plugin.network_client.sent == []


# ---------------------------------------------------------------------------
# Cross-cutting: correlation, registration, i18n, help, tab completion.
# ---------------------------------------------------------------------------

def test_moderation_sends_register_for_response_routing():
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["kick", "Bob", "global"])

    pending = plugin.chat_handler.pending
    assert len(pending) == 1
    request_id, player_uuid = pending[0]
    assert request_id == str(plugin.network_client.sent[0].request_id)
    assert player_uuid == str(sender.unique_id)


def test_console_sends_register_against_the_sentinel():
    cmd, plugin = build()
    sender = FakeSender("Console")

    run_command(cmd, sender, ["kick", "Bob", "global"])

    assert plugin.chat_handler.pending[0][1] == CONSOLE_SENTINEL_UUID


def test_console_outcome_is_logged_not_dropped(caplog):
    """Console has no player to receive the response; the handler logs it."""
    from novachat_endstone.chat.handler import ChatHandler

    handler = ChatHandler.__new__(ChatHandler)
    handler._logger = logging.getLogger("test.console.outcome")

    with caplog.at_level(logging.INFO, logger="test.console.outcome"):
        handler._send_to_player_by_uuid(CONSOLE_SENTINEL_UUID, "Player kicked")

    assert "[NovaChat console] Player kicked" in caplog.text


def test_every_moderation_command_has_a_local_permission_node():
    """Nodes match the Java platforms. ``announce`` is absent on purpose: it
    predates this module and relies solely on the backend super-admin session."""
    assert NovaChatCommand.MODERATION_PERMISSIONS == {
        "mute": "novachat.mute",
        "kick": "novachat.kick",
        "ban": "novachat.ban",
        "unban": "novachat.ban",
        "title": "novachat.title",
    }


def test_announce_behaviour_is_unchanged():
    """Regression guard: /nc announce keeps its pre-existing behaviour and is
    NOT newly gated on a local node (its real gate is the backend session)."""
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4(),
                        permissions={"novachat.use"})

    run_command(cmd, sender, ["announce", "global", "hello"])

    assert len(plugin.network_client.sent) == 1, (
        "announce must still send for any sender; the backend enforces auth"
    )


@pytest.mark.parametrize(
    "subcommand",
    ["mute", "kick", "ban", "unban", "title", "announce", "auth", "help"],
)
def test_subcommands_are_dispatchable(subcommand):
    """Every advertised subcommand must reach a handler, never "unknown"."""
    cmd, plugin = build()
    sender = FakeSender("Alice", player_uuid=uuid.uuid4())

    run_command(cmd, sender, [subcommand])

    joined = " ".join(sender.messages)
    assert "未知命令" not in joined and "Unknown command" not in joined


@pytest.mark.parametrize("key", [
    "chat.command.usage.mute",
    "chat.command.usage.kick",
    "chat.command.usage.ban",
    "chat.command.usage.unban",
    "chat.command.usage.title",
    "chat.command.help.line_mute",
    "chat.command.help.line_kick",
    "chat.command.help.line_ban",
    "chat.command.help.line_unban",
    "chat.command.help.line_title",
    "chat.mute.progress",
    "chat.mute.duration_hint",
    "chat.kick.progress",
    "chat.ban.progress",
    "chat.ban.duration_hint",
    "chat.ban.invalid_duration",
    "chat.unban.progress",
    "chat.unban.offline_hint",
    "chat.title.progress",
    "chat.title.color_hint",
])
def test_i18n_keys_present_in_both_locales(key):
    from novachat_endstone.i18n import I18n

    i18n = I18n()
    for locale in ("zh_CN", "en_US"):
        bundle = i18n._bundles.get(locale, {})
        assert key in bundle, f"missing i18n key {key} in {locale}"
        assert i18n.get(key, locale) != key


def test_help_lists_moderation_only_with_permission():
    cmd, _ = build()
    privileged = FakeSender("Admin", player_uuid=uuid.uuid4())
    plain = FakeSender("Guest", player_uuid=uuid.uuid4(),
                       permissions={"novachat.use"})

    run_command(cmd, privileged, ["help"])
    run_command(cmd, plain, ["help"])

    assert any("mute" in m for m in privileged.messages)
    assert not any("/nc mute" in m for m in plain.messages)


def test_tab_complete_offers_moderation_subcommands():
    cmd, _ = build()
    sender = FakeSender("Admin", player_uuid=uuid.uuid4())

    assert "mute" in cmd.on_tab_complete(sender, None, "nc", ["mu"])
    assert "unban" in cmd.on_tab_complete(sender, None, "nc", ["un"])


def test_tab_complete_offers_channel_for_mute_channel_slot():
    cmd, _ = build()
    sender = FakeSender("Admin", player_uuid=uuid.uuid4())

    completions = cmd.on_tab_complete(sender, None, "nc", ["mute", "Bob", "10m", "gl"])

    assert completions == ["global"]


def test_tab_complete_offers_durations_for_mute():
    cmd, _ = build()
    sender = FakeSender("Admin", player_uuid=uuid.uuid4())

    # args includes the subcommand at index 0, so the duration slot is arg[2].
    assert cmd.on_tab_complete(sender, None, "nc", ["mute", "Bob", "1"]) == ["10m", "1h", "1d"]


def test_tab_complete_ban_offers_both_durations_and_channels():
    cmd, _ = build()
    sender = FakeSender("Admin", player_uuid=uuid.uuid4())

    completions = cmd.on_tab_complete(sender, None, "nc", ["ban", "Bob", "g"])

    assert "global" in completions


def test_tab_complete_offers_player_names_for_target_slot():
    cmd, _ = build(players=[TargetPlayer("Bob", uuid.uuid4())])
    sender = FakeSender("Admin", player_uuid=uuid.uuid4())

    assert cmd.on_tab_complete(sender, None, "nc", ["kick", "Bo"]) == ["Bob"]


# ---------------------------------------------------------------------------
# Operator-facing outcome of the async ChannelActionResponse.
#
# The command prints an optimistic "...ing" line and returns; the per-action
# result arrives later on 0x05 and is correlated by request_id. Before the fix
# every success rendered the same generic "operation succeeded" (and the
# per-action keys did not exist), so the operator could not tell a mute from a
# kick from a ban. These pin the per-action line and the failure line.
# ---------------------------------------------------------------------------

from novachat_endstone.chat.handler import ChatHandler
from novachat_endstone.protocol.packet import ChannelActionResponsePacket


def build_handler(player_uuid=None, locale="zh_CN"):
    """A ChatHandler with just the state the response path touches."""
    handler = ChatHandler.__new__(ChatHandler)
    handler._logger = logging.getLogger("test.moderation.outcome")
    handler._player_channels = {}
    handler._chat_enabled = {}
    handler._player_locales = {player_uuid: locale} if player_uuid else {}
    handler._known_channels = []
    handler._pending_actions = {}
    from novachat_endstone.i18n import I18n

    handler._i18n = I18n()
    return handler


class RecordingPlugin:
    def __init__(self):
        self.server = FakeServer()


def make_response(action, success, error_code="", request_id=None):
    response = ChannelActionResponsePacket(
        success=success,
        action=action,
        channel_id="global",
        error_code=error_code,
        message="",
        extra={},
    )
    # Frame-level request_id is stamped onto the packet by _read_packet.
    response.request_id = request_id or uuid.uuid4()
    return response


def deliver(handler, player_uuid, response, sent):
    """Route a response through the real handler, capturing delivery."""
    handler._plugin = RecordingPlugin()
    handler._pending_actions[str(response.request_id)] = player_uuid
    handler._send_to_player_by_uuid = lambda uuid_str, msg: sent.append(msg)
    handler._handle_channel_action_response(response)


@pytest.mark.parametrize(
    "action,key",
    [
        (ChannelAction.KICK, "chat.action.kick_processed"),
        (ChannelAction.MUTE, "chat.action.mute_processed"),
        (ChannelAction.UNMUTE, "chat.action.unmute_processed"),
        # BAN / UNBAN have no key of their own; Java parity uses the generic ack.
        (ChannelAction.BAN, "chat.action.success"),
        (ChannelAction.UNBAN, "chat.action.success"),
    ],
)
def test_moderation_success_reaches_the_operator(action, key):
    """A successful moderation response produces a per-action line for the
    operator who issued it (not just the target-side notice)."""
    player_uuid = str(uuid.uuid4())
    handler = build_handler(player_uuid)
    sent: list = []

    deliver(handler, player_uuid, make_response(action, True), sent)

    assert sent, f"operator must be told the {action} succeeded"
    expected = handler._i18n.get(key, "zh_CN")
    assert expected in sent[0]
    # Not the raw key: the key must resolve in the shipped bundle.
    assert key not in sent[0]


def test_moderation_success_is_distinct_per_action():
    """The mute and kick confirmations differ, so the operator can tell which
    action the backend accepted."""
    kick_sent: list = []
    mute_sent: list = []
    for action, sink in ((ChannelAction.KICK, kick_sent), (ChannelAction.MUTE, mute_sent)):
        player_uuid = str(uuid.uuid4())
        handler = build_handler(player_uuid)
        deliver(handler, player_uuid, make_response(action, True), sink)

    assert kick_sent[0] != mute_sent[0]


def test_moderation_failure_reaches_the_operator():
    """A rejected moderation action reports the coded error, in the operator's
    locale, instead of leaving them with only the progress line."""
    player_uuid = str(uuid.uuid4())
    handler = build_handler(player_uuid)
    sent: list = []

    deliver(handler, player_uuid, make_response(ChannelAction.BAN, False, "NC-403"), sent)

    assert sent, "operator must be told the ban was rejected"
    assert "NC-403" not in sent[0], "the code must be translated, not echoed raw"
    assert handler._i18n.get("error.NC-403.message", "zh_CN") in sent[0]


def test_moderation_failure_uses_the_operator_locale():
    player_uuid = str(uuid.uuid4())
    handler = build_handler(player_uuid, locale="en_US")
    sent: list = []

    deliver(handler, player_uuid, make_response(ChannelAction.MUTE, False, "NC-403"), sent)

    assert handler._i18n.get("error.NC-403.message", "en_US") in sent[0]


def test_unmatched_response_is_dropped():
    """A response with no pending request_id (backend push, or a duplicate) has
    no operator to report to and must not raise or guess a recipient."""
    player_uuid = str(uuid.uuid4())
    handler = build_handler(player_uuid)
    sent: list = []

    handler._plugin = RecordingPlugin()
    handler._send_to_player_by_uuid = lambda uuid_str, msg: sent.append(msg)
    # Note: no pending entry registered for this request id.
    handler._handle_channel_action_response(make_response(ChannelAction.KICK, True))

    assert sent == []


def test_progress_line_is_not_the_outcome():
    """The command itself only prints progress; the outcome comes later. Pin
    that a mute command sends exactly the progress line, so the success line
    asserted above can only have come from the response path."""
    from novachat_endstone.i18n import I18n

    cmd, plugin = build()
    sender = FakeSender("Admin", player_uuid=uuid.uuid4())

    run_command(cmd, sender, ["mute", "Bob", "10m", "global"])

    # Exactly one line at send time: the progress line.
    assert len(sender.messages) == 1
    # It is the progress template...
    progress_template = I18n().get("chat.mute.progress", "zh_CN", "Bob", "global", "")
    leading = progress_template.split("&eBob")[0]
    assert leading and sender.messages[0].startswith(leading)
    # ...and not the success confirmation the response path renders.
    assert sender.messages[0] != I18n().get("chat.action.mute_processed", "zh_CN")
