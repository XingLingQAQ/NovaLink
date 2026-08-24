// Register the test-only loader hooks (resolve + load). Used via
// `node --import ./_test/register.mjs --test`.
//
// resolve hook: Vite resolves extensionless imports like './ui/Card' to
// Card.jsx during dev/build; plain Node does not. Our components use that
// convention, so without the resolve hook importing any component under
// node --test fails with ERR_MODULE_NOT_FOUND.
//
// load hook: transpiles .jsx (via vite's transformWithOxc) and rewrites the
// one Vite-only `import.meta.glob` in src/i18n.js. Details in loader.mjs.
import { register } from 'node:module';
register('./loader.mjs', import.meta.url);
