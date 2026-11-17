// pdf.js, loaded only when a PDF is opened: its code, its worker and its stylesheet are not in
// the main bundle of the interface. Everything is served by Lore, no CDN (decision 2 of the reader).

import 'pdfjs-dist/web/pdf_viewer.css';
import * as pdfjs from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

export type PdfJs = typeof pdfjs;
export type PdfViewerModule = typeof import('pdfjs-dist/web/pdf_viewer.mjs');

/** Where the files pdf.js may ask for are served: fonts, character maps, image decoders. */
export const ASSETS = '/pdfjs/';

let loaded: Promise<{ pdfjs: PdfJs; viewer: PdfViewerModule }> | null = null;

/** The library and its viewer; the viewer reads the library from the global object. */
export function loadPdf(): Promise<{ pdfjs: PdfJs; viewer: PdfViewerModule }> {
  loaded ??= (async () => {
    (globalThis as { pdfjsLib?: PdfJs }).pdfjsLib = pdfjs;
    pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;
    const viewer = await import('pdfjs-dist/web/pdf_viewer.mjs');
    return { pdfjs, viewer };
  })();
  return loaded;
}
