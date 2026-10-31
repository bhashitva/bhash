// SPDX-License-Identifier: Apache-2.0
const vscode=require('vscode');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');

exports.run=async()=>{
    const workspace=process.env.BHASH_DEBUG_WORKSPACE;
    const source=path.join(workspace,'debug-inspection.bhash');
    const settingsBefore=process.platform==='win32'?await fs.readFile(process.env.BHASH_DEBUG_SETTINGS,'utf8'):undefined;
    const debugTerminals=[];
    const terminalListener=vscode.window.onDidOpenTerminal(terminal=>{if(terminal.name==='Bhash: Native Debug')debugTerminals.push(terminal);});
    const lines=(await fs.readFile(source,'utf8')).split('\n');
    const marker=name=>lines.findIndex(line=>line.includes(name));
    const uri=vscode.Uri.file(source);
    const editor=await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri));
    const extension=vscode.extensions.getExtension('bhashitva.bhash');assert.ok(extension);await extension.activate();
    assert.ok(vscode.extensions.getExtension('ms-vscode.cpptools'),'real Microsoft GDB adapter installed');
    // Debugging must coexist with editor assistance when the packaged JAR is in a Hindi path.
    const languageReady=async()=>{
        const deadline=Date.now()+15000;
        while(Date.now()<deadline){
            const symbols=await vscode.commands.executeCommand('vscode.executeDocumentSymbolProvider',uri);
            if(symbols?.some(symbol=>symbol.name==='मुख्य'))return;
            await new Promise(resolve=>setTimeout(resolve,100));
        }
        throw Error('Bhash language server did not provide the source outline from the packaged Unicode path');
    };
    let languageTimer;
    try {await Promise.race([languageReady(),new Promise((_,reject)=>{languageTimer=setTimeout(()=>reject(Error('Bhash language server startup timed out')),18000);})]);}
    finally {clearTimeout(languageTimer);}

    const events=[],transcript=[],outputs=[];let wake;
    const tracker=vscode.debug.registerDebugAdapterTrackerFactory('*',{createDebugAdapterTracker:()=>({
        onDidSendMessage(message){
            transcript.push(message);
            if(message.type==='event'&&message.event==='stopped'){events.push(message.body);if(wake){wake();wake=undefined;}}
            if(message.type==='event'&&message.event==='output')outputs.push(message.body.output);
        }
    })});
    const stopped=async()=>{
        if(!events.length) await new Promise((resolve,reject)=>{
            const timer=setTimeout(()=>{wake=undefined;reject(Error('Timed out waiting for debugger stop'));},60000);
            wake=()=>{clearTimeout(timer);resolve();};
        });
        return events.shift();
    };
    let session,buildDirectory;
    // A stopped event can precede cppdbg's refreshed stack cache (GDB 12).
    // Request explicit paging and require a usable stack within a bounded time.
    const stackAt=async(stop)=>{
        const deadline=Date.now()+5000;let stack;
        do {
            stack=await session.customRequest('stackTrace',{threadId:stop.threadId,startFrame:0,levels:50});
            if(stack.stackFrames?.length)return stack;
            await new Promise(resolve=>setTimeout(resolve,50));
        } while(Date.now()<deadline);
        assert.fail('No Bhash stack after stop: '+JSON.stringify({stop,stack,events,recent:transcript.slice(-15)}));
    };
    assert.equal(editor.document.languageId,'bhash');
    assert.equal(vscode.workspace.getConfiguration('debug',uri).get('allowBreakpointsEverywhere'),false);
    const points=[];
    // Exercise the same language permission gate as gutter clicks and F9.
    for(const name of ['DEBUG_BEFORE','DEBUG_AFTER']){
        editor.selection=new vscode.Selection(marker(name),0,marker(name),0);
        await vscode.commands.executeCommand('editor.debug.action.toggleBreakpoint');
        const point=vscode.debug.breakpoints.find(b=>b instanceof vscode.SourceBreakpoint&&b.location.uri.toString()===uri.toString()&&b.location.range.start.line===marker(name));
        assert.ok(point,'Editor breakpoint creation must work for '+name);
        points.push(point);
    }
    try {
        const start=vscode.debug.onDidStartDebugSession(value=>{session=value;buildDirectory=value.configuration.bhashBuildDirectory;});
        assert.ok(await vscode.debug.startDebugging(vscode.workspace.workspaceFolders[0],{
            type:'cppdbg',request:'launch',name:'Bhash real GDB test',bhashNative:true,bhashInput:source,cwd:workspace,
            environment:[{name:'BHASH_GC_STRESS',value:'1'},{name:'BHASH_GC_STATS',value:'1'}],stopAtEntry:false,args:[]}));
        start.dispose();
        const first=await stopped();assert.ok(session);
        if(process.platform==='win32'){
            assert.equal(session.type,'bhash-native-windows','legacy Bhash cppdbg config must route to native terminal bridge');
            assert.equal(debugTerminals.length,1);
            assert.match(debugTerminals[0].creationOptions.shellPath,/WindowsDebugLauncher\.exe$/i);
            assert.ok(!transcript.some(m=>m.command==='runInTerminal'),'bridge must handle terminal launch itself');
        }
        const stack=await stackAt(first);
        assert.match(stack.stackFrames[0].name,/^मुख्य(?:\(.*\))?$/);assert.equal(path.normalize(stack.stackFrames[0].source.path).toLowerCase(),path.normalize(source).toLowerCase());
        assert.equal(stack.stackFrames[0].line,marker('DEBUG_BEFORE')+1);
        const inspect=async(frame)=>{
            const scopes=await session.customRequest('scopes',{frameId:frame.id});
            const variables=[];
            for(const scope of scopes.scopes.filter(s=>!s.expensive))variables.push(...(await session.customRequest('variables',{variablesReference:scope.variablesReference})).variables);
            const find=name=>{const found=variables.find(v=>v.name===name);assert.ok(found,JSON.stringify(variables));return found;};
            assert.match(find('पाठ्य').value,/भारत😀/);assert.match(find('पाठ्य').value,/\\u0000/);
            assert.match(find('रिक्तमान').value,/रिक्त/);
            const child=async name=>(await session.customRequest('variables',{variablesReference:find(name).variablesReference})).variables;
            const object=await child('वस्तु');assert.ok(object.some(v=>v.name==='अंक'&&v.value==='42'),JSON.stringify(object));
            assert.ok(object.some(v=>v.name==='विरासत'&&v.value==='9'),JSON.stringify(object));
            assert.ok(object.some(v=>v.name==='नाम'&&v.value.includes('भारत😀')));
            const list=await child('अंक');assert.ok(list.some(v=>v.value==='7')&&list.some(v=>v.value==='42'),JSON.stringify(list));
            const map=await child('शब्द');assert.ok(map.some(v=>v.value.includes('साझा')),JSON.stringify(map));
            assert.ok((await child('डिब्बा')).some(v=>v.value==='42'));
            assert.ok((await child('सरणी')).some(v=>v.value==='11'));
            assert.ok((await child('बफ़र')).some(v=>v.value==='65'));
            assert.ok((await child('अभिलेखमान')).some(v=>v.name==='व'&&v.value==='55'));
        };
        await inspect(stack.stackFrames[0]);
        const initialCollections=Number((await session.customRequest('evaluate',{expression:'collections',frameId:stack.stackFrames[0].id,context:'watch'})).result);
        assert.ok(Number.isFinite(initialCollections));
        await session.customRequest('stepIn',{threadId:first.threadId});const into=await stopped();
        const inner=await stackAt(into);
        assert.match(inner.stackFrames[0].name,/दुगुना/);assert.ok(inner.stackFrames.some(f=>/^मुख्य(?:\(.*\))?$/.test(f.name)));
        const parameterScopes=await session.customRequest('scopes',{frameId:inner.stackFrames[0].id});
        const parameters=[];for(const scope of parameterScopes.scopes.filter(s=>!s.expensive))parameters.push(...(await session.customRequest('variables',{variablesReference:scope.variablesReference})).variables);
        assert.ok(parameters.some(v=>v.name==='मूल'&&v.value==='21'),'arguments must be valid at the first source stop');
        await session.customRequest('next',{threadId:into.threadId});const next=await stopped();
        const advanced=await stackAt(next);assert.match(advanced.stackFrames[0].name,/दुगुना/);
        await session.customRequest('stepOut',{threadId:next.threadId});const out=await stopped();
        const caller=await stackAt(out);assert.match(caller.stackFrames[0].name,/^मुख्य(?:\(.*\))?$/);
        await session.customRequest('continue',{threadId:out.threadId});const after=await stopped();
        const finalStack=await stackAt(after);
        assert.equal(finalStack.stackFrames[0].line,marker('DEBUG_AFTER')+1);await inspect(finalStack.stackFrames[0]);
        const finalCollections=Number((await session.customRequest('evaluate',{expression:'collections',frameId:finalStack.stackFrames[0].id,context:'watch'})).result);
        assert.ok(finalCollections>initialCollections+200,'forced collections must occur between inspections');
        const ended=new Promise((resolve,reject)=>{
            const timer=setTimeout(()=>{listener.dispose();reject(Error('Debug program did not terminate'));},30000);
            const listener=vscode.debug.onDidTerminateDebugSession(value=>{if(value.id===session.id){clearTimeout(timer);listener.dispose();resolve();}});
        });
        await session.customRequest('continue',{threadId:after.threadId});await ended;
        // Application streams use the integrated terminal, not DAP output events.
        // Exact live=0/root snapshots are checked by test_native_inspection.py.
        assert.ok(transcript.some(m=>m.type==='event'&&m.event==='exited'&&m.body.exitCode===0));
        // The build has a per-session lifetime; never reuse a previous executable.
        const deadline=Date.now()+10000;while(Date.now()<deadline){try{await fs.stat(buildDirectory);}catch{break;}await new Promise(r=>setTimeout(r,50));}
        await assert.rejects(fs.stat(buildDirectory));
        // Stopping a second paused session must release Windows executable handles,
        // remove its own temporary build, and never reuse the first program.
        const previousDirectory=buildDirectory,previousId=session.id;
        events.length=0;
        const restart=vscode.debug.onDidStartDebugSession(value=>{session=value;buildDirectory=value.configuration.bhashBuildDirectory;});
        try {
            assert.ok(await vscode.debug.startDebugging(vscode.workspace.workspaceFolders[0],{
                type:'cppdbg',request:'launch',name:'Bhash stop cleanup test',bhashNative:true,bhashInput:source,cwd:workspace,stopAtEntry:false,args:[]}));
        } finally {restart.dispose();}
        await stopped();assert.notEqual(session.id,previousId);assert.notEqual(buildDirectory,previousDirectory);
        const stoppedSession=session;
        const terminated=new Promise((resolve,reject)=>{
            const timer=setTimeout(()=>{listener.dispose();reject(Error('Stopped session did not terminate'));},30000);
            const listener=vscode.debug.onDidTerminateDebugSession(value=>{if(value.id===stoppedSession.id){clearTimeout(timer);listener.dispose();resolve();}});
        });
        await vscode.debug.stopDebugging(stoppedSession);await terminated;
        const stopDeadline=Date.now()+10000;
        while(Date.now()<stopDeadline){try{await fs.stat(buildDirectory);}catch{break;}await new Promise(r=>setTimeout(r,50));}
        await assert.rejects(fs.stat(buildDirectory));
        if(process.platform==='win32'){
            const ioSource=path.join(workspace,'console-debug.bhash');
            const ioUri=vscode.Uri.file(ioSource);
            const readyFile=path.join(workspace,'console-ready.txt');await fs.rm(readyFile,{force:true});
            const ioPoints=[new vscode.SourceBreakpoint(new vscode.Location(ioUri,new vscode.Position(2,0))),
                new vscode.SourceBreakpoint(new vscode.Location(ioUri,new vscode.Position(5,0)))];
            points.push(...ioPoints);vscode.debug.addBreakpoints(ioPoints);events.length=0;
            const ioStart=vscode.debug.onDidStartDebugSession(value=>{session=value;buildDirectory=value.configuration.bhashBuildDirectory;});
            try{assert.ok(await vscode.debug.startDebugging(vscode.workspace.workspaceFolders[0],{
                type:'bhash-native-windows',request:'launch',name:'Bhash terminal input',bhashInput:ioSource,cwd:workspace,args:[]}));}
            finally{ioStart.dispose();}
            const beforeInput=await stopped();assert.equal(debugTerminals.length,3);
            console.log('PASS Windows source stepping, heap inspection, GC and Stop cleanup with incompatible profiles');
            await session.customRequest('continue',{threadId:beforeInput.threadId});
            // GDB can reset console input as it resumes. Wait until the inferior has
            // resumed past its output line before typing into its terminal.
            const inputDeadline=Date.now()+10000;let ready=false;
            while(Date.now()<inputDeadline){try{ready=(await fs.readFile(readyFile,'utf8'))==='ready';if(ready)break;}catch{}await new Promise(r=>setTimeout(r,50));}
            assert.ok(ready,'debuggee must resume and become ready for terminal input');
            console.log('Windows debuggee ready for Hindi terminal input');
            debugTerminals[2].sendText('भारत😀',true);
            const afterInput=await stopped();const ioStack=await stackAt(afterInput);
            assert.equal(ioStack.stackFrames[0].line,6);
            const ioScopes=await session.customRequest('scopes',{frameId:ioStack.stackFrames[0].id});
            const ioValues=[];for(const scope of ioScopes.scopes.filter(s=>!s.expensive))
                ioValues.push(...(await session.customRequest('variables',{variablesReference:scope.variablesReference})).variables);
            assert.ok(ioValues.some(v=>v.name==='नाम'&&v.value.includes('भारत😀')),'Hindi interactive input reaches the native program');
            await vscode.debug.stopDebugging(session);
            const terminalDeadline=Date.now()+10000;
            while(Date.now()<terminalDeadline&&vscode.window.terminals.includes(debugTerminals[2]))await new Promise(r=>setTimeout(r,50));
            assert.ok(!vscode.window.terminals.includes(debugTerminals[2]),'interactive session terminal is closed on Stop');
        }
        if(process.platform==='win32'){
            assert.equal(await fs.readFile(process.env.BHASH_DEBUG_SETTINGS,'utf8'),settingsBefore,'terminal settings must remain byte-for-byte unchanged');
            await assert.rejects(fs.stat(process.env.BHASH_DEBUG_SHELL_MARKER),'incompatible default/automation profile must never run');
            assert.equal(debugTerminals.length,3,'each session gets its own terminal');
            assert.ok(!vscode.window.terminals.includes(debugTerminals[1]),'Stop closes the active debug terminal');
        }
        console.log('PASS real VS Code/GDB DAP: source breakpoints, step in/over/out, Hindi stacks, nested variables, forced GC, repeat sessions, Stop cleanup, incompatible Windows terminal profiles and Hindi interactive input.');
    } finally {
        await fs.writeFile(process.env.BHASH_DEBUG_LOG,JSON.stringify({transcript,outputs},null,2));
        vscode.debug.removeBreakpoints(points);tracker.dispose();terminalListener.dispose();if(session)await vscode.debug.stopDebugging(session);
    }
};
