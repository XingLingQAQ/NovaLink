/**
 * cn — merge class names with tailwind-merge so later Tailwind utilities win.
 * Mirrors the shadcn/ui `cn` helper (clsx + tailwind-merge).
 */
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs) {
  return twMerge(clsx(inputs));
}

export default cn;
