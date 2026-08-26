import test from 'node:test';
import assert from 'node:assert/strict';

// §11.6 item 15 / PANEL-009 — accessibility-semantics contract tests for the
// three shared ui primitives: CustomSelect.jsx, Modal.jsx, Select.jsx.
//
// Test harness note (mirrors ConfigHistory.test.js): the project runs
// `node --test` (NOT vitest) with a custom ESM loader (_test/loader.mjs) that
// transpiles .jsx imports (and stubs createPortal for Modal). This file is
// plain .js using React.createElement so it needs no transpile and is
// discoverable by `node --test`.
//
// SSR limitation (verified): renderToStaticMarkup does NOT run useEffect, so
// none of the document-level listeners these components register (outside-click
// close, Modal's Escape/Tab keydown handler, inert-root application, focus
// move/restore) ever fire here, and state stays at its initial value. We
// therefore pin the RENDERED SEMANTIC CONTRACT only:
//   - CustomSelect / Select: trigger button ARIA (combobox/listbox wiring,
//     aria-expanded=false, aria-controls, accessible-name fallback chain),
//     collapsed listbox markup with role="option" + aria-selected per option,
//     and the closed-menu animation class branch.
//   - Modal: open vs closed prop branches (closed renders nothing; open
//     renders role="dialog" + aria-modal + aria-labelledby pointing at the
//     rendered title id, plus a labelled close button).
//
// Keyboard behavior (Esc-to-close, Tab focus trap, arrow navigation,
// focus move into the panel and restore to the trigger) is implemented in
// effects/handlers that this harness cannot drive; it is gated by the
// VERIFY-009 browser slice (jshook/Camoufox E2E), NOT faked here.

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Importing i18n.js initializes the i18next instance (registers zh_CN/en_US
// resources) so Modal's t('common.close') returns a real translation.
import '../../i18n.js';

import CustomSelect from './CustomSelect.jsx';
import Modal from './Modal.jsx';
import Select from './Select.jsx';

const OPTS = [
  { value: 'debug', label: 'Debug' },
  { value: 'info', label: 'Info' },
  { value: 'warn', label: 'Warn' },
];

// Safety-net fetch stub. Effects don't fire in renderToStaticMarkup so the
// components never call fetch during a render, but we keep the global patched
// so an unexpected import-time fetch can't hit the network.
function withFetch() {
  const original = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    text: async () => '',
  });
  return () => { globalThis.fetch = original; };
}

function attr(markup, name) {
  const m = markup.match(new RegExp(`${name}="([^"]*)"`));
  return m ? m[1] : null;
}

// ====================== CustomSelect ======================

test('CustomSelect: trigger button carries listbox popup semantics with aria-expanded=false when collapsed', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(CustomSelect, { options: OPTS, defaultValue: 'info' })
    );

    // The trigger is a real <button> (focusable, Enter/Space native click).
    assert.match(html, /<button/, 'trigger is a native <button> element');
    assert.equal(attr(html, 'aria-haspopup'), 'listbox');
    // Initial state is closed — SSR pins the collapsed branch of aria-expanded.
    assert.equal(attr(html, 'aria-expanded'), 'false');

    // aria-controls points at the id of the rendered listbox element.
    const controls = attr(html, 'aria-controls');
    assert.ok(controls, 'trigger has aria-controls');
    assert.match(html, new RegExp(`id="${controls}"[^>]*role="listbox"`),
      'aria-controls references the rendered role=listbox element');

    // Accessible name falls back to the selected option label.
    assert.equal(attr(html, 'aria-label'), 'Info');
  } finally {
    undo();
  }
});

test('CustomSelect: aria-label prop overrides the selected-label naming fallback', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(CustomSelect, {
        options: OPTS,
        defaultValue: 'info',
        'aria-label': 'Log level',
      })
    );
    assert.equal(attr(html, 'aria-label'), 'Log level');
  } finally {
    undo();
  }
});

test('CustomSelect: without any selection or aria-label the trigger is still named ("Select option")', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(CustomSelect, { options: OPTS })
    );
    assert.equal(attr(html, 'aria-label'), 'Select option',
      'PANEL-009 naming fallback keeps the control operable by screen readers');
  } finally {
    undo();
  }
});

