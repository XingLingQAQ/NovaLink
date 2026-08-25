"""VERIFY-007 Endstone — canonical NC error-code localization parity.

Audit VERIFY-007 requires that every canonical NC-* error code has a localized,
non-empty, human-readable message and suggestion in each platform's i18n
bundles. This module is the Endstone (Python) slice: it asserts the Endstone
``lang/en_US.json`` and ``lang/zh_CN.json`` bundles cover the canonical NC code
set and that :class:`I18n.error_message` returns real localized text (not a
fallback to the bare code) for every canonical code in both locales.

The canonical code set is the audit-mandated list (see
``docs/PRODUCTION_READINESS_AND_PRODUCT_PLAN.md`` VERIFY-007):
NC-400/401/403/404/409/410/411/420/429/430/431/432/433/434/435/436/437/438/
439/500/501/502/503/504/510/511.

This is a pure regression test — it adds no production code and changes no
behavior. It fails closed the moment a canonical code is dropped from either
locale bundle or the formatter falls back to the bare code.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from novachat_endstone.i18n import I18n
from novachat_endstone.i18n.provider import _LANG_DIR


# The audit-mandated canonical NC error-code set (VERIFY-007).
CANONICAL_NC_CODES: tuple[str, ...] = (
    "NC-400",
    "NC-401",
    "NC-403",
    "NC-404",
    "NC-409",
    "NC-410",
    "NC-411",
    "NC-420",
    "NC-429",
    "NC-430",
    "NC-431",
    "NC-432",
    "NC-433",
    "NC-434",
    "NC-435",
    "NC-436",
    "NC-437",
    "NC-438",
    "NC-439",
    "NC-500",
    "NC-501",
    "NC-502",
    "NC-503",
    "NC-504",
    "NC-510",
    "NC-511",
)

# Locales the Endstone client ships and must keep in parity.
SUPPORTED_LOCALES: tuple[str, ...] = ("en_US", "zh_CN")


def _load_bundle(locale: str) -> dict[str, str]:
    """Load a lang/<locale>.json bundle straight from disk.

    Reading the raw file (rather than going through I18n) lets the test report
    exactly which keys are missing from which file, independent of the
    provider's fallback chain.
    """
    path = _LANG_DIR / f"{locale}.json"
    assert path.is_file(), f"Endstone i18n bundle missing: {path}"
    data = json.loads(path.read_text(encoding="utf-8"))
    assert isinstance(data, dict), f"{path}: top-level JSON must be an object"
    return {k: v for k, v in data.items() if isinstance(k, str) and isinstance(v, str)}


@pytest.mark.parametrize("locale", SUPPORTED_LOCALES)
def test_bundle_file_exists_and_is_loaded(locale: str) -> None:
    """Each supported locale must ship a parseable lang/<locale>.json."""
    bundle = _load_bundle(locale)
    assert bundle, f"lang/{locale}.json is empty or has no string entries"


@pytest.mark.parametrize("locale", SUPPORTED_LOCALES)
@pytest.mark.parametrize("code", CANONICAL_NC_CODES)
def test_canonical_code_has_localized_message_key(locale: str, code: str) -> None:
    """``error.<CODE>.message`` must exist in every locale bundle."""
    bundle = _load_bundle(locale)
    key = f"error.{code}.message"
    assert key in bundle, (
        f"lang/{locale}.json is missing localized message key {key!r} "
        f"(VERIFY-007 canonical code {code})"
    )


@pytest.mark.parametrize("locale", SUPPORTED_LOCALES)
@pytest.mark.parametrize("code", CANONICAL_NC_CODES)
def test_canonical_code_has_localized_suggestion_key(locale: str, code: str) -> None:
    """``error.<CODE>.suggestion`` must exist in every locale bundle."""
    bundle = _load_bundle(locale)
    key = f"error.{code}.suggestion"
    assert key in bundle, (
        f"lang/{locale}.json is missing localized suggestion key {key!r} "
        f"(VERIFY-007 canonical code {code})"
    )


@pytest.mark.parametrize("locale", SUPPORTED_LOCALES)
@pytest.mark.parametrize("code", CANONICAL_NC_CODES)
def test_canonical_code_message_is_non_empty(locale: str, code: str) -> None:
    """The localized message must be a non-blank string, not a placeholder."""
    bundle = _load_bundle(locale)
    message = bundle.get(f"error.{code}.message", "")
    assert message.strip(), (
        f"lang/{locale}.json: error.{code}.message is empty/blank "
        f"(VERIFY-007 requires a clear localized message)"
    )
    # The message must not be a bare fallback to the code itself.
    assert message.strip() != code, (
        f"lang/{locale}.json: error.{code}.message is just the code {code!r}, "
        f"not a localized human-readable message"
    )


@pytest.mark.parametrize("locale", SUPPORTED_LOCALES)
@pytest.mark.parametrize("code", CANONICAL_NC_CODES)
def test_canonical_code_suggestion_is_non_empty(locale: str, code: str) -> None:
    """The localized suggestion must be a non-blank string."""
    bundle = _load_bundle(locale)
    suggestion = bundle.get(f"error.{code}.suggestion", "")
    assert suggestion.strip(), (
        f"lang/{locale}.json: error.{code}.suggestion is empty/blank "
        f"(VERIFY-007 requires a clear localized suggestion)"
    )


def test_canonical_set_is_exactly_covered_by_en_us() -> None:
    """The en_US bundle must cover the full canonical set — every canonical
    code present with both message and suggestion.
    """
    bundle = _load_bundle("en_US")
    missing = [
        c for c in CANONICAL_NC_CODES
        if f"error.{c}.message" not in bundle or f"error.{c}.suggestion" not in bundle
    ]
    assert missing == [], (
        f"en_US bundle is missing canonical NC codes: {missing}"
    )


def test_canonical_set_is_exactly_covered_by_zh_cn() -> None:
    """The zh_CN bundle must cover the full canonical set."""
    bundle = _load_bundle("zh_CN")
    missing = [
        c for c in CANONICAL_NC_CODES
        if f"error.{c}.message" not in bundle or f"error.{c}.suggestion" not in bundle
    ]
    assert missing == [], (
        f"zh_CN bundle is missing canonical NC codes: {missing}"
    )


def test_en_us_and_zh_cn_keys_are_in_parity() -> None:
    """The two locale bundles must expose the SAME set of error.NC-* keys.

    A code present in one locale but not the other would mean one language
    silently falls back to the other locale's text, violating VERIFY-007's
    "each platform shows a clear localized message in every supported locale"
    requirement. This is the automatic set-difference check the audit asks for.
    """
    en = _load_bundle("en_US")
    zh = _load_bundle("zh_CN")
    en_error_keys = {k for k in en if k.startswith("error.NC-")}
    zh_error_keys = {k for k in zh if k.startswith("error.NC-")}
    only_in_en = en_error_keys - zh_error_keys
    only_in_zh = zh_error_keys - en_error_keys
    assert not only_in_en, (
        f"NC error keys only in en_US (missing from zh_CN): {sorted(only_in_en)}"
    )
    assert not only_in_zh, (
        f"NC error keys only in zh_CN (missing from en_US): {sorted(only_in_zh)}"
    )


@pytest.mark.parametrize("locale", SUPPORTED_LOCALES)
@pytest.mark.parametrize("code", CANONICAL_NC_CODES)
def test_error_message_returns_localized_text_not_fallback(locale: str, code: str) -> None:
    """``I18n.error_message`` must return real localized text for every code.

    The formatter combines message + suggestion into ``§c{msg} §7{prefix} {sug}``.
    If either the message or suggestion key were missing, the provider would
    fall back to the key itself (e.g. ``error.NC-404.message``), which is NOT a
    clear localized message. This test catches that regression by asserting
    the formatted output contains the bundle's message text and the suggestion
    text, and does NOT contain the raw key.
    """
    i18n = I18n()
    bundle = _load_bundle(locale)
    expected_message = bundle[f"error.{code}.message"]
    expected_suggestion = bundle[f"error.{code}.suggestion"]

    formatted = i18n.error_message(code, locale)

    assert expected_message in formatted, (
        f"{locale}: error_message({code}) must contain the localized message "
        f"{expected_message!r}; got {formatted!r}"
    )
    assert expected_suggestion in formatted, (
        f"{locale}: error_message({code}) must contain the localized suggestion "
        f"{expected_suggestion!r}; got {formatted!r}"
    )
    # The raw lookup key must NOT leak through (that would indicate a fallback).
    assert f"error.{code}.message" not in formatted, (
        f"{locale}: error_message({code}) leaked the raw key "
        f"'error.{code}.message' — i18n fallback, not a localized message"
    )
    assert f"error.{code}.suggestion" not in formatted, (
        f"{locale}: error_message({code}) leaked the raw suggestion key"
    )


def test_canonical_codes_are_a_superset_of_bundle_error_codes() -> None:
    """Every NC error key present in the bundles must be in the canonical set
    OR explicitly allowed as a non-canonical extension. Today the bundles
    contain exactly the canonical set, so any new error.NC-* key added to a
    bundle without extending CANONICAL_NC_CODES is caught here.
    """
    canonical = set(CANONICAL_NC_CODES)
    for locale in SUPPORTED_LOCALES:
        bundle = _load_bundle(locale)
        bundle_codes = {
            k[len("error."):-len(".message")]
            for k in bundle
            if k.startswith("error.NC-") and k.endswith(".message")
        }
        extras = bundle_codes - canonical
        assert not extras, (
            f"{locale}: bundle defines error.NC-* codes not in the canonical "
            f"VERIFY-007 set: {sorted(extras)}. If these are intentional, extend "
            f"CANONICAL_NC_CODES in this test."
        )
