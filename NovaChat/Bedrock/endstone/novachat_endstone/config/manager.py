"""Template-backed configuration loading for NovaChat Endstone."""

from __future__ import annotations

import copy
import logging
import os
import shutil
import tempfile
from importlib.resources import files
from io import StringIO
from pathlib import Path
from typing import TYPE_CHECKING, Any, Optional

from ruamel.yaml import YAML
from ruamel.yaml.comments import CommentedMap

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


class ConfigManager:
    """Loads config.yml and upgrades it from the packaged template."""

    _DYNAMIC_MAPPINGS = {
        "chat.channel-prefixes",
        "format.channels",
        "world-routing.mappings",
    }

    def __init__(self, plugin: "NovaChatPlugin"):
        self._plugin = plugin
        self._logger = logging.getLogger("NovaChat.Config")
        self._config: CommentedMap = CommentedMap()
        self._config_path = Path(plugin.data_folder) / "config.yml"

    def load(self) -> bool:
        """Install, upgrade, and load the config without losing the last good state."""
        previous = self._config
        try:
            template_text = self._read_template()
            template = self._parse(template_text, "bundled configuration template")

            if not self._config_path.exists():
                self._validate(template)
                self._write_text_atomically(template_text)
                candidate = template
                self._logger.info("Created config.yml from the bundled template")
            else:
                original = self._config_path.read_text(encoding="utf-8")
                candidate = self._parse(original, "existing config.yml")
                changed = self._merge_missing(candidate, template)
                self._validate(candidate)
                if changed:
                    rendered = self._dump(candidate)
                    self._parse(rendered, "generated config.yml")
                    backup = self._config_path.with_name(self._config_path.name + ".bak")
                    shutil.copy2(self._config_path, backup)
                    self._write_text_atomically(rendered)
                    self._logger.info(
                        "Added new configuration entries from the bundled template; backup: %s",
                        backup,
                    )

            self._config = candidate
            self._logger.info("Configuration loaded")
            return True
        except Exception as exc:
            self._config = previous
            self._logger.error("Failed to install, upgrade, or load config.yml: %s", exc)
            return False

    @staticmethod
    def _yaml() -> YAML:
        parser = YAML(typ="rt")
        parser.preserve_quotes = True
        parser.width = 4096
        parser.indent(mapping=2, sequence=4, offset=2)
        return parser

    @staticmethod
    def _read_template() -> str:
        return files("novachat_endstone.config").joinpath("default_config.yml").read_text(
            encoding="utf-8"
        )

    @classmethod
    def _parse(cls, content: str, source: str) -> CommentedMap:
        loaded = cls._yaml().load(content)
        if not isinstance(loaded, CommentedMap):
            raise ValueError(f"{source} root must be a YAML mapping")
        return loaded

    @classmethod
    def _dump(cls, config: CommentedMap) -> str:
        output = StringIO()
        cls._yaml().dump(config, output)
        return output.getvalue()

    def _merge_missing(
        self, target: CommentedMap, template: CommentedMap, parent_path: str = ""
    ) -> bool:
        changed = False
        for key, template_value in template.items():
            path = f"{parent_path}.{key}" if parent_path else str(key)
            if key not in target:
                target[key] = copy.deepcopy(template_value)
                if key in template.ca.items:
                    target.ca.items[key] = copy.deepcopy(template.ca.items[key])
                changed = True
                continue

            current = target[key]
            if isinstance(template_value, CommentedMap):
                if isinstance(current, CommentedMap) and path not in self._DYNAMIC_MAPPINGS:
                    changed |= self._merge_missing(current, template_value, path)
        return changed

    def _write_text_atomically(self, content: str) -> None:
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temp_name = tempfile.mkstemp(
            prefix=f".{self._config_path.name}.",
            suffix=".tmp",
            dir=self._config_path.parent,
            text=True,
        )
        temp_path = Path(temp_name)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp_path, self._config_path)
        finally:
            temp_path.unlink(missing_ok=True)

    @classmethod
    def _validate(cls, config: CommentedMap) -> None:
        backend = cls._require_mapping(config, "backend")
        cls._require_non_blank_string(backend, "host", "backend.host")
        port = cls._require_int(backend, "port", "backend.port")
        if not 1 <= port <= 65535:
            raise ValueError("backend.port must be between 1 and 65535")
        cls._require_non_blank_string(backend, "username", "backend.username")
        cls._require_string(backend, "password", "backend.password")
        reconnect_delay = cls._require_int(
            backend, "reconnect-delay", "backend.reconnect-delay"
        )
        if reconnect_delay <= 0:
            raise ValueError("backend.reconnect-delay must be greater than 0")

        # AUTH-002 TLS: transport encryption for the backend connection.
        # Default enable=false keeps the plaintext path (zero regression). When
        # enabled, the backend certificate is ALWAYS verified — there is no
        # option to turn verification off. The optional client_cert_path /
        # client_key_path are for mutual-TLS (the backend does not require mTLS
        # by default). A non-blank client_cert_path MUST be paired with a
        # non-blank client_key_path.
        tls = cls._require_mapping(backend, "tls")
        tls_enable = cls._require_bool(tls, "enable", "backend.tls.enable")
        ca_cert_path = cls._require_string(
            tls, "ca_cert_path", "backend.tls.ca_cert_path"
        )
        client_cert_path = cls._require_string(
            tls, "client_cert_path", "backend.tls.client_cert_path"
        )
        client_key_path = cls._require_string(
            tls, "client_key_path", "backend.tls.client_key_path"
        )
        if bool(client_cert_path.strip()) != bool(client_key_path.strip()):
            raise ValueError(
                "backend.tls.client_cert_path and backend.tls.client_key_path "
                "must both be set or both be empty"
            )

        chat = cls._require_mapping(config, "chat")
        cls._require_bool(chat, "replace_vanilla", "chat.replace_vanilla")
        cls._require_non_blank_string(chat, "default_channel", "chat.default_channel")

        format_config = cls._require_mapping(config, "format")
        cls._require_string(format_config, "default", "format.default")
        cls._require_string_mapping(format_config, "channels", "format.channels")

        world_routing = cls._require_mapping(config, "world-routing")
        cls._require_bool(world_routing, "enabled", "world-routing.enabled")
        cls._require_string_mapping(
            world_routing, "mappings", "world-routing.mappings"
        )

        cls._require_non_blank_string(config, "server-version", "server-version")
        cls._require_bool(config, "debug", "debug")

    @staticmethod
    def _require_mapping(parent: CommentedMap, key: str) -> CommentedMap:
        value = parent.get(key)
        if not isinstance(value, CommentedMap):
            raise ValueError(f"{key} must be a mapping")
        return value

    @staticmethod
    def _require_string(parent: CommentedMap, key: str, path: str) -> str:
        value = parent.get(key)
        if not isinstance(value, str):
            raise ValueError(f"{path} must be a string")
        return value

    @classmethod
    def _require_non_blank_string(
        cls, parent: CommentedMap, key: str, path: str
    ) -> str:
        value = cls._require_string(parent, key, path)
        if not value.strip():
            raise ValueError(f"{path} must not be blank")
        return value

    @staticmethod
    def _require_int(parent: CommentedMap, key: str, path: str) -> int:
        value = parent.get(key)
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"{path} must be an integer")
        return value

    @staticmethod
    def _require_bool(parent: CommentedMap, key: str, path: str) -> bool:
        value = parent.get(key)
        if not isinstance(value, bool):
            raise ValueError(f"{path} must be a boolean")
        return value

    @classmethod
    def _require_string_mapping(
        cls, parent: CommentedMap, key: str, path: str
    ) -> CommentedMap:
        mapping = cls._require_mapping(parent, key)
        for entry_key, value in mapping.items():
            if not isinstance(entry_key, str) or not isinstance(value, str):
                raise ValueError(f"{path} must contain only string values")
        return mapping

    def _required(self, *path: str) -> Any:
        value: Any = self._config
        for key in path:
            value = value[key]
        return value

    @property
    def backend_host(self) -> str:
        return str(self._required("backend", "host"))

    @property
    def backend_port(self) -> int:
        return int(self._required("backend", "port"))

    @property
    def backend_username(self) -> str:
        return str(self._required("backend", "username"))

    @property
    def backend_password(self) -> str:
        return str(self._required("backend", "password"))

    @property
    def reconnect_delay(self) -> int:
        return int(self._required("backend", "reconnect-delay"))

    # --- AUTH-002 TLS -------------------------------------------------------
    # Transport-layer encryption for the backend connection. `enable` defaults
    # to False (plaintext compatibility); when True, the client verifies the
    # backend certificate against `ca_cert_path` (or the system CA store when
    # blank). The optional mTLS pair is loaded only when both paths are set.
    @property
    def tls_enabled(self) -> bool:
        return bool(self._required("backend", "tls", "enable"))

    @property
    def tls_ca_cert_path(self) -> str:
        return str(self._required("backend", "tls", "ca_cert_path"))

    @property
    def tls_client_cert_path(self) -> str:
        return str(self._required("backend", "tls", "client_cert_path"))

    @property
    def tls_client_key_path(self) -> str:
        return str(self._required("backend", "tls", "client_key_path"))

    @property
    def server_version(self) -> str:
        return str(self._required("server-version"))

    @property
    def replace_vanilla(self) -> bool:
        return bool(self._required("chat", "replace_vanilla"))

    @property
    def default_channel(self) -> str:
        return str(self._required("chat", "default_channel"))

    def get_channel_format(self, channel_id: str) -> str:
        formats = self._required("format")
        channels = formats["channels"]
        return str(channels.get(channel_id, formats["default"]))

    @property
    def world_routing_enabled(self) -> bool:
        return bool(self._required("world-routing", "enabled"))

    def get_world_channel(self, world_name: str) -> Optional[str]:
        value = self._required("world-routing", "mappings").get(world_name)
        return str(value) if value is not None else None

    @property
    def debug(self) -> bool:
        return bool(self._required("debug"))

    @debug.setter
    def debug(self, value: bool) -> None:
        self._config["debug"] = value
