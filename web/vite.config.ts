import { readdirSync, readFileSync, statSync } from 'node:fs';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';
import { configDefaults } from 'vitest/config';

const PDFJS = fileURLToPath(new URL('./node_modules/pdfjs-dist/', import.meta.url));

/**
 * The files pdf.js asks for while it reads a PDF, served by Lore under /pdfjs/: the standard fonts
 * of PDF, the character maps of Asian fonts, and the image decoders in JavaScript (WebAssembly is
 * not allowed by the policy of the interface). With their licences. They are loaded only when a
 * PDF needs them.
 */
function pdfjsAssets(): Plugin {
  const files = (): [string, string][] => {
    const list: [string, string][] = [['LICENSE', 'LICENSE']];
    for (const folder of ['standard_fonts', 'cmaps']) {
      for (const name of readdirSync(join(PDFJS, folder))) list.push([`${folder}/${name}`, `${folder}/${name}`]);
    }
    for (const name of ['openjpeg_nowasm_fallback.js', 'jbig2_nowasm_fallback.js', 'LICENSE_OPENJPEG',
      'LICENSE_PDFJS_OPENJPEG', 'LICENSE_JBIG2', 'LICENSE_PDFJS_JBIG2']) {
      list.push([`wasm/${name}`, `wasm/${name}`]);
    }
    return list;
  };
  return {
    name: 'lore-pdfjs-assets',
    configureServer(server) {
      server.middlewares.use('/pdfjs', (request, response, next) => {
        const path = decodeURIComponent((request.url ?? '').split('?')[0]).replace(/^\/+/, '');
        const found = files().find(([published]) => published === path);
        if (!found || !statSync(join(PDFJS, found[1]), { throwIfNoEntry: false })) {
          next();
          return;
        }
        response.setHeader('Content-Type', extname(path) === '.js' ? 'text/javascript' : 'application/octet-stream');
        response.end(readFileSync(join(PDFJS, found[1])));
      });
    },
    generateBundle() {
      for (const [published, source] of files()) {
        this.emitFile({ type: 'asset', fileName: `pdfjs/${published}`, source: readFileSync(join(PDFJS, source)) });
      }
    },
  };
}

// In development, the API runs on http://localhost:8080 (./mvnw spring-boot:run) and Vite forwards
// /api to it: the interface and the API share one origin, as in production.
export default defineConfig({
  plugins: [react(), pdfjsAssets()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Above the 5 seconds of a single wait (src/test/setup.ts): a failure names what is missing.
    testTimeout: 15000,
    // The journeys in real browsers run with Playwright (npm run e2e).
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
});
