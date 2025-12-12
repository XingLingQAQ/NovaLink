import React from 'react';

function hashString(input) {
  if (!input) return 0;
  let hash = 0;
  for (let i = 0; i < input.length; i += 1) {
    hash = (hash * 31 + input.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

function getInitial(name) {
  const trimmed = (name || '').trim();
  if (!trimmed) return '?';
  // Support Chinese / non-latin names by taking the first visible character.
  return trimmed[0].toUpperCase();
}

/**
 * Offline-friendly avatar component (no external image requests).
 *
 * Props:
 * - name: string
 * - size: number (px)
 * - rounded: tailwind class string, e.g. "rounded" | "rounded-full"
 * - className: additional class names
 */
export default function Avatar({
  name = '',
  size = 32,
  rounded = 'rounded-full',
  className = ''
}) {
  const label = (name || '').trim() || 'Unknown';
  const initial = getInitial(label);
  const hue = hashString(label) % 360;

  const style = {
    width: size,
    height: size,
    backgroundColor: `hsl(${hue}, 70%, 45%)`,
    fontSize: Math.max(10, Math.floor(size * 0.45)),
    lineHeight: 1
  };

  return (
    <div
      className={`shrink-0 ${rounded} flex items-center justify-center text-white font-semibold select-none ${className}`}
      style={style}
      title={label}
      aria-label={label}
    >
      {initial}
    </div>
  );
}


