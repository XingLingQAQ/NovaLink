"""I18n message provider for NovaChat-Endstone.

Mirrors the Java client-core message bundles (messages_zh_CN.properties /
messages_en_US.properties). Keys and color codes (&e, §c) stay inside the
values; only natural language swaps between locales.

Usage:
    i18n = I18n()
    i18n.get("chat.join.joined", "zh_CN", channel_id)   # -> "已加入频道 &e{0}"
    i18n.get("chat.command.help.title", "en_US")

Falls back to zh_CN (the hard default) when a key is missing from en_US,
matching the Java Utf8Control fallback chain.
"""

from __future__ import annotations

from typing import Dict

from novachat_endstone.i18n.messages_zh_CN import ZH_CN
from novachat_endstone.i18n.messages_en_US import EN_US


class I18n:
    """Per-locale message lookup with MessageFormat-style {0}/{1} placeholders."""

    DEFAULT_LOCALE = "zh_CN"

    def __init__(self) -> None:
        self._bundles: Dict[str, Dict[str, str]] = {
            "zh_CN": ZH_CN,
            "en_US": EN_US,
        }

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
