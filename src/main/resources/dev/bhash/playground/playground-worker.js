// SPDX-License-Identifier: Apache-2.0
import {dotnet} from './_framework/dotnet.js';
self.onmessage = async ({data}) => {
  self.onmessage = null;
  try {
    const {getAssemblyExports, getConfig} = await dotnet.create();
    const api = (await getAssemblyExports(getConfig().mainAssemblyName)).BhashBrowser;
    self.onmessage = ({data: message}) => { if(message?.type === 'cancel') api.Cancel(); };
    self.postMessage({type: 'running'});
    const exitCode = await api.Run(data.input);
    self.onmessage = null;
    self.postMessage({output: api.Output(), errors: api.Errors(), exitCode});
  } catch (error) { self.postMessage({output: '', errors: String(error), exitCode: 1}); }
};
