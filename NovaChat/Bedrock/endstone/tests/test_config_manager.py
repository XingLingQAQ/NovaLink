from __future__ import annotations

from importlib.resources import files
from pathlib import Path

from novachat_endstone.config.manager import ConfigManager


class StubPlugin:
    def __init__(self, data_folder: Path):
        self.data_folder = str(data_folder)


def test_creates_config_from_packaged_template(tmp_path: Path) -> None:
    manager = ConfigManager(StubPlugin(tmp_path))

    assert manager.load()

    expected = files("novachat_endstone.config").joinpath("default_config.yml").read_text(
        encoding="utf-8"
    )
    config_path = tmp_path / "config.yml"
    assert config_path.read_text(encoding="utf-8") == expected
    assert manager.backend_username == "EndstoneServer"
    assert not config_path.with_name("config.yml.bak").exists()


def test_upgrades_and_preserves_comments_unknown_fields_and_dynamic_maps(
    tmp_path: Path,
) -> None:
    config_path = tmp_path / "config.yml"
    original = """\
# operator configuration
operator-setting: retained
backend:
  host: endstone.internal # keep host
  port: 19999
format:
  channels:
    custom: custom format
world-routing:
  mappings:
    custom_world: staff
"""
    config_path.write_text(original, encoding="utf-8")
    manager = ConfigManager(StubPlugin(tmp_path))

    assert manager.load()
    upgraded = config_path.read_text(encoding="utf-8")

    assert manager.backend_host == "endstone.internal"
    assert manager.backend_port == 19999
    assert manager.get_channel_format("custom") == "custom format"
    assert manager.get_world_channel("custom_world") == "staff"
    assert "# operator configuration" in upgraded
    assert "# keep host" in upgraded
    assert "operator-setting: retained" in upgraded
    assert "global:" not in upgraded
    assert "overworld:" not in upgraded
    assert config_path.with_name("config.yml.bak").read_text(encoding="utf-8") == original

    assert manager.load()
    assert config_path.read_text(encoding="utf-8") == upgraded


def test_wrong_value_type_is_not_overwritten(tmp_path: Path) -> None:
    config_path = tmp_path / "config.yml"
    original = "backend:\n  port: wrong-type\n"
    config_path.write_text(original, encoding="utf-8")
    manager = ConfigManager(StubPlugin(tmp_path))

    assert not manager.load()
    assert config_path.read_text(encoding="utf-8") == original
    assert not config_path.with_name("config.yml.bak").exists()


def test_malformed_reload_keeps_previous_runtime_config(tmp_path: Path) -> None:
    manager = ConfigManager(StubPlugin(tmp_path))
    assert manager.load()
    original_host = manager.backend_host
    config_path = tmp_path / "config.yml"
    malformed = "backend: [\n"
    config_path.write_text(malformed, encoding="utf-8")

    assert not manager.load()

    assert manager.backend_host == original_host
    assert config_path.read_text(encoding="utf-8") == malformed
    assert not config_path.with_name("config.yml.bak").exists()
