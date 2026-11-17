import { realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
export function isMain(url) {
  return !!process.argv[1] && url === pathToFileURL(realpathSync(process.argv[1])).href;
}
