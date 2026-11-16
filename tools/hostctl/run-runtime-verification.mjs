import { open, mkdir, readFile, writeFile, rm, lstat } from 'node:fs/promises';
import { join, resolve, dirname } from 'node:path';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { git } from './inputs.mjs';
import { collectReportFiles } from './report-files.mjs';
import { verifyRuntimeReports } from './runtime-reports.mjs';

const workspace = resolve(process.argv[2] ?? '');
if (!process.argv[2]) throw new Error('Usage: node tools/hostctl/run-runtime-verification.mjs WORKSPACE');
const lockPath = join(workspace, '.git/runtime-verification.lock');
const lock = await open(lockPath, 'wx');
try {
  const assembly = JSON.parse(await readFile(join(workspace, '.git/hostctl.json'), 'utf8'));
  const id = randomUUID();
  const output = join(workspace, '.git/runtime-verification', id);
  await mkdir(output, { recursive: true });
  const build = 'vendor/rikka-plugin-host/platform/runtime-quickjs/build';
  const roots = { jvm: `${build}/test-results/testDebugUnitTest`, android: `${build}/outputs/androidTest-results/connected/debug` };
  // Refuse path links and clear only task-owned reports while holding the invocation lock.
  for (const relative of Object.values(roots)) {
    let path = workspace;
    for (const part of relative.split('/')) {
      path = join(path, part);
      try {
        if ((await lstat(path)).isSymbolicLink()) throw new Error('Report path must not contain links');
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    await rm(join(workspace, relative), { recursive: true, force: true });
  }
  const receipt = { schemaVersion: 1, id, maintenanceCommit: assembly.maintenanceCommit,
    sourceTree: git(workspace, ['rev-parse', 'HEAD^{tree}']), startedAt: Date.now(), reports: [] };
  await writeFile(join(output, 'invocation.json'), JSON.stringify(receipt, null, 2));
  const args = [':plugin-runtime-quickjs:testDebugUnitTest', ':plugin-runtime-quickjs:connectedDebugAndroidTest', '--rerun-tasks', '--console=plain'];
  receipt.exitCode = await new Promise((accept, reject) => {
    const child = process.platform === 'win32'
      ? spawn('cmd.exe', ['/d', '/c', 'gradlew.bat', ...args], { cwd: workspace, stdio: 'inherit' })
      : spawn('./gradlew', args, { cwd: workspace, stdio: 'inherit' });
    child.once('error', reject);
    child.once('exit', code => accept(code ?? -1));
  });
  receipt.completedAt = Date.now();
  for (const [kind, relative] of Object.entries(roots)) {
    const root = join(workspace, relative);
    let files;
    try { files = await collectReportFiles(root); } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      files = [];
    }
    for (const file of files) {
      const path = `${kind}/${file.path}`;
      const target = join(output, path);
      await mkdir(dirname(target), { recursive: true });
      await writeFile(target, await readFile(join(root, file.path)));
      receipt.reports.push({ kind, path, sha256: file.sha256, device: kind === 'android' ? file.path : undefined });
    }
  }
  // AGP may return task success while instrumentation failed; preserve its explicit result too.
  try {
    receipt.instrumentationExitCode = Number((await readFile(join(workspace, roots.android, 'test-result-exit-code.txt'), 'utf8')).trim());
  } catch { receipt.instrumentationExitCode = null; }
  await writeFile(join(output, 'invocation.json'), JSON.stringify(receipt, null, 2) + '\n');
  console.log(`Runtime invocation evidence: ${output}`);
  console.log(JSON.stringify(await verifyRuntimeReports(output), null, 2));
} finally {
  await lock.close();
  await rm(lockPath);
}
