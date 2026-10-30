// SPDX-License-Identifier: Apache-2.0
const vscode=require('vscode');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');

exports.run=async()=>{
    const workspace=process.env.BHASH_DEBUG_WORKSPACE;
    const source=path.join(workspace,'debug-inspection.bhash');
    const lines=(await fs.readFile(source,'utf8')).split('\n');
    const marker=name=>lines.findIndex(line=>line.includes(name));
    const uri=vscode.Uri.file(source);
    await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri));
    const extension=vscode.extensions.getExtension('bhashitva.bhash');assert.ok(extension);await extension.activate();
    assert.ok(vscode.extensions.getExtension('ms-vscode.cpptools'),'real Microsoft GDB adapter installed');
    const events=[],transcript=[],outputs=[];let wake;
    const tracker=vscode.debug.registerDebugAdapterTrackerFactory('cppdbg',{createDebugAdapterTracker:()=>({
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
    const points=['DEBUG_BEFORE','DEBUG_AFTER'].map(name=>new vscode.SourceBreakpoint(new vscode.Location(uri,new vscode.Position(marker(name),0))));
    vscode.debug.addBreakpoints(points);
    try {
        const start=vscode.debug.onDidStartDebugSession(value=>{session=value;buildDirectory=value.configuration.bhashBuildDirectory;});
        assert.ok(await vscode.debug.startDebugging(vscode.workspace.workspaceFolders[0],{
            type:'cppdbg',request:'launch',name:'Bhash real GDB test',bhashNative:true,bhashInput:source,cwd:workspace,
            environment:[{name:'BHASH_GC_STRESS',value:'1'},{name:'BHASH_GC_STATS',value:'1'}],stopAtEntry:false,args:[]}));
        start.dispose();
        const first=await stopped();assert.ok(session);
        const stack=await stackAt(first);
        assert.match(stack.stackFrames[0].name,/^मुख्य(?:\(.*\))?$/);assert.equal(stack.stackFrames[0].source.path,source);
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
        const deadline=Date.now()+5000;while(Date.now()<deadline){try{await fs.stat(buildDirectory);}catch{break;}await new Promise(r=>setTimeout(r,50));}
        await assert.rejects(fs.stat(buildDirectory));
        console.log('PASS real VS Code/GDB DAP: source breakpoints, step in/over/out, Hindi stacks, nested variables, forced GC and cleanup.');
    } finally {
        await fs.writeFile(process.env.BHASH_DEBUG_LOG,JSON.stringify({transcript,outputs},null,2));
        vscode.debug.removeBreakpoints(points);tracker.dispose();if(session)await vscode.debug.stopDebugging(session);
    }
};
