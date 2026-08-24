// Test-only ESM hooks (resolve + load) for `node --import ./_test/register.mjs --test`.
//
// The project runs `node --test` (not vitest) and has no jsdom. Two constructs
// in src/ are unparseable by plain Node, so we minimally rewrite only the
// files that need it; every other file falls through to Node's default loader
// UNCHANGED (the existing baseline tests must stay unaffected):
//
//   1. src/i18n.js calls `import.meta.glob('./lang/*.json', {eager:true})` — a
//      Vite-only construct. We rewrite it into a static gather of every
//      src/lang/*.json so i18n gets real translations at test time.
//
//   2. .jsx files are not parseable by plain Node. We transpile them with
//      @babel/core (a transitive devDependency already installed) using an
//      inline JSX->React.createElement plugin, and stub `createPortal` so
//      Modal.jsx can render to static markup with no document.body.
//
//      We deliberately do NOT use vite's transformWithOxc here: its native
//      binding access-violates (exit 0xC0000005) on Windows/Node 24 when the
//      test worker exits, which crashes the whole suite even on green tests.
//      Babel is pure JS and exits cleanly.
//
// resolve hook: Vite resolves extensionless imports like './ui/Card' to
// Card.jsx; plain Node does not. Our components use that convention, so the
// resolve hook tries .jsx/.js for relative extensionless specifiers.

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { extname } from 'node:path';
import { createRequire } from 'node:module';
import { transformSync } from '@babel/core';

const require = createRequire(import.meta.url);
const t = require('@babel/types');

// Build the JSX->React.createElement transform plugin once. Pure JS, no native
// binding, so it does not destabilize the test worker on exit.
const jsxPlugin = function () {
  function transformJSXName(name) {
    if (t.isJSXIdentifier(name)) {
      // lowercase first char => host element (string tag); else component ref.
      if (/^[a-z]/.test(name.name)) return t.stringLiteral(name.name);
      return t.identifier(name.name);
    }
    if (t.isJSXMemberExpression(name)) {
      // e.g. Foo.Bar -> member expression
      return t.memberExpression(transformJSXName(name.object), t.identifier(name.property.name));
    }
    if (t.isJSXNamespacedName(name)) {
      return t.stringLiteral(`${name.namespace.name}:${name.name.name}`);
    }
    throw new Error('Unsupported JSX name type');
  }

  function transformAttrValue(value) {
    if (!value) return t.booleanLiteral(true);
    if (t.isStringLiteral(value)) return value;
    if (t.isJSXExpressionContainer(value)) {
      if (t.isJSXEmptyExpression(value.expression)) return t.nullLiteral();
      return value.expression;
    }
    if (t.isJSXElement(value) || t.isJSXFragment(value)) return transformNode(value);
    return value;
  }

  function transformAttr(attr) {
    if (t.isJSXAttribute(attr)) {
      // Preserve the raw attribute name as a string key (className, data-x,
      // aria-label, etc. all become string-literal object keys).
      return t.objectProperty(t.stringLiteral(attr.name.name), transformAttrValue(attr.value));
    }
    if (t.isJSXSpreadAttribute(attr)) return t.spreadElement(attr.argument);
    return null;
  }

  function transformChild(child) {
    if (t.isJSXText(child)) {
      // Collapse JSX text: strip newlines+indent, preserve single interior
      // spaces. Empty result (whitespace-only between tags) drops the child.
      const raw = child.value;
      const out = raw.split('\n').map((l) => l.trim()).join(' ').replace(/\s+/g, ' ').trim();
      if (!out) return null;
      return t.stringLiteral(out);
    }
    if (t.isJSXExpressionContainer(child)) {
      if (t.isJSXEmptyExpression(child.expression)) return null;
      return child.expression;
    }
    if (t.isJSXSpreadChild(child)) return t.spreadElement(child.expression);
    if (t.isJSXElement(child) || t.isJSXFragment(child)) return transformNode(child);
    return null;
  }

  function transformNode(node) {
    if (t.isJSXFragment(node)) {
      const children = node.children.map(transformChild).filter(Boolean);
      return t.callExpression(
        t.memberExpression(t.identifier('React'), t.identifier('createElement')),
        [t.memberExpression(t.identifier('React'), t.identifier('Fragment')), t.objectExpression([]), ...children]
      );
    }
    const tag = transformJSXName(node.openingElement.name);
    const props = t.objectExpression(
      node.openingElement.attributes.map(transformAttr).filter(Boolean)
    );
    const children = node.children.map(transformChild).filter(Boolean);
    return t.callExpression(
      t.memberExpression(t.identifier('React'), t.identifier('createElement')),
      [tag, props, ...children]
    );
  }

  return {
    visitor: {
      JSXElement(path) { path.replaceWith(transformNode(path.node)); },
      JSXFragment(path) { path.replaceWith(transformNode(path.node)); },
    },
  };
};

function isUnderSrc(url) {
  try {
    const p = fileURLToPath(url).replace(/\\/g, '/');
    return p.includes('/src/');
  } catch {
    return false;
  }
}

