// The only bridge between the web page and the desktop app. Each function is a
// narrow, named action; the page never gets Node, the filesystem or the token.

const { contextBridge, ipcRenderer } = require('electron');

const invoke = (channel, ...args) => ipcRenderer.invoke(channel, ...args);

contextBridge.exposeInMainWorld('debridone', {
  isDesktop: true,
  platform: process.platform,
  rd: {
    status: () => invoke('rd:status'),
    connect: (token) => invoke('rd:connect', token),
    disconnect: () => invoke('rd:disconnect'),
    request: (method, path, params) => invoke('rd:request', { method, path, params }),
  },
  net: {
    fetchText: (options) => invoke('net:fetchText', options),
  },
  downloads: {
    start: (options) => invoke('dl:start', options),
    cancel: (id) => invoke('dl:cancel', id),
    reveal: (filePath) => invoke('dl:reveal', filePath),
    onEvent: (callback) => {
      const listener = (_event, message) => callback(message);
      ipcRenderer.on('dl:event', listener);
      return () => ipcRenderer.removeListener('dl:event', listener);
    },
  },
  storage: {
    loadAll: () => invoke('store:loadAll'),
    set: (key, value) => invoke('store:set', { key, value }),
    remove: (key) => invoke('store:remove', key),
  },
  openExternal: (url) => invoke('app:openExternal', url),
});
