'use strict';
/*
 * Adapter for Houdoku's older, pre-Tiyo extension format.
 *
 * Before Tiyo, every source was its own npm package (`@houdoku/extension-<name>`, see
 * https://github.com/xgi/houdoku-extensions). Such a package exports METADATA and an
 * ExtensionClient class whose constructor takes one `UtilFunctions(fetchFn, webviewFn, docFn)`
 * object from `houdoku-extension-lib` (1.9 line). Current Houdoku no longer loads these packages;
 * this adapter gives them the three functions they expect and presents them with the same
 * operations a Tiyo source has.
 *
 * `docFn` needs an HTML parser. Old Houdoku passed the renderer's DOMParser; here the jsdom copy
 * inside the installed Tiyo package is used, so a legacy source that parses web pages only works
 * while Tiyo is installed. Sources that talk to a JSON API work on their own.
 */

const fs = require('fs');
const path = require('path');

class LegacyError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

function loadLegacyExtension({ aki, pluginsDir, packageName, requireFn, tiyoDir }) {
  const packageDir = path.join(pluginsDir, packageName);
  const manifest = JSON.parse(fs.readFileSync(path.join(packageDir, 'package.json'), 'utf8'));
  const libRange = (manifest.dependencies || {})['houdoku-extension-lib'];
  if (!libRange) {
    throw new LegacyError('INCOMPATIBLE', `${packageName} does not use houdoku-extension-lib, so it is not a Houdoku extension.`);
  }
  let lib;
  try {
    lib = requireFn(path.join(packageDir, 'node_modules', 'houdoku-extension-lib'));
  } catch (err) {
    throw new LegacyError('INCOMPATIBLE', `${packageName}: its extension library could not be loaded (${err.message}).`);
  }
  if (typeof lib.UtilFunctions !== 'function') {
    throw new LegacyError(
      'INCOMPATIBLE',
      `${packageName} was built for an extension interface (houdoku-extension-lib ${libRange}) that this adapter does not support.`,
    );
  }
  let jsdom = null;
  const parser = () => {
    if (!jsdom) {
      try {
        jsdom = requireFn(path.join(tiyoDir, 'node_modules', 'jsdom'));
      } catch (_err) {
        throw new LegacyError('NEEDS_TIYO', `${packageName} reads web pages and needs the Tiyo package to be installed (it supplies the HTML parser).`);
      }
    }
    return jsdom;
  };

  const fetchFn = async (url, init) => {
    const response = await fetch(url, init);
    // node-fetch 2 responses have buffer(); the built-in fetch does not.
    if (typeof response.buffer !== 'function') {
      response.buffer = async () => Buffer.from(await response.arrayBuffer());
    }
    return response;
  };
  const webviewFn = () =>
    Promise.reject(new LegacyError('BROWSER_CHECK', 'This source needs a browser window, which DebridOne does not provide for legacy extensions.'));
  const docFn = (html) => new (parser().JSDOM)(html === undefined ? '' : html).window.document;

  const mod = aki.load(pluginsDir, packageName, requireFn);
  if (!mod || typeof mod.ExtensionClient !== 'function') {
    throw new LegacyError('INCOMPATIBLE', `${packageName} does not export an ExtensionClient.`);
  }
  const client = new mod.ExtensionClient(new lib.UtilFunctions(fetchFn, webviewFn, docFn));
  const metadata = typeof client.getMetadata === 'function' ? client.getMetadata() : mod.METADATA;
  if (!metadata || !metadata.id || !metadata.name) {
    throw new LegacyError('INCOMPATIBLE', `${packageName} does not describe its source.`);
  }
  return { metadata, client };
}

module.exports = { loadLegacyExtension };