// Replace `import.meta.glob(...)` in i18n.js with a static gather of every
// src/lang/*.json (read the dir at test time so new locales are picked up
// automatically, mirroring the Vite glob behavior).
function patchI18nGlob(source, srcRoot) {
  const langDir = srcRoot + '/lang';
  let files = ['en_US.json', 'zh_CN.json'];
  try {
    if (existsSync(langDir)) {
      const all = readdirSync(langDir).filter((f) => f.endsWith('.json')).sort();
      if (all.length) files = all;
    }
  } catch {
    /* keep defaults */
  }
  const importLines = files
    .map((f, i) => `import __locale${i} from './lang/${f}' with { type: 'json' };`)
    .join('\n');
  const entries = files.map((f, i) => `'./lang/${f}': __locale${i}`).join(',\n  ');
  const replacement = `const localeModules = {\n  ${entries}\n};`;
  return source.replace(
    /const\s+localeModules\s*=\s*import\.meta\.glob\([^)]*\)\s*;?/s,
    `${importLines}\n${replacement}`
  );
}

// Stub createPortal so Modal.jsx renders inline under SSR (no document.body).
// Replaces the 2-arg `createPortal(node, document.body)` call with just `node`.
function stubCreatePortal(source) {
  // Remove createPortal from react-dom import lists.
  source = source.replace(
    /(import\s*\{)([^}]*)(\}\s*from\s*['"]react-dom['"])/g,
    (m, head, names, tail) => {
      const cleaned = names
        .split(',')
        .map((s) => s.trim())
        .filter((s) => s && s !== 'createPortal')
        .join(', ');
      return cleaned ? `${head} ${cleaned} ${tail}` : '';
    }
  );
  // Replace createPortal(X, document.body) -> (X). Matches call spans with
  // balanced parens so nested JSX/createElement calls survive.
  source = source.replace(/createPortal\s*\(([\s\S]*?),\s*document\.body\s*\)/g, '($1)');
  return source;
}

// Transpile JSX in a .jsx/.tsx source string to React.createElement calls.
function transpileJsx(source, filename) {
  const out = transformSync(source, {
    filename,
    babelrc: false,
    configFile: false,
    parserOpts: { plugins: ['jsx'] },
    plugins: [jsxPlugin],
  });
  return out.code;
}

// --- resolve hook: extensionless relative specifiers -> .jsx/.js -----------
export async function resolve(specifier, context, nextResolve) {
  // Only rewrite RELATIVE specifiers WITHOUT an extension. Absolute,
  // bare-module, and already-extensioned specifiers pass through unchanged.
  if (
    (specifier.startsWith('./') || specifier.startsWith('../')) &&
    !extname(specifier)
  ) {
    const exts = ['.jsx', '.js', '.mjs', '/index.jsx', '/index.js'];
    for (const ext of exts) {
      try {
        return await nextResolve(specifier + ext, context);
      } catch {
        /* try next extension */
      }
    }
  }
  return nextResolve(specifier, context);
}

// --- load hook: i18n glob rewrite + JSX transpile --------------------------
export async function load(url, context, defaultLoad) {
  if (!isUnderSrc(url)) return defaultLoad(url, context);

  const ext = extname(url);
  const isJsxish = ext === '.jsx' || ext === '.tsx';
  const isJs = ext === '.js' || ext === '.mjs' || ext === '.cjs';
  if (!isJsxish && !isJs) return defaultLoad(url, context);

  let source;
  try {
    source = readFileSync(fileURLToPath(url), 'utf8');
  } catch {
    return defaultLoad(url, context);
  }

  const srcRoot = process.cwd().replace(/\\/g, '/') + '/src';
  let changed = false;

  // Patch import.meta.glob in i18n.js (the only file that uses it).
  // Safe to run on any .js — the regex only matches the literal glob call.
  if (source.includes('import.meta.glob')) {
    source = patchI18nGlob(source, srcRoot);
    changed = true;
  }

  // Transpile ONLY by extension. Every .jsx/.tsx in this project is a React
  // component; every .js is plain JS with no JSX. (Content-sniffing .js for
  // JSX is unsafe and was removed.)
  if (isJsxish) {
    try {
      source = transpileJsx(source, fileURLToPath(url).replace(/\\/g, '/'));
      source = stubCreatePortal(source);
      changed = true;
    } catch {
      /* transpile failed — leave source; caller will get a parse error */
    }
  }

  if (!changed) {
    // For .jsx/.tsx that fell through without being changed (e.g. transpile
    // threw and left source unchanged), we STILL must force format='module'
    // + shortCircuit so Node doesn't reject the .jsx extension. The transpile
    // catch above leaves the original JSX source, which Node can't parse, but
    // at least the extension error won't mask the real parse error.
    if (isJsxish) {
      return { source, format: 'module', shortCircuit: true };
    }
    return defaultLoad(url, context);
  }

  return { source, format: 'module', shortCircuit: true };
}
