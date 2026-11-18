function text(value, max) {
  return typeof value === 'string' && value.length >= 1 && value.length <= max;
}

function record(value, fields) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).length === Object.keys(fields).length &&
    Object.entries(fields).every(([key, check]) => Object.hasOwn(value, key) && check(value[key]));
}

const updateFields = {
  repository: value => text(value, 140) && /^[A-Za-z0-9][A-Za-z0-9-]*\/[A-Za-z0-9][A-Za-z0-9._-]*$/.test(value),
  assetName: value => text(value, 80) && /^[a-z][a-z0-9.-]*\.json$/.test(value) && value !== 'release.json',
};

const fields = {
  schemaVersion: value => value === 1,
  name: value => text(value, 80) && value.trim() === value &&
    !Array.from(value, char => char.codePointAt(0)).some(code => code < 32 || (code >= 127 && code <= 159)),
  applicationId: value => text(value, 180) && /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/.test(value) &&
    !['me.rerere.rikkahub', 'me.rerere.rikkahub.debug'].includes(value),
  deepLinkScheme: value => text(value, 80) && /^[a-z][a-z0-9+.-]*$/.test(value) &&
    !['rikkahub', 'http', 'https', 'file', 'content', 'android-app'].includes(value),
  versionCode: value => Number.isSafeInteger(value) && value > 0 && value <= 2_100_000_000,
  versionName: value => text(value, 96) && /^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)*$/.test(value),
  signingCertificateSha256: value => text(value, 64) && /^[a-f0-9]{64}$/.test(value),
  updates: value => record(value, updateFields),
};

/** Public, frozen distribution identity. Secrets belong to the independent signing environment. */
export function validateProduct(value) {
  if (!record(value, fields)) throw new Error('Invalid product distribution contract');
  return value;
}

/** Consume the already verified blob, so identity validation and assembly share exactly one input. */
export function productInputs(config, imports) {
  const mapped = imports.filter(entry => entry.to === 'vendor/rikka-plugin-host/distribution/product.json');
  if (config.product === undefined && mapped.length === 0) return {};
  if (config.product === undefined || mapped.length !== 1 || mapped[0].from !== config.product) {
    throw new Error('Product must be declared in the profile and mapped to its immutable source map location');
  }
  return { product: validateProduct(JSON.parse(mapped[0].bytes.toString('utf8'))), productDigest: mapped[0].sha256 };
}
