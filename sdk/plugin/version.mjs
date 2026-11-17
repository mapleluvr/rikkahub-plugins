const pattern = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
export function parseVersion(version) {
  const match = typeof version === 'string' && version.length <= 80 && pattern.exec(version);
  if (!match || match[4]?.split('.').some(p => /^0[0-9]+$/.test(p))) throw new Error('Invalid plugin version');
  return { prerelease: !!match[4] };
}
