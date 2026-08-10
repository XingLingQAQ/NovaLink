import React from 'react';
import { cva } from 'class-variance-authority';
import { cn } from '../../lib/cn';

/**
 * shadcn/ui-style Badge (lightweight). Mirrors logs/frontend/src/components/ui/badge.tsx:
 * rounded-full, h-5, text-[11px], border, with bg-tint/10 + text-token variants.
 */
const badgeVariants = cva(
  'inline-flex h-5 items-center justify-center rounded-full border px-1.5 py-0 text-[11px] font-normal leading-none shadow-none transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
  {
    variants: {
      variant: {
        default: 'border-transparent bg-primary/10 text-primary hover:bg-primary/15',
        secondary:
          'border-transparent bg-secondary/70 text-secondary-foreground hover:bg-secondary',
        destructive:
          'border-transparent bg-destructive/10 text-destructive hover:bg-destructive/15',
        outline: 'border-border bg-transparent text-foreground',
        success:
          'border-transparent bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/15',
        warning:
          'border-transparent bg-amber-500/10 text-amber-600 dark:text-amber-400 hover:bg-amber-500/15',
        info:
          'border-transparent bg-sky-500/10 text-sky-600 dark:text-sky-400 hover:bg-sky-500/15',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  }
);

function Badge({ className, variant = 'default', ...props }) {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge as default };
