package edu.uiowa.kind2.lsp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
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
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import edu.uiowa.cs.clc.kind2.Kind2Exception;
import edu.uiowa.cs.clc.kind2.api.Kind2Api;
import edu.uiowa.cs.clc.kind2.results.AstInfo;
import edu.uiowa.cs.clc.kind2.results.Log;
import edu.uiowa.cs.clc.kind2.results.Result;

/**
 * LanguageServer
 */
public class Kind2LanguageServer implements org.eclipse.lsp4j.services.LanguageServer, LanguageClientAware {

  private List<String> options;
  private Kind2LanguageClient client;
  private Map<String, String> openDocuments;
  private Map<String, Result> parseResults;
  private Map<String, Result> analysisResults;

  public Kind2LanguageServer() {
    options = new ArrayList<>();
    client = null;
    openDocuments = new HashMap<>();
    parseResults = new HashMap<>();
    analysisResults = new HashMap<>();
  }

  public String getText(String uri) throws IOException, URISyntaxException {
    if (openDocuments.containsKey(uri)) {
      return openDocuments.get(uri);
    }
    return Files.readString(Paths.get(new URI(uri)));
  }

  Result callKind2(String uri, List<String> options) throws Kind2Exception, IOException, URISyntaxException {
    Kind2Api api = new Kind2Api();
    options.add("--include_dir");
    options.add(uri.substring(7, uri.lastIndexOf("/")));
    api.setArgs(options);
    return api.execute(getText(uri));
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
          new Range(new Position(Integer.parseInt(log.getLine()) - 1, Integer.parseInt(log.getColumn())),
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
    client.logMessage(new MessageParams(MessageType.Info, "parsing..."));

    // ignore exceptions from syntax errors
    try {
      ArrayList<String> newOptions = new ArrayList<>();
      Collections.copy(options, newOptions);
      Collections.addAll(newOptions, "-json", "--no_tc", "false", "--only_parse", "true", "--lsp", "true");
      parseResults.put(uri, callKind2(uri, newOptions));
    } catch (Kind2Exception | IOException | URISyntaxException e) {
      throw new ResponseErrorException(new ResponseError(ResponseErrorCode.ParseError, e.getMessage(), e));
    }

    List<Diagnostic> diagnostics = new ArrayList<>();

    for (Log log : parseResults.get(uri).getKind2Logs()) {
      diagnostics.add(logToDiagnostic(log));
    }

    client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    client.logMessage(new MessageParams(MessageType.Info, "parsing done."));
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
      sCapabilities.setDocumentSymbolProvider(true);
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
  public CompletableFuture<List<String>> getComponents(String uri) {
    return CompletableFuture.supplyAsync(() -> {
      List<String> components = new ArrayList<>();
      if (parseResults.containsKey(uri)) {
        String path = uri.substring(7);
        for (AstInfo info : parseResults.get(uri).getAstInfos()) {
          if (info.getFile().equals("")) {
            components.add(info.getJson().replace("\"file\": \"\"", "\"file\": \"" + path + "\""));
          }
        }
      }
      return components;
    });
  }

  @JsonRequest(value = "kind2/check", useSegment = false)
  public CompletableFuture<Set<String>> check(String uri, String name) {
    return CompletableFuture.supplyAsync(() -> {
      client.logMessage(new MessageParams(MessageType.Info, "Checking component " + name + " in " + uri + "..."));

      ArrayList<String> newOptions = new ArrayList<String>();

      Collections.copy(options, newOptions);
      Collections.addAll(newOptions, "-json", "--lus_main", name);

      try {
        analysisResults.put(uri, callKind2(uri, newOptions));
      } catch (Exception e) {
        throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InternalError, e.getMessage(), e));
      }

      String path = uri.substring(7);

      Set<String> properties = new HashSet<>();

      properties.addAll(analysisResults.get(uri).getValidProperties().stream()
          .map(p -> p.getJson().replace("\"file\": \"\"", "\"file\": \"" + path + "\"")).collect(Collectors.toSet()));
      properties.addAll(analysisResults.get(uri).getFalsifiedProperties().stream()
          .map(p -> p.getJson().replace("\"file\": \"\"", "\"file\": \"" + path + "\"")).collect(Collectors.toSet()));
      properties.addAll(analysisResults.get(uri).getUnknownProperties().stream()
          .map(p -> p.getJson().replace("\"file\": \"\"", "\"file\": \"" + path + "\"")).collect(Collectors.toSet()));

      return properties;
    });
  }

  @JsonRequest(value = "kind2/counterExample", useSegment = false)
  public CompletableFuture<String> counterExample(String uri, String name) {
    return CompletableFuture.supplyAsync(() -> {
      if (!analysisResults.containsKey(uri)) {
        return null;
      }
      for (var prop : analysisResults.get(uri).getFalsifiedProperties()) {
        if (prop.getJsonName().equals(name)) {
          return prop.getCounterExample().getJson();
        }
      }
      return null;
    });
  }

  @JsonRequest(value = "kind2/interpret", useSegment = false)
  public CompletableFuture<String> interpret(String uri, String main, String json) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return new Kind2Api().interpret(new URI(uri), main, json);
      } catch (URISyntaxException e) {
        throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InternalError, e.getMessage(), e));
      }
    });
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    return CompletableFuture.supplyAsync(() -> {
      return 0;
    });
  }

  @Override
  public void exit() {
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return new TextDocumentService() {
      @Override
      public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.put(uri, params.getTextDocument().getText());
        parse(uri);
        client.updateComponents(uri);
      }

      @Override
      public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.replace(uri, params.getContentChanges().get(0).getText());
        parse(uri);
        client.updateComponents(uri);
      }

      @Override
      public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.remove(params.getTextDocument().getUri());
        parseResults.remove(uri);
        analysisResults.remove(uri);
        client.updateComponents(uri);
      }

      @Override
      public void didSave(DidSaveTextDocumentParams params) {
        openDocuments.replace(params.getTextDocument().getUri(), params.getText());
        parse(params.getTextDocument().getUri());
      }

      @Override
      public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
          DocumentSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
          String uri = params.getTextDocument().getUri();
          List<Either<SymbolInformation, DocumentSymbol>> symbols = new ArrayList<>();
          if (parseResults.containsKey(uri)) {
            for (AstInfo info : parseResults.get(uri).getAstInfos()) {
              Position startPos = new Position(Integer.parseInt(info.getStartLine()) - 1,
                  Integer.parseInt(info.getStartColumn()));
              Position endPos = new Position(Integer.parseInt(info.getEndLine()) - 1,
                  Integer.parseInt(info.getEndColumn()));
              Range range = new Range(startPos, endPos);
              symbols.add(Either.forRight(new DocumentSymbol(info.getName(), SymbolKind.Function, range, range)));
            }
          }
          return symbols;
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
    this.client = (Kind2LanguageClient) client;
  }
}
