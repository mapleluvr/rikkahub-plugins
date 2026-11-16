import { readFile, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { parseJUnit, requireCases } from './reports.mjs';

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
].map(name => `dev.rikkahub.plugins.runtime.PluginRuntimeTest#${name}`);

async function reports(path) {
  const cases = [];
  for (const entry of await readdir(path, { withFileTypes: true })) {
    if (entry.isDirectory()) cases.push(...await reports(join(path, entry.name)));
    else if (/^TEST-.*\.xml$/.test(entry.name)) cases.push(...parseJUnit(await readFile(join(path, entry.name), 'utf8')));
  }
  return cases;
}

const workspace = process.argv[2];
if (!workspace) throw new Error('Usage: node tools/hostctl/verify-runtime-reports.mjs WORKSPACE');
const build = join(resolve(workspace), 'vendor/rikka-plugin-host/platform/runtime-quickjs/build');
const jvm = requireCases(await reports(join(build, 'test-results/testDebugUnitTest')), [...raw, ...owned]);
const android = requireCases(await reports(join(build, 'outputs/androidTest-results/connected/debug')), [
  ...raw, ...owned,
  'dev.rikkahub.plugins.runtime.RuntimeLifecycleTest#activityRecreationKeepsServiceStateAndProcessDeathRequiresReconstruction',
]);
console.log(JSON.stringify({ jvm, android }, null, 2));
