import React, { useState } from 'react';
import { cn } from '../../lib/cn';

/**
 * shadcn/ui-style Tabs (lightweight, controlled or uncontrolled).
 * Mirrors logs/frontend/src/components/ui/tabs.tsx list/trigger styling:
 * pill list rounded-full bg-muted h-8, triggers h-7 px-3 text-xs rounded-full.
 *
 * Props:
 * - tabs: Array<{ value, label, icon? }>
 * - value / defaultValue: active tab value
 * - onChange(value)
 */
function Tabs({ tabs = [], value, defaultValue, onChange, className, ...props }) {
  const [internal, setInternal] = useState(defaultValue ?? tabs[0]?.value);
  const active = value !== undefined ? value : internal;
  const select = (v) => {
    if (value === undefined) setInternal(v);
    if (onChange) onChange(v);
  };
  return (
    <div
      className={cn(
        'relative isolate inline-flex h-8 w-fit items-center gap-1 rounded-full bg-muted p-0.5',
        className
      )}
      {...props}
    >
      {tabs.map((tab) => {
        const Icon = tab.icon;
        const isActive = tab.value === active;
        return (
          <button
            type="button"
            key={tab.value}
            onClick={() => select(tab.value)}
            className={cn(
              'relative z-10 inline-flex h-7 items-center justify-center rounded-full px-3 text-xs font-medium text-muted-foreground outline-none transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/50',
              isActive && 'bg-background text-foreground shadow-sm'
            )}
          >
            {Icon && <Icon size={14} />}
            {tab.label}
            {tab.badge != null && tab.badge > 0 && (
              <span className="ml-1 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold text-destructive-foreground">
                {tab.badge}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

export { Tabs as default };
