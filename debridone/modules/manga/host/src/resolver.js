'use strict';
/*
 * Works out which packages an extension package needs, without installing anything.
 *
 * aki-plugin-manager's own installer fetches `<name>/<range with its first character removed>`,
 * which only works for simple ranges and checks no integrity. This resolver follows npm's rules
 * instead (highest version that satisfies each range) and returns, for every package, the tarball
 * address and the registry's SHA-512, so the app can verify each download before unpacking it.
 *
 * Layout: the same shape aki produces — the package itself at the root and each dependency under
 * `node_modules/<name>` of the package that needs it — except that a dependency already provided by
 * a folder further up (which Node finds anyway) is not installed twice.
 */

const semver = require('semver');

const DEFAULT_REGISTRY = 'https://registry.npmjs.org';
const MAX_PACKAGES = 400;

class ResolveError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

function packagePath(name) {
  return name.startsWith('@') ? name.replace('/', '%2F') : name;
}

function createRegistry(base) {
  const cache = new Map();
  return async function packument(name) {
    if (!cache.has(name)) {
      cache.set(
        name,
        (async () => {
          const response = await fetch(`${base}/${packagePath(name)}`, {
            headers: { Accept: 'application/vnd.npm.install-v1+json' },
            signal: AbortSignal.timeout(30000),
          });
          if (response.status === 404) throw new ResolveError('NOT_FOUND', `${name} is not in the registry.`);
          if (!response.ok) throw new ResolveError('REGISTRY', `The registry answered ${response.status} for ${name}.`);
          return response.json();
        })(),
      );
    }
    return cache.get(name);
  };
}

function pickVersion(name, range, doc) {
  const versions = Object.keys(doc.versions || {});
  const tags = doc['dist-tags'] || {};
  if (Object.prototype.hasOwnProperty.call(tags, range)) return tags[range];
  if (versions.includes(range)) return range;
  const valid = semver.validRange(range);
  if (!valid) throw new ResolveError('UNSUPPORTED_RANGE', `${name}: "${range}" is not a version or range this installer accepts.`);
  // Prefer what `latest` points at when it fits, as npm does.
  if (tags.latest && semver.satisfies(tags.latest, valid)) return tags.latest;
  const best = semver.maxSatisfying(versions, valid);
  if (!best) throw new ResolveError('NO_VERSION', `${name}: no published version satisfies "${range}".`);
  return best;
}

async function resolveInstallPlan({ name, version, registry }) {
  const base = String(registry || DEFAULT_REGISTRY).replace(/\/+$/, '');
  const packument = createRegistry(base);
  const plan = [];

  async function manifest(pkgName, range) {
    const doc = await packument(pkgName);
    const chosen = pickVersion(pkgName, range, doc);
    const entry = doc.versions[chosen];
    if (!entry || !entry.dist || !entry.dist.tarball) {
      throw new ResolveError('REGISTRY', `${pkgName}@${chosen} has no downloadable archive.`);
    }
    return entry;
  }

  /**
   * @param entry     registry manifest of the package being placed
   * @param dir       its folder relative to the install root ('' for the root package)
   * @param visible   name -> version maps of every node_modules folder Node would search from here,
   *                  nearest first
   */
  async function place(entry, dir, visible) {
    const wanted = Object.entries(entry.dependencies || {});
    const own = new Map();
    const children = [];
    for (const [depName, range] of wanted) {
      const provided = visible.find((level) => level.has(depName));
      if (provided) {
        const have = provided.get(depName);
        const valid = semver.validRange(range);
        if (have === range || (valid && semver.satisfies(have, valid))) continue;
      }
      const depEntry = await manifest(depName, range);
      own.set(depName, depEntry.version);
      children.push(depEntry);
    }
    for (const depEntry of children) {
      const depDir = `${dir ? `${dir}/` : ''}node_modules/${depEntry.name}`;
      plan.push({
        path: depDir,
        name: depEntry.name,
        version: depEntry.version,
        tarball: depEntry.dist.tarball,
        integrity: depEntry.dist.integrity || null,
        shasum: depEntry.dist.shasum || null,
      });
      if (plan.length > MAX_PACKAGES) {
        throw new ResolveError('TOO_MANY', `More than ${MAX_PACKAGES} packages would be installed.`);
      }
    }
    for (const depEntry of children) {
      const depDir = `${dir ? `${dir}/` : ''}node_modules/${depEntry.name}`;
      await place(depEntry, depDir, [own, ...visible]);
    }
  }

  const root = await manifest(name, version || 'latest');
  plan.push({
    path: '',
    name: root.name,
    version: root.version,
    tarball: root.dist.tarball,
    integrity: root.dist.integrity || null,
    shasum: root.dist.shasum || null,
  });
  await place(root, '', []);
  return {
    name: root.name,
    version: root.version,
    registry: base,
    license: root.license || null,
    packages: plan,
  };
}

module.exports = { resolveInstallPlan };
