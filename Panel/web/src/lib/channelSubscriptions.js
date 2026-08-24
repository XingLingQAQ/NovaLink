/**
 * Computes the exact channel set needed by the current panel view.
 * Backend-provided `subscribable` is authoritative; a forged/local-only
 * channel object is never enough to make it into the subscription set.
 */
export function desiredChannelSubscriptions(channels, activeTab, selectedChannelId = 'all') {
  const authorized = (Array.isArray(channels) ? channels : [])
    .filter((channel) => channel && channel.id && channel.subscribable === true);

  if (activeTab === 'dashboard') {
    return authorized
      .filter((channel) => channel.type === 'GLOBAL')
      .map((channel) => channel.id);
  }

  if (activeTab !== 'messages') return [];

  if (selectedChannelId && selectedChannelId !== 'all') {
    const selected = authorized.find((channel) => channel.id === selectedChannelId);
    return selected ? [selected.id] : [];
  }

  return authorized
    .filter((channel) => channel.type !== 'PRIVATE')
    .map((channel) => channel.id);
}

/** Computes subscribe/unsubscribe commands needed to reach an exact desired set. */
export function subscriptionDelta(desiredChannels, subscribedChannels, pendingChannels) {
  const desired = new Set(desiredChannels || []);
  const subscribed = new Set(subscribedChannels || []);
  const pending = new Set(pendingChannels || []);
  const obsolete = new Set(
    [...subscribed, ...pending].filter((channel) => !desired.has(channel)),
  );
  const missing = [...desired].filter(
    (channel) => !subscribed.has(channel) && !pending.has(channel),
  );
  return { subscribe: missing, unsubscribe: [...obsolete] };
}

export default desiredChannelSubscriptions;
