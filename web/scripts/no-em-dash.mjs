// Fails when a source file of the interface contains an em dash (U+2014): the project never uses
// it, in code, comments or interface text. ESLint checks the scripts; this covers the rest.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const EXTENSIONS = new Set(['.css', '.html', '.js', '.mjs', '.ts', '.tsx', '.json', '.svg', '.md']);
const SKIP = new Set(['node_modules', 'dist', 'fonts']);
const EM_DASH = String.fromCharCode(0x2014);

const found = [];
function scan(path) {
  for (const name of readdirSync(path)) {
    const file = join(path, name);
    if (statSync(file).isDirectory()) {
      if (!SKIP.has(name)) scan(file);
    } else if (EXTENSIONS.has(extname(name)) && name !== 'package-lock.json') {
      readFileSync(file, 'utf8').split('\n').forEach((line, index) => {
        if (line.includes(EM_DASH)) found.push(`${relative(web, file)}:${index + 1}`);
      });
    }
  }
}

scan(web);
if (found.length > 0) {
  console.error(`Tiret cadratin trouvé :\n${found.join('\n')}`);
  process.exit(1);
}
