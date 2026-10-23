// SPDX-License-Identifier: Apache-2.0
import { dotnet } from './_framework/dotnet.js';

self.onmessage = async ({ data }) => {
    try {
        const { getAssemblyExports, getConfig } = await dotnet.create();
        const exports = await getAssemblyExports(getConfig().mainAssemblyName);
        const exitCode = exports.BhashBrowser.Run(data.input);
        self.postMessage({ output: exports.BhashBrowser.Output(), errors: exports.BhashBrowser.Errors(), exitCode });
    } catch (error) {
        self.postMessage({ output: '', errors: String(error), exitCode: 1 });
    }
};
