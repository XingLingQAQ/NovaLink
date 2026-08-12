"""I18n message provider for NovaChat-Endstone.

Translations live as external ``lang/<locale>.json`` files next to this
provider (one file per locale, keyed by filename stem). At construction the
provider scans the ``lang/`` directory and loads every ``*.json`` file it
finds, so adding a new language is just dropping a new ``lang/<locale>.json``
into the directory — no code change required.

Keys and color codes (&e, §c) stay inside the values; only natural language
swaps between locales. Keys mirror the Java client-core message bundles
(messages_zh_CN.properties / messages_en_US.properties) for cross-platform
parity.

Usage:
    i18n = I18n()
    i18n.get("chat.join.joined", "zh_CN", channel_id)   # -> "已加入频道 &e{0}"
    i18n.get("chat.command.help.title", "en_US")

Falls back to zh_CN (the hard default) when a key is missing from the
requested locale, matching the Java Utf8Control fallback chain.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Dict

# Directory holding lang/<locale>.json files (sibling of this module).
_LANG_DIR = Path(__file__).resolve().parent / "lang"


class I18n:
    """Per-locale message lookup with MessageFormat-style {0}/{1} placeholders."""

    DEFAULT_LOCALE = "zh_CN"

    def __init__(self) -> None:
        self._bundles: Dict[str, Dict[str, str]] = {}
        self._load_lang_dir()

    def _load_lang_dir(self) -> None:
        """Scan the lang/ directory and load every <locale>.json file.

        The filename stem (e.g. ``zh_CN`` for ``zh_CN.json``) becomes the
        locale key. Files that fail to parse are skipped silently so a single
        malformed file never breaks the whole provider — zh_CN remains as the
        hard default as long as its file loads.
        """
        if not _LANG_DIR.is_dir():
            return
        for path in sorted(_LANG_DIR.glob("*.json")):
            locale = path.stem
            try:
                with path.open("r", encoding="utf-8") as fh:
                    data = json.load(fh)
            except (OSError, ValueError):
                continue
            if not isinstance(data, dict):
                continue
            bundle: Dict[str, str] = {}
            for key, value in data.items():
                if isinstance(key, str) and isinstance(value, str):
                    bundle[key] = value
            if bundle:
                self._bundles[locale] = bundle

    def get(self, key: str, locale: str, *args) -> str:
        """
        Look up a localized message and substitute {0}, {1}, ... placeholders.

        Falls back to zh_CN when the key is absent from the requested locale.
        """
        bundle = self._bundles.get(locale) or self._bundles.get(self.DEFAULT_LOCALE) or {}
        template = bundle.get(key)
        if template is None:
            # Final fallback to the default locale, then to the key itself.
            template = self._bundles.get(self.DEFAULT_LOCALE, {}).get(key, key)
        return self._format(template, args)

    def error_message(self, error_code: str, locale: str) -> str:
        """
        Build a human-readable error message from an NC-* error code.

        Combines error.<code>.message + error.<code>.suggestion, matching the
        Java ErrorMessageFormatter behaviour.
        """
        message = self.get(f"error.{error_code}.message", locale, error_code)
        suggestion = self.get(f"error.{error_code}.suggestion", locale)
        prefix = self.get("error.suggestion_prefix", locale)
        return f"§c{message} §7{prefix} {suggestion}"

    @staticmethod
    def _format(template: str, args) -> str:
        """Substitute {0}, {1}, ... positional placeholders."""
        result = template
        for i, value in enumerate(args):
            result = result.replace("{" + str(i) + "}", str(value))
        return result
