"""Tests for ConfigSync known-channel extraction (Endstone).

Mirrors the Java ``ConfigSyncChannels.extract`` contract: the known-channel
set is the union of ``global_channels`` keys and the ``channels`` keys of the
``clients[]`` entry whose ``username`` matches this client's configured
backend username. The active per-player channel (``_player_channels``) must
NOT be touched.

These tests drive ``ChatHandler._handle_config_sync`` directly with a
``ConfigSyncPacket`` so no socket is required. The fixture payloads live in
``NovaChat/Bedrock/test-fixtures/`` and are shared with the PMMP and
LeviLamina siblings.
"""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from novachat_endstone.chat.handler import ChatHandler
from novachat_endstone.protocol.packet import ConfigSyncPacket

FIXTURES = Path(__file__).resolve().parents[2] / "test-fixtures"


def _load_fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def _make_handler(username: str = "EndstoneServer") -> tuple[ChatHandler, MagicMock]:
    """Build a ChatHandler with stubbed plugin/config/network clients.

    A real ConfigManager is not required: the handler only reaches into the
    config manager for ``backend_username``, so a MagicMock exposing that
    property is enough and avoids disk/template loading.
    """
    config_manager = MagicMock()
    config_manager.backend_username = username

    plugin = MagicMock()
    network_client = MagicMock()
    # ChatHandler.__init__ calls register_handler for every packet id, then
    # builds an I18n() provider (pure, no deps). The MagicMock absorbs the
    # register_handler calls.
    handler = ChatHandler(
        plugin=plugin,
        network_client=network_client,
        config_manager=config_manager,
    )
    return handler, config_manager


def _sync_packet(config_json: str) -> ConfigSyncPacket:
    return ConfigSyncPacket(config_json=config_json, timestamp=0)


class TestConfigSyncChannels:
    def test_unions_globals_and_matching_client_channels(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        payload = _load_fixture("config-sync-payload.json")

        handler._handle_config_sync(_sync_packet(payload))

        # globals: global, staff ; client EndstoneServer: local, trade
        assert handler.get_known_channels() == [
            "global",
            "local",
            "staff",
            "trade",
        ]

    def test_other_clients_channels_are_excluded(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        payload = _load_fixture("config-sync-payload.json")

        handler._handle_config_sync(_sync_packet(payload))

        known = set(handler.get_known_channels())
        # PMMP_Server's "help" and NukkitServer's "arena-1" must NOT appear.
        assert "help" not in known
        assert "arena-1" not in known

    def test_blank_username_returns_globals_only(self) -> None:
        handler, config_manager = _make_handler(username="")
        # A blank username means the config lookup returned nothing usable;
        # the extractor must fall back to globals only (Java contract).
        config_manager.backend_username = ""
        payload = _load_fixture("config-sync-payload.json")

        handler._handle_config_sync(_sync_packet(payload))

        assert handler.get_known_channels() == ["global", "staff"]

    def test_unknown_username_returns_globals_only(self) -> None:
        handler, config_manager = _make_handler(username="EndstoneServer")
        config_manager.backend_username = "NobodyMatches"
        payload = _load_fixture("config-sync-payload.json")

        handler._handle_config_sync(_sync_packet(payload))

        assert handler.get_known_channels() == ["global", "staff"]

    def test_empty_payload_yields_empty_registry(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        payload = _load_fixture("config-sync-empty.json")

        handler._handle_config_sync(_sync_packet(payload))

        assert handler.get_known_channels() == []

    def test_malformed_json_leaves_existing_registry_intact(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        # Seed a known-good registry first.
        handler._handle_config_sync(_sync_packet(_load_fixture("config-sync-payload.json")))
        before = handler.get_known_channels()
        assert before  # sanity

        malformed = _load_fixture("config-sync-malformed.json")
        handler._handle_config_sync(_sync_packet(malformed))

        # Bad JSON must not clear the registry (audit acceptance line 323).
        assert handler.get_known_channels() == before

    def test_does_not_overwrite_active_player_channel(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        handler._player_channels["player-1"] = "global"

        handler._handle_config_sync(_sync_packet(_load_fixture("config-sync-payload.json")))

        assert handler._player_channels == {"player-1": "global"}

    def test_null_or_missing_global_channels_is_tolerated(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        # No global_channels key; a client entry for this username still wins.
        payload = json.dumps({
            "clients": [
                {"username": "EndstoneServer", "channels": {"local": {}}}
            ]
        })

        handler._handle_config_sync(_sync_packet(payload))

        assert handler.get_known_channels() == ["local"]

    def test_wrong_types_log_warning_and_continue(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        # global_channels as an array (wrong type) + clients as a mapping
        # (wrong type): both must be tolerated; the client entry for the
        # username is absent, so the result is the empty set.
        payload = json.dumps({
            "global_channels": ["not", "a", "mapping"],
            "clients": {"username": "EndstoneServer"}
        })

        handler._handle_config_sync(_sync_packet(payload))

        assert handler.get_known_channels() == []

    def test_top_level_array_is_rejected(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        # Root is an array, not an object.
        handler._handle_config_sync(_sync_packet(json.dumps([1, 2, 3])))

        # A top-level non-object must not raise and must not seed channels.
        assert handler.get_known_channels() == []

    def test_missing_clients_key_is_tolerated(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        payload = json.dumps({"global_channels": {"global": {}}})

        handler._handle_config_sync(_sync_packet(payload))

        assert handler.get_known_channels() == ["global"]

    def test_client_entry_without_username_is_skipped(self) -> None:
        handler, _ = _make_handler(username="EndstoneServer")
        payload = json.dumps({
            "global_channels": {"global": {}},
            "clients": [
                {"channels": {"local": {}}},  # no username field
                {"username": "EndstoneServer", "channels": {"trade": {}}}
            ]
        })

        handler._handle_config_sync(_sync_packet(payload))

        assert handler.get_known_channels() == ["global", "trade"]

    def test_does_not_restore_template_examples(self) -> None:
        """Audit acceptance (line 323): an unknown/empty payload must NOT
        restore template example channels (local/global)."""
        handler, _ = _make_handler(username="EndstoneServer")
        # Empty payload -> empty registry, never the hardcoded local/global.
        handler._handle_config_sync(_sync_packet(_load_fixture("config-sync-empty.json")))

        assert "local" not in handler.get_known_channels()
        assert "global" not in handler.get_known_channels()
