/**
 * Compile le sous-ensemble testable de app/src en CommonJS dans app/.test-build/.
 * Émettre DANS app/ laisse Node résoudre « upng-js » depuis app/node_modules.
 * Le sous-ensemble est celui qui ne dépend ni de react-native ni d'expo :
 * parse.ts, api.ts, utils/mask.ts (+ leurs imports).
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const APP_DIR = path.resolve(HERE, '../..');
export const BUILD_DIR = path.join(APP_DIR, '.test-build');

const ENTRY_FILES = ['src/parse.ts', 'src/api.ts', 'src/utils/mask.ts', 'src/types/upng-js.d.ts'];

const TSC_ARGS = ['--ignoreConfig', ...ENTRY_FILES, '--outDir', BUILD_DIR,
  '--module', 'commonjs', '--target', 'es2022', '--esModuleInterop',
  '--skipLibCheck', '--moduleResolution', 'node', '--ignoreDeprecations', '6.0'];

export function buildClient() {
  const tsc = path.join(APP_DIR, 'node_modules', '.bin', 'tsc');
  if (!existsSync(tsc)) throw new Error(`tsc absent (${tsc}) — lance « npm install » dans app/`);
  mkdirSync(BUILD_DIR, { recursive: true });
  const result = spawnSync(tsc, TSC_ARGS, { cwd: APP_DIR, encoding: 'utf8' });
  const output = `${result.stdout || ''}${result.stderr || ''}`.trim();
  if (result.status !== 0) throw new Error(`tsc a échoué :\n${output}`);
  console.log(`  build TS ok → ${path.relative(process.cwd(), BUILD_DIR)}`);
  const require = createRequire(path.join(APP_DIR, 'package.json'));
  return {
    dir: BUILD_DIR,
    /** Charge un module compilé : load('parse.js'), load('utils/mask.js')… */
    load: (relative) => require(path.join(BUILD_DIR, relative)),
    /** Charge une dépendance de app/node_modules : require('upng-js'). */
    require,
  };
}