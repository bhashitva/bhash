// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {PassThrough}=require('node:stream');
const {DapReader,dapFrame,windowsDebugTerminal,WindowsDebugAdapter}=require('../dist/windows-debug-adapter.js');
const launcher="C:\\Extensions हिन्दी 'quotes'\\debugAdapters\\bin\\WindowsDebugLauncher.exe";
const args={kind:'integrated',cwd:'C:\\Projects हिन्दी',env:{DEBUGINFOD_URLS:'',REMOVE_ME:null},
    args:[launcher,'--stdin=pipe','--dbgExe=C:\\GDB हिन्दी\\gdb.exe','--interpreter=mi "--data-directory=C:/GDB हिन्दी/share/gdb"']};

test('Windows terminal directly runs the vendor launcher, preserving argv, cwd and environment',()=>{
    const result=windowsDebugTerminal(args,launcher,args.cwd);
    assert.equal(result.shellPath,launcher);assert.deepEqual(result.shellArgs,args.args.slice(1));
    assert.equal(windowsDebugTerminal({...args,cwd:''},launcher,'C:\\Fallback हिन्दी').cwd,'C:\\Fallback हिन्दी');
    assert.throws(()=>windowsDebugTerminal({...args,cwd:''},launcher,'/mnt/c/test'));
    assert.equal(result.cwd,args.cwd);assert.deepEqual(result.env,args.env);assert.equal(result.isTransient,true);
    for(const changed of [{kind:'external'},{args:['wsl.exe','anything']},{args:['cmd','/c','echo wrong']},
        {args:[launcher,'bad\nline']},{cwd:'/mnt/c/test'},{env:{bad:42}},{env:{bad:'a\0b'}}])
        assert.throws(()=>windowsDebugTerminal({...args,...changed},launcher,args.cwd));
});

test('DAP reader handles byte-split Hindi and combined messages, rejecting malformed/oversized input',()=>{
    const reader=new DapReader(),messages=[];
    const expected=[{seq:1,type:'event',event:'output',body:{output:'भारत😀'}},{seq:2,type:'response',request_seq:1,success:true}];
    const bytes=Buffer.concat(expected.map(dapFrame));
    for(const byte of bytes)reader.accept(Buffer.from([byte]),message=>messages.push(message));
    assert.deepEqual(messages,expected);
    const together=[];new DapReader().accept(bytes,message=>together.push(message));assert.deepEqual(together,expected);
    for(const data of ['X: 4\r\n\r\n{}','Content-Length: 999999999\r\n\r\n',
        'Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}','x'.repeat(8193),'Content-Length: 2\r\n\r\n{}'])
        assert.throws(()=>new DapReader().accept(Buffer.from(data),()=>{}));
});

function fixture(pid=123, terminalLauncher=launcher){
    const child=new EventEmitter();child.stdin=new PassThrough();child.stdout=new PassThrough();child.stderr=new PassThrough();
    child.kill=()=>{child.killed=true;};
    const writes=[],events=[],terminals=[],logs=[];let onClose;
    const reader=new DapReader();child.stdin.on('data',bytes=>reader.accept(bytes,message=>writes.push(message)));
    const adapter=new WindowsDebugAdapter('C:\\Extensions\\OpenDebugAD7.exe',{
        cwd:args.cwd,
        terminal(options){const terminal={processId:Promise.resolve(pid),options,show(){},dispose(){this.disposed=true;}};terminals.push(terminal);return terminal;},
        closed(listener){onClose=listener;return {dispose(){}};},log:text=>logs.push(text)
    },terminalLauncher,()=>child);
    adapter.onDidSendMessage(message=>events.push(message));
    return {adapter,child,writes,events,terminals,logs,receive:message=>child.stdout.write(dapFrame(message)),close:terminal=>onClose(terminal)};
}

test('bridge correlates requests and reverse responses while handling terminal creation locally',async()=>{
    const f=fixture();
    try{
        f.adapter.handleMessage({seq:11,type:'request',command:'initialize',arguments:{adapterID:'bhash-native-windows'}});
        assert.equal(f.writes[0].arguments.adapterID,'cppdbg');
        f.receive({seq:1,type:'response',command:'initialize',request_seq:f.writes[0].seq,success:true});
        assert.equal(f.events[0].request_seq,11);
        f.receive({seq:2,type:'request',command:'runInTerminal',arguments:{...args,cwd:''}});
        await new Promise(resolve=>setImmediate(resolve));
        assert.equal(f.terminals.length,1);assert.equal(f.events.length,1);
        const response=f.writes.at(-1);assert.equal(response.request_seq,2);assert.equal(response.success,true);assert.equal(response.body.shellProcessId,123);
        f.adapter.handleMessage({seq:12,type:'request',command:'launch',arguments:{type:'bhash-native-windows'}});
        assert.equal(f.writes.at(-1).arguments.type,'cppdbg');assert.ok(f.writes.at(-1).seq>response.seq);
        f.receive({seq:3,type:'request',command:'customReverse'});
        f.adapter.handleMessage({seq:13,type:'response',command:'customReverse',request_seq:f.events.at(-1).seq,success:true});
        assert.equal(f.writes.at(-1).request_seq,3);
        f.receive({seq:4,type:'response',command:'launch',request_seq:f.writes.find(x=>x.command==='launch').seq,success:true});
        assert.equal(f.events.at(-1).request_seq,12);
        f.close(f.terminals[0]);assert.equal(f.child.killed,true);assert.ok(f.events.some(e=>e.event==='terminated'));
    }finally{f.adapter.dispose();}
});

