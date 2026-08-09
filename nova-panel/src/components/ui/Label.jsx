import React from 'react';
import { cn } from '../../lib/cn';

/**
 * shadcn/ui-style Label (lightweight, native <label> — no radix).
 * Mirrors logs/frontend/src/components/ui/label.tsx: text-xs text-muted-foreground.
 */
const Label = React.forwardRef(({ className, ...props }, ref) => (
  <label
    ref={ref}
    className={cn('text-xs font-normal leading-none text-muted-foreground peer-disabled:cursor-not-allowed peer-disabled:opacity-70', className)}
    {...props}
  />
));
Label.displayName = 'Label';

export { Label as default };
