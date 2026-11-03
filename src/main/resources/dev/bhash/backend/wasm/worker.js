// SPDX-License-Identifier: Apache-2.0
import { dotnet } from './_framework/dotnet.js';

self.onmessage = async ({ data }) => {
    self.onmessage = null;
    try {
        const { getAssemblyExports, getConfig } = await dotnet.create();
        const exports = await getAssemblyExports(getConfig().mainAssemblyName);
        self.onmessage = ({data: message}) => { if(message?.type === 'cancel') exports.BhashBrowser.Cancel(); };
        self.postMessage({type:'running'});
        const exitCode = await exports.BhashBrowser.Run(data.input);
        self.onmessage = null;
        self.postMessage({ output: exports.BhashBrowser.Output(), errors: exports.BhashBrowser.Errors(), exitCode });
    } catch (error) {
        self.postMessage({ output: '', errors: String(error), exitCode: 1 });
    }
};