test('Watch and hover use encoded Bhash evaluation; explicit REPL retains GDB syntax',()=>{
    const f=fixture();
    try {
        for(const context of ['watch','hover',undefined]) {
            const expression='वस्तु.अंक + अंक[१]';
            f.adapter.handleMessage({seq:31,type:'request',command:'evaluate',arguments:{expression,frameId:8,context}});
            const forwarded=f.writes.at(-1);
            assert.equal(forwarded.arguments.frameId,8);
            assert.equal(forwarded.arguments.expression,'$bhash_watch("'+Buffer.from(expression,'utf8').toString('base64')+'")');
            f.receive({seq:22,type:'response',command:'evaluate',request_seq:forwarded.seq,success:true,body:{result:'84'}});
            assert.equal(f.events.at(-1).request_seq,31);
        }
        f.adapter.handleMessage({seq:32,type:'request',command:'evaluate',arguments:{expression:'-exec info registers',context:'repl'}});
        assert.equal(f.writes.at(-1).arguments.expression,'-exec info registers');
        const before=f.writes.length;
        f.adapter.handleMessage({seq:33,type:'request',command:'evaluate',arguments:{expression:'अ'.repeat(2000),context:'watch'}});
        assert.equal(f.writes.length,before);assert.equal(f.events.at(-1).success,false);
    } finally {f.adapter.dispose();}
});

test('Linux forwards terminal reverse requests to VS Code and preserves correlation',()=>{
    const f=fixture(123,null);
    try {
        f.receive({seq:55,type:'request',command:'runInTerminal',arguments:{kind:'integrated',cwd:'/tmp',args:['/bin/sh']}});
        assert.equal(f.terminals.length,0);
        assert.equal(f.events.at(-1).command,'runInTerminal');
        f.adapter.handleMessage({seq:99,type:'response',request_seq:f.events.at(-1).seq,command:'runInTerminal',success:true,body:{shellProcessId:123}});
        assert.equal(f.writes.at(-1).request_seq,55);
    } finally {f.adapter.dispose();}
});

test('terminal start failures return a DAP error; Stop disposes active terminal and adapter',async()=>{
    const f=fixture(null);
    f.receive({seq:1,type:'request',command:'runInTerminal',arguments:args});
    await new Promise(resolve=>setImmediate(resolve));
    assert.equal(f.writes.at(-1).success,false);
    f.adapter.dispose();assert.equal(f.child.killed,true);assert.equal(f.terminals[0].disposed,true);
    const invalid=fixture();invalid.receive({seq:2,type:'request',command:'runInTerminal',arguments:{...args,args:['wsl.exe']}});
    await new Promise(resolve=>setImmediate(resolve));assert.equal(invalid.writes.at(-1).success,false);assert.equal(invalid.terminals.length,0);invalid.adapter.dispose();
});

test('late terminal startup after cancellation is cleaned up, while completed output stays visible',async()=>{
    let ready;const waiting=fixture(new Promise(resolve=>{ready=resolve;}));waiting.receive({seq:1,type:'request',command:'runInTerminal',arguments:args});
    waiting.adapter.dispose();ready(123);await new Promise(resolve=>setImmediate(resolve));assert.equal(waiting.terminals[0].disposed,true);
    const completed=fixture();completed.receive({seq:1,type:'request',command:'runInTerminal',arguments:args});
    await new Promise(resolve=>setImmediate(resolve));completed.receive({seq:2,type:'event',event:'exited',body:{exitCode:0}});
    completed.adapter.dispose();assert.ok(!completed.terminals[0].disposed,'retain completed terminal output');
});

test('adapter startup and malformed-protocol failures terminate without an unhandled stream error',()=>{
    for(const action of [f=>f.child.emit('error',Error('ENOENT')),f=>f.child.stdout.write(Buffer.from('bad\r\n\r\n'))]){
        const f=fixture();f.adapter.handleMessage({seq:8,type:'request',command:'initialize'});action(f);
        assert.ok(f.events.some(m=>m.type==='response'&&m.command==='initialize'&&m.request_seq===8&&!m.success));
        assert.ok(f.events.some(m=>m.event==='terminated'));assert.equal(f.child.killed,true);
    }
});
