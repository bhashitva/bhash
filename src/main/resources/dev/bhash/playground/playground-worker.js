// SPDX-License-Identifier: Apache-2.0
import {dotnet} from './_framework/dotnet.js';
self.onmessage = async ({data}) => {
  try {
    const {getAssemblyExports, getConfig} = await dotnet.create();
    const api = (await getAssemblyExports(getConfig().mainAssemblyName)).BhashBrowser;
    self.postMessage({type: 'running'});
    const exitCode = api.Run(data.input);
    self.postMessage({output: api.Output(), errors: api.Errors(), exitCode});
  } catch (error) { self.postMessage({output: '', errors: String(error), exitCode: 1}); }
};
