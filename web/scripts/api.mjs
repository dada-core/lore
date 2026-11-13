// Regenerates the API description (web/openapi.json) from the server code, then the TypeScript
// types of the interface (src/api/schema.d.ts). Run it after changing a controller: npm run api
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const root = resolve(web, '..');
const windows = process.platform === 'win32';

function run(command, args, cwd, env) {
  const result = spawnSync(command, args, { cwd, env: { ...process.env, ...env }, stdio: 'inherit', shell: windows });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

run(windows ? `"${resolve(root, 'mvnw.cmd')}"` : './mvnw',
  ['-q', '-B', 'test', '-Dtest=OpenApiTests', '-Dsurefire.failIfNoSpecifiedTests=false'],
  root,
  { LORE_OPENAPI_OUTPUT: resolve(web, 'openapi.json') });
run('npx', ['openapi-typescript', 'openapi.json', '-o', 'src/api/schema.d.ts'], web);