test('CustomSelect: listbox renders role=option children with aria-selected pinned to the current value', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(CustomSelect, { options: OPTS, defaultValue: 'warn' })
    );
    const optionCount = (html.match(/role="option"/g) || []).length;
    assert.equal(optionCount, 3, 'one role=option per normalized option');

    // Selected option carries aria-selected=true, others false.
    assert.match(html, /aria-selected="true"[^>]*>Warn</);
    assert.equal((html.match(/aria-selected="true"/g) || []).length, 1,
      'exactly one selected option');
    assert.equal((html.match(/aria-selected="false"/g) || []).length, 2);

    // Options are plain divs (not focusable themselves); screen readers follow
    // the highlight via the trigger's aria-activedescendant instead.
    assert.doesNotMatch(html, /role="option"[^>]*tabindex="0"/);
  } finally {
    undo();
  }
});

test('CustomSelect: plain-string options are normalized to value+label', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(CustomSelect, { options: ['alpha', 'beta'], defaultValue: 'beta' })
    );
    assert.equal((html.match(/role="option"/g) || []).length, 2);
    assert.match(html, />beta</);
    assert.match(html, /aria-selected="true"/);
    // The visible trigger label mirrors the raw string value when no label exists.
    assert.match(html, />beta<\/span>/);
  } finally {
    undo();
  }
});

test('CustomSelect: collapsed menu carries the hidden-state classes (pointer-events-none)', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(CustomSelect, { options: OPTS })
    );
    // Initial state is closed; the menu is visually suppressed via Tailwind
    // classes. (open=true markup is unreachable under SSR — state lives in
    // useState — so the open branch is VERIFY-009 browser-slice territory.)
    assert.match(html, /pointer-events-none/);
    assert.match(html, /opacity-0 scale-95 -translate-y-1/);
  } finally {
    undo();
  }
});

// ====================== Select ======================

test('Select: trigger carries WAI-ARIA combobox semantics (role=combobox + listbox wiring)', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Select, { options: OPTS, defaultValue: 'info' })
    );
    assert.match(html, /<button[^>]*role="combobox"/, 'trigger announces as combobox');
    assert.equal(attr(html, 'aria-haspopup'), 'listbox');
    assert.equal(attr(html, 'aria-expanded'), 'false', 'collapsed on initial render');

    const controls = attr(html, 'aria-controls');
    assert.ok(controls, 'trigger has aria-controls');
    assert.match(html, new RegExp(`id="${controls}"[^>]*role="listbox"`),
      'aria-controls references the rendered role=listbox element');
    assert.doesNotMatch(html, /aria-activedescendant=/,
      'activedescendant is omitted while closed (no highlight yet)');
  } finally {
    undo();
  }
});

test('Select: accessible-name fallback chain — aria-label prop wins over selected text', () => {
  const undo = withFetch();
  try {
    const named = renderToStaticMarkup(
      React.createElement(Select, {
        options: OPTS,
        defaultValue: 'info',
        'aria-label': 'Severity',
      })
    );
    assert.equal(attr(named, 'aria-label'), 'Severity');

    const unnamed = renderToStaticMarkup(
      React.createElement(Select, { options: OPTS, defaultValue: 'warn' })
    );
    assert.equal(attr(unnamed, 'aria-label'), 'Warn',
      'falls back to the selected option label');
  } finally {
    undo();
  }
});

test('Select: placeholder is used as both the visible text and the accessible name when nothing is selected', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Select, { options: OPTS, placeholder: 'Pick one' })
    );
    assert.equal(attr(html, 'aria-label'), 'Pick one');
    assert.match(html, /text-muted-foreground/, 'placeholder styling branch');
    assert.match(html, /<span[^>]*>Pick one<\/span>/);
  } finally {
    undo();
  }
});

test('Select: aria-labelledby is forwarded and suppresses the implicit aria-label fallback', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Select, {
        options: OPTS,
        defaultValue: 'info',
        'aria-labelledby': 'external-heading-id',
      })
    );
    assert.equal(attr(html, 'aria-labelledby'), 'external-heading-id');
    assert.equal(attr(html, 'aria-label'), null,
      'no implicit name when an explicit labelling association exists');
  } finally {
    undo();
  }
});

