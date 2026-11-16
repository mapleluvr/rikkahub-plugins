import { readFile, readdir, stat } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { digest } from './inputs.mjs';

/** Enumerate only the report roots of one task invocation, never unrelated Gradle output. */
export async function collectReportFiles(root, base = root) {
  const files = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) files.push(...await collectReportFiles(path, base));
    else if (/^TEST-.*\.xml$/.test(entry.name)) files.push({
      path: relative(base, path).replaceAll('\\', '/'),
      sha256: digest(await readFile(path)),
      modifiedAt: (await stat(path)).mtimeMs,
    });
  }
  return files;
}
