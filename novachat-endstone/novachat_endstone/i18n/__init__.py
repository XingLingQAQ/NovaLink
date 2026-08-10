"""I18n message package for NovaChat-Endstone.

Provides zh_CN/en_US message lookup keyed on the same keys used by the
Java client-core bundles (messages_zh_CN.properties / messages_en_US.properties),
so cross-platform behaviour is consistent.
"""

from novachat_endstone.i18n.provider import I18n

__all__ = ["I18n"]
