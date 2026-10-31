// SPDX-License-Identifier: Apache-2.0
import type * as vscode from 'vscode';
import {spawn, ChildProcessWithoutNullStreams} from 'node:child_process';
import * as path from 'node:path';

type Message = {seq: number; type: string; command?: string; request_seq?: number;
    event?: string; arguments?: any; body?: any; success?: boolean; message?: string};
const MAX_FRAME = 16 * 1024 * 1024;

// DAP framing is byte based: Hindi text may span chunks and has more bytes than characters.
export class DapReader {
    private buffer = Buffer.alloc(0);
    accept(chunk: Buffer, receive: (message: Message) => void) {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        while (this.buffer.length) {
            const end = this.buffer.indexOf('\r\n\r\n');
            if (end < 0) {if (this.buffer.length > 8192) throw Error('Invalid debugger protocol header'); return;}
            if (end > 8192) throw Error('Invalid debugger protocol header');
            const lengths = [...this.buffer.subarray(0,end).toString('ascii').matchAll(/^Content-Length: (\d+)\r?$/gmi)];
            const size = lengths.length === 1 ? Number(lengths[0][1]) : NaN;
            if (!Number.isSafeInteger(size) || size < 1 || size > MAX_FRAME) throw Error('Invalid debugger protocol length');
            if (this.buffer.length < end + 4 + size) return;
            const message = JSON.parse(this.buffer.subarray(end+4,end+4+size).toString('utf8'));
            if (!message || !Number.isInteger(message.seq) || !['request','response','event'].includes(message.type))
                throw Error('Invalid debugger protocol message');
            this.buffer = this.buffer.subarray(end+4+size);
            receive(message);
        }
    }
}

export function dapFrame(message: Message): Buffer {
    const body = Buffer.from(JSON.stringify(message),'utf8');
    return Buffer.concat([Buffer.from(`Content-Length: ${body.length}\r\n\r\n`),body]);
}

// Launch Microsoft's WindowsDebugLauncher directly in a dedicated integrated terminal.
// No user shell, command-string construction, or terminal profile changes are involved.
export function windowsDebugTerminal(arguments_: any, launcher: string, defaultCwd: string): vscode.TerminalOptions {
    // MIEngine deliberately sends an empty cwd for its Windows launcher.
    const cwd = arguments_?.cwd === '' ? defaultCwd : arguments_?.cwd;
    const args = arguments_?.args;
    if (arguments_?.kind !== 'integrated' || !Array.isArray(args) || args.length < 2
        || !args.every(value=>typeof value === 'string' && !/[\0\r\n]/.test(value))
        || path.win32.normalize(args[0]).toLowerCase() !== path.win32.normalize(launcher).toLowerCase()
        || typeof cwd !== 'string' || /[\0\r\n]/.test(cwd) || (!path.win32.isAbsolute(cwd) || path.win32.parse(cwd).root.length < 2))
        throw Error('Unsupported Windows debugger terminal request. Update the Bhash and Microsoft C/C++ extensions.');
    if (!path.win32.isAbsolute(launcher) || /[\0\r\n]/.test(launcher)) throw Error('Windows debugger launcher is unavailable');
    const env = arguments_.env;
    if (env !== undefined && (!env || Array.isArray(env) || typeof env !== 'object'
        || Object.entries(env).some(([key,value])=>/[=\0]/.test(key) || value !== null && (typeof value !== 'string' || value.includes('\0')))))
        throw Error('Invalid debugger terminal environment');
    return {name:'Bhash: Native Debug',shellPath:launcher,
        shellArgs:args.slice(1),cwd,env,isTransient:true};
}

interface Terminal {processId: Thenable<number | undefined>; show(preserveFocus?: boolean): void; dispose(): void;}
interface Services {
    cwd: string;
    terminal(options: vscode.TerminalOptions): Terminal;
    closed(listener: (terminal: Terminal)=>void): vscode.Disposable;
    log(text: string): void;
}

