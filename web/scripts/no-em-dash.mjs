// Fails when a file of the repository contains an em dash (U+2014) or an en dash (U+2013): the
// project uses neither, in code, comments, interface text, scripts or documentation. ESLint checks
// the scripts of the interface; this covers every other text file of the repository.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { basename, dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const EXTENSIONS = new Set([
  '.css', '.html', '.js', '.mjs', '.ts', '.tsx', '.json', '.svg', '.md',
  '.java', '.sql', '.sh', '.cmd', '.yml', '.yaml', '.xml', '.properties', '.service', '.env', '.txt', '.dockerignore',
]);
const NAMES = new Set(['Dockerfile', 'lore', '.gitignore', '.dockerignore', 'mvnw']);
// Built, downloaded, or kept beside the code (editors, files imported in development); Local/ is
// never published.
const SKIP = new Set(['node_modules', 'dist', 'target', 'build', '.git', '.mvn', 'Local', 'site', 'fonts', 'e2e-results', 'e2e-report', '.auth',
  '.idea', '.vscode', 'data']);
const DASHES = [String.fromCharCode(0x2014), String.fromCharCode(0x2013)];

const found = [];
function scan(path) {
  for (const name of readdirSync(path)) {
    const file = join(path, name);
    if (statSync(file).isDirectory()) {
      if (!SKIP.has(name)) scan(file);
    } else if ((EXTENSIONS.has(extname(name)) || NAMES.has(basename(name))) && name !== 'package-lock.json') {
      readFileSync(file, 'utf8').split('\n').forEach((line, index) => {
        if (DASHES.some((dash) => line.includes(dash))) found.push(`${relative(root, file)}:${index + 1}`);
      });
    }
  }
}

scan(root);
if (found.length > 0) {
  console.error(`Tiret cadratin ou demi-cadratin trouvé :\n${found.join('\n')}`);
  process.exit(1);
}
