#!/usr/bin/env node
import { build } from 'esbuild';
import { zipSync } from 'fflate';
import { createHash } from 'node:crypto';
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const MAX_EXPANDED = 8 * 1024 * 1024;
const MAX_ARCHIVE = 4 * 1024 * 1024;

function validPath(path) {
  return typeof path === 'string' && path.length <= 200 && /^[A-Za-z0-9_./-]+$/.test(path)
    && path.split('/').every(p => p && p !== '.' && p !== '..' && !p.endsWith('.'));
}

async function readResource(root, name) {
  if (!validPath(name)) throw new Error(`Invalid resource path: ${name}`);
  const file = await realpath(resolve(root, name));
  const within = relative(root, file);
  if (isAbsolute(within) || within === '..' || within.startsWith(`..${sep}`)) {
    throw new Error(`Resource escapes plugin directory: ${name}`);
  }
  return readFile(file);
}

/** Reproducible archive; the host remains authoritative for permissions and wire compatibility. */
export async function buildPackage(directory, source = 'src/index.ts') {
  const root = await realpath(directory);
  const manifestBytes = await readResource(root, 'manifest.json');
  if (manifestBytes.length > 64 * 1024) throw new Error('Manifest exceeds size limit');
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  if (!/^[a-z][a-z0-9]*(\.[a-z][a-z0-9_-]*)+$/.test(manifest.id ?? '')) throw new Error('Invalid plugin ID');
  if (!/^\d+\.\d+\.\d+(?:-[\w.-]+)?(?:\+[\w.-]+)?$/.test(manifest.version ?? '')) throw new Error('Invalid version');
  const entry = manifest.entry ?? 'main.js';
  const resources = manifest.resources ?? [];
  const names = ['manifest.json', entry, ...resources];
  if (names.length > 128 || names.some(name => !validPath(name))
    || new Set(names.map(name => name.toLowerCase())).size !== names.length) throw new Error('Invalid or duplicate package paths');
  if (!entry.endsWith('.js')) throw new Error('Entry must be JavaScript');
  await readResource(root, source);
  const output = await build({
    absWorkingDir: root,
    entryPoints: [source],
    bundle: true,
    write: false,
    format: 'iife',
    platform: 'neutral',
    target: 'es2020',
    charset: 'utf8',
    sourcemap: false,
    logLevel: 'silent',
  });
  if (output.outputFiles.length !== 1 || output.outputFiles[0].contents.length > 768 * 1024) {
    throw new Error('Plugin must bundle into one bounded JavaScript file');
  }
  const files = new Map([
    ['manifest.json', Buffer.from(JSON.stringify(manifest))],
    [entry, output.outputFiles[0].contents],
  ]);
  for (const name of resources) files.set(name, await readResource(root, name));
  if ([...files.values()].reduce((size, bytes) => size + bytes.length, 0) > MAX_EXPANDED) {
    throw new Error('Expanded package exceeds size limit');
  }
  const archive = zipSync(Object.fromEntries([...files].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)), {
    level: 6,
    mtime: new Date(1980, 0, 1),
  });
  if (archive.length > MAX_ARCHIVE) throw new Error('Archive exceeds size limit');
  return { manifest, archive, sha256: createHash('sha256').update(archive).digest('hex') };
}

async function main(args) {
  const [command, directory, destination, source] = args;
  if (command !== 'build' || !directory || !destination || args.length > 4) {
    throw new Error('Usage: rikkaplug build <plugin-directory> <output.rikkaplug> [src/index.ts]');
  }
  const result = await buildPackage(directory, source);
  const output = resolve(destination);
  await mkdir(dirname(output), { recursive: true });
  await writeFile(output, result.archive);
  await writeFile(`${output}.sha256`, `${result.sha256}  ${basename(output)}\n`);
  console.log(`${result.manifest.id}@${result.manifest.version} ${result.sha256}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