// A separate debug type avoids replacing C/C++'s registered adapter factory. All debugging
// still uses its installed OpenDebugAD7/GDB implementation. This bridge encodes automatic
// Bhash evaluations and handles Windows terminal requests; Linux terminals go to VS Code.
export class NativeDebugAdapter implements vscode.DebugAdapter {
    private child: ChildProcessWithoutNullStreams;
    private listeners = new Set<(message: Message)=>void>();
    private inputSeq = 0;
    private outputSeq = 0;
    private requests = new Map<number,{seq: number; command: string; watch: boolean}>();
    private reverse = new Map<number,number>();
    private terminals = new Set<Terminal>();
    private closed: vscode.Disposable;
    private disposed = false;
    private exited = false;
    readonly onDidSendMessage: vscode.Event<any> = (listener, thisArgs, disposables) => {
        const bound = (message: Message)=>listener.call(thisArgs,message);
        this.listeners.add(bound);
        const disposable = {dispose:()=>{this.listeners.delete(bound);}};
        disposables?.push(disposable); return disposable;
    };
    constructor(adapter: string, private services: Services, private launcher: string | undefined = process.platform === 'win32' ? path.win32.join(path.win32.dirname(adapter),'WindowsDebugLauncher.exe') : undefined,
        start: (command: string)=>ChildProcessWithoutNullStreams = command=>spawn(command,[],{stdio:'pipe',windowsHide:true})) {
        this.closed = services.closed(terminal=>{if (this.terminals.delete(terminal) && !this.exited) this.fail('The Bhash debug terminal was closed.');});
        this.child = start(adapter);
        const reader = new DapReader();
        this.child.stdout.on('data',chunk=>{try {reader.accept(chunk,message=>this.receive(message));} catch(error) {this.fail(String(error));}});
        this.child.stderr.on('data',chunk=>services.log(String(chunk)));
        this.child.on('error',error=>this.fail(`Microsoft C/C++ debugger could not start: ${error.message}`));
        this.child.stdin.on('error',error=>this.fail(`Debugger connection failed: ${error.message}`));
        this.child.on('close',()=>{if (!this.disposed) this.fail('The debugger adapter exited.');});
    }
    handleMessage(message: Message) {
        if (this.disposed) return;
        if (message.type === 'request' && message.command === 'evaluate' && message.arguments?.context !== 'repl') {
            const expression = message.arguments?.expression;
            if (typeof expression !== 'string' || Buffer.byteLength(expression,'utf8') > 4096) {
                this.emit({seq:0,type:'response',request_seq:message.seq,command:'evaluate',success:false,message:'Bhash Watch: expression exceeds 4096 UTF-8 bytes.'});
                return;
            }
            message = {...message,arguments:{...message.arguments,expression:'$bhash_watch("' + Buffer.from(expression,'utf8').toString('base64') + '")'}};
        }
        const forwarded = {...message,seq:++this.inputSeq};
        if (message.type === 'request') {
            this.requests.set(forwarded.seq,{seq:message.seq,command:message.command!,watch:message.command === 'evaluate' && message.arguments?.context !== 'repl'});
            if (message.command === 'initialize') forwarded.arguments = {...message.arguments,adapterID:'cppdbg'};
            if (message.command === 'launch') forwarded.arguments = {...message.arguments,type:'cppdbg'};
        } else if (message.type === 'response') {
            const original = this.reverse.get(message.request_seq!);
            if (original === undefined) return;
            forwarded.request_seq = original; this.reverse.delete(message.request_seq!);
        }
        this.write(forwarded);
    }
    private write(message: Message) {if (!this.disposed) this.child.stdin.write(dapFrame(message));}
    private emit(message: Message) {
        const forwarded = {...message,seq:++this.outputSeq};
        for (const listener of this.listeners) listener(forwarded);
        return forwarded.seq;
    }
    private receive(message: Message) {
        if (this.disposed) return;
        if (this.launcher && message.type === 'request' && message.command === 'runInTerminal') {void this.runTerminal(message); return;}
        if (message.type === 'response') {
            const original = this.requests.get(message.request_seq!);
            if (original === undefined) return;
            this.requests.delete(message.request_seq!); message = {...message,request_seq:original.seq};
            // MIEngine flattens GDB's reserved array bounds to a length. Its
            // error printer is unquoted; ordinary literal/managed text is quoted.
            if (original.watch && message.success && /^char \[(?:-1\.\.)?\d+\]$/.test(message.body?.type || '')
                && typeof message.body?.result === 'string' && message.body.result.startsWith('Bhash Watch: ')) {
                message = {...message,success:false,message:String(message.body?.result || 'Bhash Watch: evaluation failed.'),body:undefined};
            }
        }
        if (message.type === 'event' && message.event === 'exited') this.exited = true;
        if (message.type === 'request') this.reverse.set(this.outputSeq+1,message.seq);
        this.emit(message);
    }
    private async runTerminal(message: Message) {
        let terminal: Terminal | undefined;
        try {
            terminal = this.services.terminal(windowsDebugTerminal(message.arguments,this.launcher!,this.services.cwd));
            this.terminals.add(terminal); terminal.show(true);
            const pid = await terminal.processId;
            if (this.disposed) {terminal.dispose(); return;}
            if (!pid) throw Error('Windows debug terminal did not start.');
            this.write({seq:++this.inputSeq,type:'response',request_seq:message.seq,command:'runInTerminal',success:true,body:{shellProcessId:pid}});
        } catch(error) {
            if (terminal) {this.terminals.delete(terminal);terminal.dispose();}
            this.write({seq:++this.inputSeq,type:'response',request_seq:message.seq,command:'runInTerminal',success:false,message:String(error)});
        }
    }
    private fail(reason: string) {
        if (this.disposed) return;
        this.services.log(reason);
        for (const request of this.requests.values()) this.emit({seq:0,type:'response',request_seq:request.seq,success:false,command:request.command,message:reason});
        this.emit({seq:0,type:'event',event:'output',body:{category:'stderr',output:reason+'\n'}});
        this.emit({seq:0,type:'event',event:'terminated'}); this.dispose();
    }
    dispose() {
        if (this.disposed) return;
        this.disposed = true; this.closed.dispose();
        if (!this.exited) for (const terminal of this.terminals) terminal.dispose();
        this.terminals.clear(); this.requests.clear();this.reverse.clear();
        this.child.stdin.end(); this.child.kill(); this.listeners.clear();
    }
}

// Preserve the original internal export for callers testing the Windows terminal bridge.
export {NativeDebugAdapter as WindowsDebugAdapter};
