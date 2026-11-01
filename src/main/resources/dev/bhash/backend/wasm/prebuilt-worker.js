// SPDX-License-Identifier: Apache-2.0
import {runtimeBase, reportRunning} from './browser-host-config.js';
self.onmessage = async ({data}) => {
  self.onmessage = null;
  try {
    const response = await fetch(new URL('./BhashProgram.dll', import.meta.url));
    if (!response.ok) throw new Error('Unable to download the compiled program. Run again.');
    const bytes = new Uint8Array(await response.arrayBuffer());
    // Chunking avoids JavaScript's argument-count limit for larger programs.
    let binary = '';
    for (let i=0; i<bytes.length; i+=8192) binary += String.fromCharCode(...bytes.subarray(i,i+8192));
    const {dotnet} = await import(new URL('_framework/dotnet.js', new URL(runtimeBase, import.meta.url)).href);
    const {getAssemblyExports, getConfig} = await dotnet.create();
    const api = (await getAssemblyExports(getConfig().mainAssemblyName)).BhashBrowser;
    if (reportRunning) self.postMessage({type:'running'});
    const exitCode = api.Run(btoa(binary), data.input);
    self.postMessage({output:api.Output(), errors:api.Errors(), exitCode});
  } catch (error) { self.postMessage({output:'', errors:String(error), exitCode:1}); }
};

