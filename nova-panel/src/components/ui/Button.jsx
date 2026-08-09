import React from 'react';
import { cva } from 'class-variance-authority';
import { cn } from '../../lib/cn';

/**
 * shadcn/ui-style Button (lightweight, no radix dependency).
 * Mirrors logs/frontend/src/components/ui/button.tsx: pill (rounded-full),
 * small heights, text-xs. Uses class-variance-authority + the oklch tokens from
 * index.css so the button re-themes automatically with the .dark class on <html>.
 * Legacy `theme`/`mode` props are accepted for backward compatibility but ignored.
 */
const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground hover:bg-primary/84',
        destructive: 'bg-destructive text-destructive-foreground hover:bg-destructive/90',
        outline:
          'border border-input bg-background hover:bg-accent hover:text-accent-foreground',
        secondary:
          'bg-secondary text-secondary-foreground hover:bg-secondary/80',
        ghost: 'hover:bg-accent hover:text-accent-foreground',
        link: 'text-primary underline-offset-4 hover:underline',
      },
      size: {
        default: 'h-8 px-3 text-xs',
        sm: 'h-8 px-3 text-xs',
        lg: 'h-9 px-5 text-sm',
        icon: 'h-8 w-8 text-xs',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  }
);

const Button = ({
  children,
  variant = 'default',
  size,
  theme, // legacy — ignored (tokens auto-switch with .dark)
  mode, // legacy — ignored
  onClick,
  className = '',
  disabled = false,
  type = 'button',
  title,
  ...props
}) => {
  void theme; void mode;
  return (
    <button
      type={type}
      onClick={disabled ? undefined : onClick}
      disabled={disabled}
      title={title}
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    >
      {children}
    </button>
  );
};

export { Button as default };
