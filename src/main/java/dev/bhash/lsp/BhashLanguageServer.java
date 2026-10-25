// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import dev.bhash.backend.*;
import dev.bhash.frontend.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.*;

/** Local, stdio-only LSP. Analysis never builds or executes user programs. */
public final class BhashLanguageServer implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService, AutoCloseable {
    private record Buffer(String text, int version) {}
    private record ProjectState(Path input, List<SourceFile> sources, Map<String,SourceIndex> indexes, Map<String,List<Diagnostic>> diagnostics) {}
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"bhash-analysis");t.setDaemon(true);return t;});
    private final Map<Path,Buffer> buffers = new LinkedHashMap<>();
    private final Set<Path> roots = new LinkedHashSet<>();
    private final Map<Path,ProjectState> projects = new LinkedHashMap<>();
    private final Set<String> published = new HashSet<>();
    private final AtomicLong revision = new AtomicLong();
    private LanguageClient client;
    private ScheduledFuture<?> pending;
    private volatile boolean shutdown;
    private boolean needsDiscovery = true;
    public static void main(String[] args) {
        try (var server = new BhashLanguageServer()) {
            Launcher<LanguageClient> launcher=LSPLauncher.createServerLauncher(server,System.in,System.out);
            server.connect(launcher.getRemoteProxy());
            launcher.startListening().get();
        } catch (Exception error) { System.err.println("Bhash language server stopped: "+error.getMessage()); }
    }
    @Override public void connect(LanguageClient client) { this.client=client; }
    @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        return submit(()->{
            if(params.getWorkspaceFolders()!=null)for(var folder:params.getWorkspaceFolders()) {Path p=path(folder.getUri());if(p!=null)roots.add(p);}
            if(roots.isEmpty() && params.getRootUri()!=null){Path p=path(params.getRootUri());if(p!=null)roots.add(p);}
            var capabilities=new ServerCapabilities();
            capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
            capabilities.setCompletionProvider(new CompletionOptions(false,List.of(".")));
            capabilities.setHoverProvider(true);capabilities.setDefinitionProvider(true);capabilities.setDocumentSymbolProvider(true);capabilities.setWorkspaceSymbolProvider(true);
            var folders = new WorkspaceFoldersOptions();folders.setSupported(true);folders.setChangeNotifications(true);
            capabilities.setWorkspace(new WorkspaceServerCapabilities(folders));
            return new InitializeResult(capabilities,new ServerInfo("Bhash","0.1.0"));
        });
    }
    @Override public void initialized(InitializedParams params) { schedule(true); }
    @Override public CompletableFuture<Object> shutdown() { shutdown=true;return submit(()->null); }
    @Override public void exit() { close(); }
    @Override public void close() { executor.shutdownNow(); }
    @Override public TextDocumentService getTextDocumentService() { return this; }
    @Override public WorkspaceService getWorkspaceService() { return this; }
    private <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(()->{try{return task.call();}catch(Exception e){throw new CompletionException(e);}},executor);
    }
    static Path path(String uri) {try {var parsed=URI.create(uri);return "file".equalsIgnoreCase(parsed.getScheme())?Path.of(parsed).toAbsolutePath().normalize():null;}catch(Exception e){return null;} }
    private void change(Runnable mutation) {
        long version=revision.incrementAndGet();
        executor.execute(()->{mutation.run();schedule(version,false);});
    }
    private void schedule(boolean discover) {long version=revision.incrementAndGet();executor.execute(()->schedule(version,discover));}
    private void schedule(long version, boolean discover) {
        needsDiscovery |= discover;
        if(pending!=null)pending.cancel(false);
        pending=executor.schedule(()->analyze(version,discover),300,TimeUnit.MILLISECONDS);
    }
    @Override public void didOpen(DidOpenTextDocumentParams params) {
        var document=params.getTextDocument();Path path=path(document.getUri());if(path==null)return;
        change(()->buffers.put(path,new Buffer(document.getText(),document.getVersion())));
    }
    @Override public void didChange(DidChangeTextDocumentParams params) {
        Path path=path(params.getTextDocument().getUri());if(path==null)return;
        change(()->{var old=buffers.get(path);if(old==null || params.getTextDocument().getVersion()<=old.version)return;
            String text=old.text;
            for(var edit:params.getContentChanges()) {
                if(edit.getRange()==null)text=edit.getText();
                else {var c=new TextCoordinates(text);int start=c.offset(edit.getRange().getStart()),end=c.offset(edit.getRange().getEnd());text=text.substring(0,start)+edit.getText()+text.substring(end);}
            }
            buffers.put(path,new Buffer(text,params.getTextDocument().getVersion()));});
    }
    @Override public void didClose(DidCloseTextDocumentParams params) {Path path=path(params.getTextDocument().getUri());if(path!=null)change(()->buffers.remove(path));}
    @Override public void didSave(DidSaveTextDocumentParams params) {schedule(false);}
    @Override public void didChangeConfiguration(DidChangeConfigurationParams params) {schedule(true);}
    @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {schedule(true);}
    @Override public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        change(()->{for(var folder:params.getEvent().getRemoved()){Path p=path(folder.getUri());if(p!=null)roots.remove(p);}
            for(var folder:params.getEvent().getAdded()){Path p=path(folder.getUri());if(p!=null)roots.add(p);}projects.clear();});
        schedule(true);
    }
    private Path input(Path source) {
        if(source.getFileName().toString().equals(ProjectLoader.MANIFEST))return source;
        Path boundary=roots.stream().filter(source::startsWith).max(Comparator.comparingInt(Path::getNameCount)).orElse(source.getParent());
        for(Path parent=source.getParent();parent!=null && parent.startsWith(boundary);parent=parent.getParent()) {
            Path candidate=parent.resolve(ProjectLoader.MANIFEST);
            if(Files.isRegularFile(candidate)||buffers.containsKey(candidate)) {
                try {
                    var properties=new Properties();var buffer=buffers.get(candidate);
                    properties.load(new StringReader(buffer==null?Files.readString(candidate):buffer.text));
                    for(String entry:properties.getProperty("sources","src").split(","))
                        if(source.startsWith(parent.resolve(entry.strip()).normalize()))return candidate;
                    return source;
                } catch(IOException | IllegalArgumentException invalid) {return candidate;}
            }
        }
        return source;
    }
    private Set<Path> discover() throws IOException {
        var inputs=new LinkedHashSet<Path>();
        for(Path root:roots)if(Files.isDirectory(root))Files.walkFileTree(root,Set.of(),32,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attributes) {
                return !dir.equals(root)&&Set.of(".git","node_modules","target","build","dist",".idea",".vscode").contains(dir.getFileName().toString())?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) {
                if(attributes.isRegularFile() && (file.getFileName().toString().equals(ProjectLoader.MANIFEST)||file.toString().endsWith(".bhash")))inputs.add(input(file));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException failure) {return FileVisitResult.CONTINUE;}
        });
        return inputs;
    }
    private void analyze(long version,boolean discover) {
        if(shutdown || version!=revision.get())return;
        var inputs=new LinkedHashSet<Path>();var diagnostics=new LinkedHashMap<String,List<Diagnostic>>();
        if(discover || needsDiscovery)try{inputs.addAll(discover());}catch(IOException e){log("Workspace discovery: "+e.getMessage());}
        for(Path existing:projects.keySet())inputs.add(input(existing));
        for(Path file:buffers.keySet())inputs.add(input(file));
        var overlays=new HashMap<Path,String>();buffers.forEach((p,b)->overlays.put(p,b.text));
        var fresh=new LinkedHashMap<Path,ProjectState>();
        for(Path input:inputs) {
            if(version!=revision.get())return;
            if(!Files.exists(input)&&!buffers.containsKey(input))continue;
            try {
                var project=ProjectLoader.load(input,overlays);
                var previous=projects.get(input);
                if(previous!=null && previous.sources.equals(project.sources())) {fresh.put(input,previous);diagnostics.putAll(previous.diagnostics);continue;}
                var projectDiagnostics=new LinkedHashMap<String,List<Diagnostic>>();
                var indexes=new LinkedHashMap<String,SourceIndex>();boolean syntaxValid=true;
                for(var source:project.sources()) {
                    String uri=Path.of(source.name()).toUri().toString();var parsed=source.parse();var coords=new TextCoordinates(source.text());
                    var list=projectDiagnostics.computeIfAbsent(uri,k->new ArrayList<>());
                    for(var error:parsed.diagnostics())list.add(error(coords.diagnostic(error.line(),error.column()),error.message()));
                    syntaxValid &= parsed.valid();
                    indexes.put(uri,new SourceIndex(source));
                }
                if(syntaxValid)try {ManagedEmitter.check(project.sources());}
                catch(CompilationException invalid){semanticErrors(invalid.getMessage(),project.sources(),projectDiagnostics,input);}
                fresh.put(input,new ProjectState(input,project.sources(),indexes,projectDiagnostics));diagnostics.putAll(projectDiagnostics);
            } catch(IOException | IllegalArgumentException error) {
                diagnostics.computeIfAbsent(input.toUri().toString(),k->new ArrayList<>()).add(error(new Range(new Position(0,0),new Position(0,0)),error.getMessage()));
            } catch(RuntimeException error) {log("Analysis failed for "+input+": "+error);}
        }
        if(version!=revision.get())return;
        projects.clear();projects.putAll(fresh);needsDiscovery=false;
        if(client!=null){var all=new HashSet<>(published);all.addAll(diagnostics.keySet());
            for(String uri:all){var params=new PublishDiagnosticsParams(uri,diagnostics.getOrDefault(uri,List.of()));var buffer=buffers.get(path(uri));if(buffer!=null)params.setVersion(buffer.version);client.publishDiagnostics(params);}
            published.clear();published.addAll(diagnostics.keySet());}
    }
    private void semanticErrors(String message,List<SourceFile> sources,Map<String,List<Diagnostic>> diagnostics,Path fallback) {
        for(String line:message.split("\\R")) {
            boolean located=false;
            for(var source:sources)if(line.startsWith(source.name()+":")) {
                var matcher=java.util.regex.Pattern.compile("^(\\d+):(\\d+): (.*)$").matcher(line.substring(source.name().length()+1));
                if(matcher.matches()) {var coords=new TextCoordinates(source.text());diagnostics.computeIfAbsent(Path.of(source.name()).toUri().toString(),k->new ArrayList<>()).add(error(coords.diagnostic(Integer.parseInt(matcher.group(1)),Integer.parseInt(matcher.group(2))),matcher.group(3)));located=true;break;}
            }
            if(!located)diagnostics.computeIfAbsent(fallback.toUri().toString(),k->new ArrayList<>()).add(error(new Range(new Position(0,0),new Position(0,0)),line));
        }
    }
    private static Diagnostic error(Range range,String message) {var d=new Diagnostic(range,message,DiagnosticSeverity.Error,"bhash");return d;}
    private void log(String message) {if(client!=null)client.logMessage(new MessageParams(MessageType.Warning,message));else System.err.println(message);}
    private ProjectState state(String uri) {
        // A request after an edit observes that edit, even before the debounce timer fires.
        if(pending!=null&&!pending.isDone()){pending.cancel(false);analyze(revision.get(),false);}
        Path file=path(uri);if(file==null)return null;
        return projects.values().stream().filter(p->p.indexes.containsKey(file.toUri().toString())).findFirst().orElse(null);
    }
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("bhash/projectInput")
    public CompletableFuture<String> projectInput(TextDocumentIdentifier document) {
        return submit(()->{Path file=path(document.getUri());return file==null?null:input(file).toString();});
    }
    @Override public CompletableFuture<Either<List<CompletionItem>,CompletionList>> completion(CompletionParams params) {
        return submit(()->{var project=state(params.getTextDocument().getUri());var index=project==null?null:project.indexes.get(path(params.getTextDocument().getUri()).toUri().toString());
            return Either.forLeft(index==null?List.of():index.completions(params.getPosition(),project.indexes.values()));});
    }
    @Override public CompletableFuture<Either<List<? extends Location>,List<? extends LocationLink>>> definition(DefinitionParams params) {
        return submit(()->{var project=state(params.getTextDocument().getUri());var index=project==null?null:project.indexes.get(path(params.getTextDocument().getUri()).toUri().toString());
            return Either.forLeft(index==null?List.of():index.resolve(params.getPosition(),project.indexes.values()).stream().map(SourceIndex.Symbol::location).toList());});
    }
    @Override public CompletableFuture<Hover> hover(HoverParams params) {
        return submit(()->{var project=state(params.getTextDocument().getUri());var index=project==null?null:project.indexes.get(path(params.getTextDocument().getUri()).toUri().toString());if(index==null)return null;
            var symbols=index.resolve(params.getPosition(),project.indexes.values());if(symbols.isEmpty())return null;
            String text=String.join("\n\n",symbols.stream().map(s->"```bhash\n"+(s.detail().isBlank()?s.name():s.detail())+"\n```\n\n"+s.qualified()).distinct().toList());
            return new Hover(new MarkupContent(MarkupKind.MARKDOWN,text));});
    }
    @Override public CompletableFuture<List<Either<SymbolInformation,DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return submit(()->{var project=state(params.getTextDocument().getUri());var index=project==null?null:project.indexes.get(path(params.getTextDocument().getUri()).toUri().toString());
            return index==null?List.of():index.outline.stream().map(s->Either.<SymbolInformation,DocumentSymbol>forRight(s)).toList();});
    }
    @Override public CompletableFuture<Either<List<? extends SymbolInformation>,List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        return submit(()->{var symbols=new ArrayList<SymbolInformation>();var seen=new HashSet<String>();
            for(var project:projects.values())for(var index:project.indexes.values())for(var s:index.symbols)if(s.global() && s.qualified().contains(SourceIndex.key(params.getQuery())) && seen.add(s.uri()+s.selection()))
                symbols.add(new SymbolInformation(s.name(),s.kind(),s.location(),index.pkg));
            return Either.forLeft(symbols);});
    }
}