test('Select: options render role=option with aria-selected reflecting the controlled/uncontrolled value', () => {
  const undo = withFetch();
  try {
    const uncontrolled = renderToStaticMarkup(
      React.createElement(Select, { options: OPTS, defaultValue: 'debug' })
    );
    assert.equal((uncontrolled.match(/role="option"/g) || []).length, 3);
    assert.match(uncontrolled, /aria-selected="true"[^>]*>Debug</);
    assert.equal((uncontrolled.match(/aria-selected="true"/g) || []).length, 1);

    // Controlled value must win over internal default state.
    const controlled = renderToStaticMarkup(
      React.createElement(Select, { options: OPTS, defaultValue: 'debug', value: 'warn' })
    );
    assert.match(controlled, /aria-selected="true"[^>]*>Warn</);
  } finally {
    undo();
  }
});

test('Select: object options use their label in the menu while selection still matches the value', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Select, {
        options: [
          { value: 'v-low', label: 'Low severity' },
          { value: 'v-high', label: 'High severity' },
        ],
        defaultValue: 'v-high',
      })
    );
    assert.match(html, /aria-selected="true"[^>]*>High severity</);
    assert.match(html, /aria-selected="false"[^>]*>Low severity</);
  } finally {
    undo();
  }
});

test('Select: collapsed popup carries the hidden-state classes; listbox is not tabbable itself', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Select, { options: OPTS })
    );
    assert.match(html, /pointer-events-none/);
    assert.match(html, /opacity-0 scale-95 -translate-y-1/);
    assert.doesNotMatch(html, /role="listbox"[^>]*tabindex="0"/,
      'listbox relies on activedescendant, not its own tab stop');
  } finally {
    undo();
  }
});

// ====================== Modal ======================

test('Modal: isOpen=false renders nothing (no dialog markup leaks into the page)', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Modal, { isOpen: false, onClose: () => {}, title: 'Hidden' }, 'body')
    );
    assert.equal(html, '');
    assert.doesNotMatch(html, /role="dialog"/);
  } finally {
    undo();
  }
});

test('Modal: isOpen=true renders role=dialog with aria-modal and a working title association', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Modal, { isOpen: true, onClose: () => {}, title: 'Confirm rollback' }, 'body')
    );
    assert.match(html, /role="dialog"/);
    assert.equal(attr(html, 'aria-modal'), 'true');

    // aria-labelledby resolves to the rendered <h3> title element id.
    const labelledby = attr(html, 'aria-labelledby');
    assert.ok(labelledby, 'dialog has aria-labelledby');
    assert.match(html, new RegExp(`<h3 id="${labelledby}"[^>]*>Confirm rollback</h3>`),
      'title heading carries the referenced id');

    // Backdrop is present behind the dialog panel.
    assert.match(html, /bg-black\/20 backdrop-blur/);
  } finally {
    undo();
  }
});

test('Modal: close control is a real button with a translated accessible name', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Modal, { isOpen: true, onClose: () => {}, title: 'T' }, 'body')
    );
    // t('common.close') === "Close" in en_US (i18n initialized above; the
    // language detector has no DOM in SSR so fallbackLng zh_CN would give
    // 关闭 — either way the name must exist and be non-empty, never the bare
    // key "common.close").
    const closeLabel = attr(html, 'aria-label');
    assert.ok(closeLabel && closeLabel.length > 0, 'close button is named');
    assert.notEqual(closeLabel, 'common.close', 'never leaks the i18n key');
    assert.match(
      html,
      new RegExp(`<button[^>]*aria-label="${closeLabel}"`),
      'the name sits on the dedicated close <button>'
    );
  } finally {
    undo();
  }
});

test('Modal: dialog content and children render inside the portal-substituted tree', () => {
  const undo = withFetch();
  try {
    const html = renderToStaticMarkup(
      React.createElement(Modal, { isOpen: true, title: 'Edit rule' },
        React.createElement('p', null, 'Rule body text'))
    );
    assert.match(html, />Edit rule</);
    assert.match(html, />Rule body text</);
  } finally {
    undo();
  }
});
