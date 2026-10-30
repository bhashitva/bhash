// SPDX-License-Identifier: Apache-2.0
import {spawn, ChildProcess, SpawnOptions} from 'node:child_process';
import * as path from 'node:path';
import {NativeOptions, compilerInvocation, ensureNative, nativeEnvironment, capture, requireNativeHost, terminateTree} from './native';
export interface ConsoleRequest extends NativeOptions {
    operation: 'build' | 'run' | 'setup' | 'check' | 'repair';
    input?: string;
    target?: 'native' | 'jvm' | 'clr' | 'wasm-browser';
}
type Launch = (command: string, args: string[], options: SpawnOptions) => ChildProcess;
/** Windows terminal backed by pipes, without a shell or another desktop Code.exe. */
export class TaskConsole {
    private readonly abort = new AbortController();
    private child?: ChildProcess;
    private line = '';
    private queued: string[] = [];
    private eof = false;
    private skipLf = false;
    private finished = false;
    private escape = '';
    private previousCr = false;
    constructor(private readonly request: ConsoleRequest, private readonly cwd: string,
        private readonly write: (text: string) => void, private readonly done: (code: number) => void,
        private readonly launch: Launch = spawn) {}
    private output(text: string): void {
        let result = '';
        for (const char of text) {
            if (char === '\n' && !this.previousCr) result += '\r';
            result += char; this.previousCr = char === '\r';
        }
        this.write(result);
    }
    private finish(code: number): void {if (!this.finished) {this.finished = true; this.done(code);}}
    close(): void {this.abort.abort(); if (this.child) terminateTree(this.child);}
    handleInput(data: string): void {
        if (this.finished || this.eof) return;
        for (const char of data) {
            if (this.escape || char === '\x1b') {
                this.escape += char;
                if (this.escape.length > 2 && /[A-Za-z~]$/.test(this.escape)) this.escape = '';
                continue;
            }
            if (char === '\x03') {this.write('^C\r\n'); this.close(); return;}
            if (char === '\x04' || char === '\x1a') {
                if (this.line) this.queued.push(this.line);
                this.line = ''; this.eof = true; this.flush(); return;
            }
            if (char === '\n' && this.skipLf) {this.skipLf = false; continue;}
            this.skipLf = char === '\r';
            if (char === '\r' || char === '\n') {
                this.write('\r\n'); this.queued.push(this.line + '\n'); this.line = ''; this.flush();
            } else if (char === '\x7f' || char === '\b') {
                const chars = Array.from(this.line);
                if (chars.length) {const removed=chars.pop()!; this.line = chars.join(''); this.write('\b \b'.repeat(removed.codePointAt(0)! > 0xffff ? 2 : 1));}
            } else if (char >= ' ' && char !== '\x7f') {this.line += char; this.write(char);}
        }
    }
    private flush(): void {
        if (!this.child?.stdin || this.child.stdin.destroyed) return;
        for (const line of this.queued.splice(0)) this.child.stdin.write(line, 'utf8');
        if (this.eof) this.child.stdin.end();
    }
    async start(): Promise<void> {
        try {
            const target = this.request.target || 'native';
            if (target === 'native') {
                requireNativeHost();
                if (this.request.operation !== 'check') await ensureNative(this.request, message => this.output(message+'\n'), this.abort.signal, this.request.operation === 'repair');
            }
            this.abort.signal.throwIfAborted();
            const env = target === 'native' ? nativeEnvironment(this.request) : {...process.env};
            if (['setup','check','repair'].includes(this.request.operation)) {
                const invocation = compilerInvocation(this.request.jar,['native','doctor','--json'],process.platform,this.cwd);
                this.output(await capture(this.request.java,invocation.args,env,this.abort.signal,invocation.cwd));
                this.finish(0); return;
            }
            if (!this.request.input) throw new Error('Task input is missing.');
            const invocation = compilerInvocation(this.request.jar,[this.request.operation === 'build' ? 'compile' : 'run',path.resolve(this.cwd,this.request.input),'--target',target],process.platform,this.cwd);
            this.child = this.launch(this.request.java,invocation.args,{env,cwd:invocation.cwd || this.cwd,windowsHide:true,stdio:'pipe',detached:process.platform === 'linux'});
            const child=this.child;
            child.stdin?.on('error',()=>{}); // A program may exit before queued input is consumed.
            child.stdout?.setEncoding('utf8').on('data',(text:string)=>this.output(text));
            child.stderr?.setEncoding('utf8').on('data',(text:string)=>this.output(text));
            const stop=()=>terminateTree(child);
            this.abort.signal.addEventListener('abort',stop,{once:true});
            const result = new Promise<number>((resolve,reject)=>{
                child.once('error',reject);
                child.once('close',code=>resolve(this.abort.signal.aborted ? 130 : code ?? 1));
            });
            this.flush();
            if (this.abort.signal.aborted) stop();
            try {this.finish(await result);} finally {this.abort.signal.removeEventListener('abort',stop);}
        } catch (error) {
            if (!this.abort.signal.aborted) this.output(`Bhash: ${error instanceof Error ? error.message : String(error)}\n`);
            this.finish(this.abort.signal.aborted ? 130 : 1);
        }
    }
}
