import { resolve } from 'node:path';
import { verifyRuntimeReports } from './runtime-reports.mjs';

if (!process.argv[2]) throw new Error('Usage: node tools/hostctl/verify-runtime-reports.mjs INVOCATION_DIRECTORY');
console.log(JSON.stringify(await verifyRuntimeReports(resolve(process.argv[2])), null, 2));
