// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs');const os=require('node:os');const path=require('node:path');const {spawn}=require('node:child_process');
const {TaskConsole}=require('../dist/task-console.js');
const request={java:'java.exe',jar:path.resolve('compiler.jar'),mode:'managed',operation:'run',target:'jvm',input:path.resolve('hello.bhash')};
test('direct terminal preserves UTF-8, edits queued input, waits for output and reports exit code without a GUI helper',async()=>{
    let output='',exit,launchCount=0;
    const terminal=new TaskConsole(request,process.cwd(),text=>output+=text,code=>exit=code,(command,args,options)=>{
        ++launchCount;assert.equal(command,'java.exe');assert.equal(options.windowsHide,true);assert.equal(options.stdio,'pipe');assert.ok(!options.shell);
        return spawn(process.execPath,['-e',`let text='';process.stdin.setEncoding('utf8');process.stdin.on('data',d=>{text+=d;if(text.includes('\\n')){console.log(JSON.stringify(text));console.error('त्रुटि😀');process.exitCode=7;process.stdin.destroy();}});`],options);
    });
    terminal.handleInput('भारत😀X\x7f\r\n');
    await terminal.start();assert.equal(exit,7);assert.equal(launchCount,1);
    assert.match(output,/"भारत😀\\n"/);assert.match(output,/त्रुटि😀\r\n/);assert.doesNotMatch(output,/\r\r\n/);
});
test('Ctrl-C terminates the child and closes the task once with cancellation status',{timeout:20000},async()=>{
    let exits=[],terminal;
    terminal=new TaskConsole(request,process.cwd(),text=>{if(text.includes('READY'))terminal.handleInput('\x03');},code=>exits.push(code),
        (_command,_args,options)=>spawn(process.execPath,['-e',`console.log('READY');setInterval(()=>{},1000);`],options));
    await terminal.start();assert.deepEqual(exits,[130]);
});
test('closing during setup/start does not launch a process',async()=>{
    let code,launched=false;
    const terminal=new TaskConsole(request,process.cwd(),()=>{},exit=>code=exit,()=>{launched=true;throw Error('must not launch');});
    terminal.close();await terminal.start();assert.equal(launched,false);assert.equal(code,130);
});

test('Android tasks use the compiler CLI and keep Hindi Logcat output in the terminal', async () => {
    const project = path.resolve('Hindi Android App');
    const android = {java: request.java, jar: request.jar, androidArgs: ['logcat', '--device', 'emulator-5554']};
    let output = '';
    let exit;
    const terminal = new TaskConsole(android, project, text => { output += text; }, code => { exit = code; },
        (command, args, options) => {
            assert.equal(command, request.java);
            assert.equal(options.windowsHide, true);
            assert.ok(!options.shell);
            const jarIndex = args.indexOf('-jar');
            assert.ok(jarIndex >= 0);
            if (process.platform === 'win32') {
                assert.equal(args[jarIndex + 1], path.basename(request.jar));
                assert.equal(options.cwd, path.dirname(request.jar));
                const invocation = JSON.parse(Buffer.from(args[jarIndex + 3], 'base64').toString('utf8'));
                assert.deepEqual(invocation.args, ['android', ...android.androidArgs]);
                assert.equal(invocation.cwd, project);
            } else {
                assert.deepEqual(args.slice(jarIndex + 1), [request.jar, 'android', ...android.androidArgs]);
                assert.equal(options.cwd, project);
            }
            return spawn(process.execPath, ['-e', 'console.log("भाष Android smoke")'], {...options, cwd: process.cwd()});
        });
    await terminal.start();
    assert.equal(exit, 0, output);
    assert.match(output, /भाष Android smoke\r\n/);
});
