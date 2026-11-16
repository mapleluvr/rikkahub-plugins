import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { digest, safePath } from './inputs.mjs';
import { parseJUnit, requireCases, requireDevices } from './reports.mjs';

const raw = [
  'callbacksSurviveRegistrationEvaluation',
  'concurrentRpcRepliesKeepTheirOwnIdentity',
  'cancellationInterruptsNativeLoopAndAllowsAnotherEvaluation',
  'rawCloseRequestsAsynchronousHostCleanup',
  'executionDeadlineBoundsBusyJavaScript',
  'newInstanceRequiresExplicitStateRestoration',
].map(name => `dev.rikkahub.plugins.runtime.QuickJsContractTest#${name}`);
const owned = [
  'closingWaitsForHostCleanup',
  'callerCancellationRevokesInstanceAndDrainsHostTasks',
  'keepsRegisteredCallbacksAcrossSuccessfulCalls',
  'nestedSameInstanceCallIsRejectedWithoutRevokingTheCaller',
  'acyclicCrossInstanceCallCompletes',
].map(name => `dev.rikkahub.plugins.runtime.PluginRuntimeTest#${name}`);

/** Read a sealed snapshot from run-runtime-verification, never merge a reused build directory. */
export async function verifyRuntimeReports(directory) {
  const receipt = JSON.parse(await readFile(join(directory, 'invocation.json'), 'utf8'));
  if (receipt.schemaVersion !== 1 || receipt.exitCode !== 0 || receipt.instrumentationExitCode !== 0 ||
      !receipt.completedAt || !receipt.startedAt ||
      receipt.completedAt < receipt.startedAt || !Array.isArray(receipt.reports)) {
    throw new Error('Runtime invocation did not complete successfully');
  }
  const paths = new Set();
  const jvm = [];
  const android = [];
  for (const report of receipt.reports) {
    safePath(report.path);
    if (paths.has(report.path)) throw new Error('Duplicate report path');
    paths.add(report.path);
    const bytes = await readFile(join(directory, report.path));
    if (digest(bytes) !== report.sha256) throw new Error('Invocation report digest mismatch');
    if (report.kind === 'jvm') jvm.push(...parseJUnit(bytes.toString('utf8')));
    else if (report.kind === 'android') android.push({ device: report.device, xml: bytes.toString('utf8') });
    else throw new Error('Unknown report kind');
  }
  return {
    invocationId: receipt.id,
    jvm: requireCases(jvm, [...raw, ...owned]),
    android: requireDevices(android, [
      ...raw, ...owned,
      'dev.rikkahub.plugins.runtime.RuntimeLifecycleTest#activityRecreationKeepsServiceStateAndProcessDeathRequiresReconstruction',
    ]),
  };
}
