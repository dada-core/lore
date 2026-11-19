import type { SampleFile } from './lore';

// The files the journeys import, made when they run: no binary file in the repository.

/** The runbook of the five minutes: the search of a pasted error opens it at section 4.2. */
export const RUNBOOK_TITLE = 'Restaurer une base PostgreSQL depuis une sauvegarde';

export function runbook(): SampleFile {
  const text = `# ${RUNBOOK_TITLE}

Procédure d'astreinte de DadaGroup. Avant de commencer, prévenez Eli Brou.

## 1. Préparer la restauration

1. Arrêtez le service \`lore\` : \`sudo systemctl stop lore\`.
2. Vérifiez la sauvegarde de la nuit dans \`/var/backups/postgresql\`.

## 2. Vider la base

\`\`\`sh
dropdb --if-exists lore && createdb --owner lore lore
\`\`\`

## 3. Restaurer

\`\`\`sh
pg_restore --clean --if-exists --no-owner --dbname lore /var/backups/postgresql/lore.dump
\`\`\`

## 4. Vérifier

### 4.1 Contrôles

Comptez les documents de chaque espace et comparez avec la veille.

### 4.2 Erreurs fréquentes

\`\`\`text
pg_restore: error: could not execute query: ERROR:  role "lore_app" does not exist
\`\`\`

Le rôle de l'application manque sur ce serveur : créez-le avec \`createuser lore_app\`, puis relancez la restauration.

## 5. Redémarrer

Démarrez le service, puis ouvrez Lore pour vérifier qu'une recherche répond.
`;
  return { name: 'restaurer-postgresql.md', mimeType: 'text/markdown', buffer: Buffer.from(text, 'utf8') };
}

/** A Markdown file with the given title and sections. */
export function markdown(name: string, title: string, sections: [string, string][]): SampleFile {
  const text = [`# ${title}`, '', ...sections.flatMap(([heading, body]) => [`## ${heading}`, '', body, ''])].join('\n');
  return { name, mimeType: 'text/markdown', buffer: Buffer.from(text, 'utf8') };
}

export function plainText(name: string, text: string): SampleFile {
  return { name, mimeType: 'text/plain', buffer: Buffer.from(text, 'utf8') };
}

/**
 * A PDF whose pages hold these lines of text, in Helvetica: enough for PDFBox to read them and for
 * pdf.js to show them. Latin-1 characters only (WinAnsi encoding).
 */
export function pdf(name: string, title: string, pages: string[][]): SampleFile {
  return { name, mimeType: 'application/pdf', buffer: writePdf(title, pages.map(textPage)) };
}

/** A PDF of images without any text, as a scanner makes: Lore marks it "Numérisé". */
export function scannedPdf(name: string, title: string, pageCount: number): SampleFile {
  const drawing = 'q 595 0 0 842 0 0 cm /Scan Do Q';
  return { name, mimeType: 'application/pdf', buffer: writePdf(title, Array.from({ length: pageCount }, () => drawing), true) };
}

function textPage(lines: string[]): string {
  const shown = lines.map((line) => `(${escape(line)}) Tj T*`).join(' ');
  return `BT /F1 12 Tf 16 TL 56 780 Td ${shown} ET`;
}

function escape(text: string): string {
  return text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
}

function writePdf(title: string, contents: string[], scan = false): Buffer {
  const objects: string[] = [];
  const pageIds = contents.map((_, index) => 4 + index * 2);
  objects[1] = '<< /Type /Catalog /Pages 2 0 R >>';
  objects[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${contents.length} >>`;
  objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>';
  // The image a scanner draws on each page: 8 by 8 grey pixels, enough for Lore to see an image.
  const image = 4 + contents.length * 2;
  const pixels = Array.from({ length: 64 }, (_, index) => String.fromCharCode(128 + ((index * 37) % 96))).join('');
  const resources = scan ? `<< /XObject << /Scan ${image} 0 R >> >>` : '<< /Font << /F1 3 0 R >> >>';
  contents.forEach((content, index) => {
    const id = pageIds[index];
    objects[id] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources ${resources} /Contents ${id + 1} 0 R >>`;
    objects[id + 1] = `<< /Length ${Buffer.byteLength(content, 'latin1')} >>\nstream\n${content}\nendstream`;
  });
  if (scan) {
    objects[image] = `<< /Type /XObject /Subtype /Image /Width 8 /Height 8 /ColorSpace /DeviceGray /BitsPerComponent 8 /Length 64 >>
stream
${pixels}
endstream`;
  }
  const info = objects.length;
  objects[info] = `<< /Title (${escape(title)}) /Producer (Lore tests) >>`;

  let body = '%PDF-1.4\n%\xe2\xe3\xcf\xd3\n';
  const offsets: number[] = [];
  for (let id = 1; id < objects.length; id++) {
    offsets[id] = Buffer.byteLength(body, 'latin1');
    body += `${id} 0 obj\n${objects[id]}\nendobj\n`;
  }
  const xref = Buffer.byteLength(body, 'latin1');
  body += `xref\n0 ${objects.length}\n0000000000 65535 f \n`;
  for (let id = 1; id < objects.length; id++) body += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`;
  body += `trailer\n<< /Size ${objects.length} /Root 1 0 R /Info ${info} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body, 'latin1');
}
