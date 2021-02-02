package edu.uiowa.kind2.lsp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import edu.uiowa.cs.clc.kind2.api.Kind2Api;
import edu.uiowa.cs.clc.kind2.results.AstInfo;
import edu.uiowa.cs.clc.kind2.results.Log;
import edu.uiowa.cs.clc.kind2.results.Property;
import edu.uiowa.cs.clc.kind2.results.Result;

/**
 * LanguageServer
 */
public class LanguageServer
    implements org.eclipse.lsp4j.services.LanguageServer, LanguageClientAware {

  private Process process;
  private List<String> options;
  private LanguageClient client;
  private Map<String, String> openDocuments;
  private ExecutorService threads;
  private List<CodeLens> components;
  private Result result;

  public LanguageServer() {
    process = null;
    options = new ArrayList<>();
    client = null;
    threads = Executors.newSingleThreadExecutor();
    openDocuments = new HashMap<>();
    components = new ArrayList<>();
  }

  public String getText(String uri) throws IOException, URISyntaxException {
    if (openDocuments.containsKey(uri)) {
      return openDocuments.get(uri);
    }
    return Files.readString(Paths.get(new URI(uri)));
  }

  Process createKind2Process(String[] options) throws IOException {
    List<String> args = new ArrayList<>();
    args.add("kind2");
    if (options != null) {
      args.addAll(Arrays.asList(options));
    }

    ProcessBuilder builder = new ProcessBuilder(args);
    builder.redirectErrorStream(true);
    return builder.start();
  }

  Result callKind2(String program, String[] options) {
    Kind2Api api = new Kind2Api();
    api.setArgs(Arrays.asList(options));
    return api.execute(program);
  }

  Result callKind2(URI uri, String[] options) throws IOException, URISyntaxException {
    Kind2Api api = new Kind2Api();
    api.setArgs(Arrays.asList(options));
    return api.execute(getText(uri.toString()));
  }

  Diagnostic logToDiagnostic(Log log) {
    DiagnosticSeverity ds;
    switch (log.getLevel()) {
      case error:
      case fatal:
        ds = DiagnosticSeverity.Error;
        break;
      case info:
      case note:
        ds = DiagnosticSeverity.Information;
        break;
      case warn:
        ds = DiagnosticSeverity.Warning;
        break;
      case off:
      case trace:
      case debug:
      default:
        ds = null;
        break;
    }

    if (log.getLine() != null) {
      return new Diagnostic(
          new Range(
              new Position(Integer.parseInt(log.getLine()) - 1, Integer.parseInt(log.getColumn())),
              new Position(Integer.parseInt(log.getLine()), 0)),
          log.getValue(), ds, "Kind 2: " + log.getSource());
    }
    return new Diagnostic(new Range(new Position(0, 0), new Position(0, 0)), log.getValue(), ds,
        "Kind 2: " + log.getSource());
  }

  /**
   * Call Kind 2 to parse a lustre file and check for syntax errors.
   *
   * @param uri the uri of the lustre file to parse.
   */
  void parse(String uri) {
    // client.logMessage(new MessageParams(MessageType.Info, "parsing..."));
    // client.logMessage(new MessageParams(MessageType.Info, "Here1"));
    Result result = null;
    components = new ArrayList<>();

    // ignore exceptions from syntax errors
    try {
      result = callKind2(getText(uri),
          new String[] {"-json", "--no_tc", "false", "--only_parse", "true", "--lsp", "true"});
    } catch (IOException | URISyntaxException e) {
      throw new ResponseErrorException(
          new ResponseError(ResponseErrorCode.ParseError, e.getMessage(), e));
    }
    // client.logMessage(new MessageParams(MessageType.Info, "2"));

    List<Diagnostic> diagnostics = new ArrayList<>();

    for (Log log : result.getKind2Logs()) {
      diagnostics.add(logToDiagnostic(log));
    }

    for (AstInfo info : result.getAstInfos()) {
      ArrayList<Object> args = new ArrayList<Object>();
      args.add(uri);
      args.add(info.getName());
      Position position =
          new Position(Integer.parseInt(info.getLine()) - 1, Integer.parseInt(info.getColumn()));
      components.add(new CodeLens(new Range(position, position),
          new Command(info.getName(), "kind2/check", args), null));
    }

    // client.logMessage(new MessageParams(MessageType.Info, "Here3"));

    client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    // client.logMessage(new MessageParams(MessageType.Info, "Here4"));
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    return CompletableFuture.supplyAsync(() -> {
      client.logMessage(new MessageParams(MessageType.Info, "Initializing server..."));
      if (params.getInitializationOptions() != null) {
        options.addAll(Arrays.asList((String[]) params.getInitializationOptions()));
      }
      ServerCapabilities sCapabilities = new ServerCapabilities();
      TextDocumentSyncOptions syncOptions = new TextDocumentSyncOptions();
      syncOptions.setOpenClose(true);
      syncOptions.setChange(TextDocumentSyncKind.Full);
      syncOptions.setSave(new SaveOptions(true));
      sCapabilities.setTextDocumentSync(syncOptions);
      sCapabilities.setCodeLensProvider(new CodeLensOptions(true));
      return new InitializeResult(sCapabilities);
    });
  }

  @Override
  public void initialized(InitializedParams params) {
    client.logMessage(new MessageParams(MessageType.Info, "Server initialized."));
  }

  /**
   * @return the components
   */
  @JsonRequest(value = "kind2/getComponents", useSegment = false)
  public CompletableFuture<List<CodeLens>> getComponents() {
    return CompletableFuture.supplyAsync(() -> {
      return components;
    });
  }

  @JsonRequest(value = "kind2/check2", useSegment = false)
  public CompletableFuture<Set<Property>> check2(String uri, String name) {
    return CompletableFuture.supplyAsync(() -> {
      client.logMessage(
          new MessageParams(MessageType.Info, "Checking component " + name + " in " + uri + "..."));

      String[] newOptions = options.toArray(new String[options.size() + 3]);

      newOptions[newOptions.length - 3] = "-json";
      newOptions[newOptions.length - 2] = "--lus_main";
      newOptions[newOptions.length - 1] = name;

      try {
        result = callKind2(new URI(uri), newOptions);
      } catch (Exception e) {
        throw new ResponseErrorException(
            new ResponseError(ResponseErrorCode.InternalError, e.getMessage(), e));
      }
      Set<Property> properties = new HashSet<>();
      properties.addAll(result.getValidProperties());
      properties.addAll(result.getFalsifiedProperties());
      properties.addAll(result.getUnknownProperties());
      return properties;
    });
  }

  @JsonNotification(value = "kind2/check", useSegment = false)
  public void check(String uri, String name) {
    threads.submit(() -> {
      try {
        client.logMessage(new MessageParams(MessageType.Info,
            "Checking component " + name + " in " + uri + "..."));

        String[] newOptions = options.toArray(new String[options.size() + 4]);

        newOptions[newOptions.length - 4] = "--color";
        newOptions[newOptions.length - 3] = "false";
        newOptions[newOptions.length - 2] = "--lus_main";
        newOptions[newOptions.length - 1] = name;
        process = createKind2Process(newOptions);
        process.getOutputStream().write(getText(uri).getBytes());
        process.getOutputStream().close();

        List<Diagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(new Diagnostic(new Range(new Position(0, 0), new Position(0, 0)),
            new String(process.getInputStream().readAllBytes()), DiagnosticSeverity.Information,
            "Kind 2"));

        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
      } catch (IOException | URISyntaxException e) {
        e.printStackTrace();
        throw new ResponseErrorException(
            new ResponseError(ResponseErrorCode.InternalError, e.getMessage(), e));
      }
    });
  }

  @JsonNotification(value = "kind2/counterExample", useSegment = false)
  public CompletableFuture<String> counterExample(String name) {
    return CompletableFuture.supplyAsync(() -> {
      if (result == null) {
        return null;
      }
      for (var prop : result.getFalsifiedProperties()) {
        if (prop.getJsonName() == name) {
          return prop.getCounterExample().getJson();
        }
      }
      return null;
    });
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (process != null && process.isAlive()) {
          process.destroy();
          wait(100);
          // forcibly kill kind2 if it refuses to terminate gracefully
          if (process.isAlive()) {
            process.destroyForcibly();
          }
          return process.exitValue();
        }
        return 0;
      } catch (InterruptedException e) {
        e.printStackTrace();
        throw new ResponseErrorException(
            new ResponseError(ResponseErrorCode.InternalError, e.getMessage(), e));
      }
    });
  }

  @Override
  public void exit() {
    // If kind2 is still alive after shutdown, destroy it forcibly and return an error code
    if (process.isAlive()) {
      process.destroyForcibly();
      // TODO: exit with an error code
    }
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return new TextDocumentService() {
      @Override
      public void didOpen(DidOpenTextDocumentParams params) {
        openDocuments.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
        threads.submit(() -> parse(params.getTextDocument().getUri()));
      }

      @Override
      public void didChange(DidChangeTextDocumentParams params) {
        openDocuments.replace(params.getTextDocument().getUri(),
            params.getContentChanges().get(0).getText());
        threads.submit(() -> parse(params.getTextDocument().getUri()));
      }

      @Override
      public void didClose(DidCloseTextDocumentParams params) {
        openDocuments.remove(params.getTextDocument().getUri());
      }

      @Override
      public void didSave(DidSaveTextDocumentParams params) {
        openDocuments.replace(params.getTextDocument().getUri(), params.getText());
        threads.submit(() -> parse(params.getTextDocument().getUri()));
      }

      @Override
      public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return CompletableFuture.supplyAsync(() -> {
          return components;
        });
      }
    };
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return new WorkspaceService() {
      @Override
      public void didChangeConfiguration(DidChangeConfigurationParams params) {
      }

      @Override
      public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
      }
    };
  }

  @Override
  public void connect(LanguageClient client) {
    this.client = client;
  }
}
