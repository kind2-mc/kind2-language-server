package edu.uiowa.kind2.lsp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DefinitionParams;
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
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
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
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
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
import edu.uiowa.cs.clc.kind2.api.IProgressMonitor;
import edu.uiowa.cs.clc.kind2.api.Kind2Api;
import edu.uiowa.cs.clc.kind2.api.LogLevel;
import edu.uiowa.cs.clc.kind2.api.Module;
import edu.uiowa.cs.clc.kind2.api.SolverOption;
import edu.uiowa.cs.clc.kind2.results.Analysis;
import edu.uiowa.cs.clc.kind2.results.AstInfo;
import edu.uiowa.cs.clc.kind2.results.ConstDeclInfo;
import edu.uiowa.cs.clc.kind2.results.ContractInfo;
import edu.uiowa.cs.clc.kind2.results.FunctionInfo;
import edu.uiowa.cs.clc.kind2.results.Log;
import edu.uiowa.cs.clc.kind2.results.NodeInfo;
import edu.uiowa.cs.clc.kind2.results.NodeResult;
import edu.uiowa.cs.clc.kind2.results.Property;
import edu.uiowa.cs.clc.kind2.results.Result;
import edu.uiowa.cs.clc.kind2.results.TypeDeclInfo;

/**
 * LanguageServer
 */
public class Kind2LanguageServer
    implements org.eclipse.lsp4j.services.LanguageServer, LanguageClientAware {

  private Kind2LanguageClient client;
  private Map<String, String> openDocuments;
  private Map<String, Result> parseResults;
  private Map<String, Map<String, NodeResult>> analysisResults;
  private String workingDirectory;

  public Kind2LanguageServer() {
    client = null;
    openDocuments = new HashMap<>();
    parseResults = new HashMap<>();
    analysisResults = new HashMap<>();
    Result.setOpeningSymbols("");
    Result.setClosingSymbols("");
    workingDirectory = null;
  }

  public String getText(String uri) throws IOException, URISyntaxException {
    if (openDocuments.containsKey(uri)) {
      return openDocuments.get(uri);
    }
    return Files.readString(Paths.get(new URI(uri)));
  }

  void checkLog(Result result) throws ResponseErrorException {
    for (Log log : result.getKind2Logs()) {
      if (log.getLevel() == edu.uiowa.cs.clc.kind2.results.LogLevel.fatal
          || log.getLevel() == edu.uiowa.cs.clc.kind2.results.LogLevel.error) {
        throw new ResponseErrorException(
            new ResponseError(ResponseErrorCode.InternalError,
                "An error occurred while checking the component:\n"
                    + log.getValue(),
                null));
      }
    }
  }

  Diagnostic logToDiagnostic(Log log) {
    DiagnosticSeverity ds;
    switch (log.getLevel()) {
    case off:
    case fatal:
    case error:
      ds = DiagnosticSeverity.Error;
      break;
    case warn:
      ds = DiagnosticSeverity.Warning;
      break;
    case note:
    case info:
    case debug:
    case trace:
      ds = DiagnosticSeverity.Information;
      break;
    default:
      ds = null;
      break;
    }

    if (ds == null) {
      return null;
    }

    if (log.getLine() != null) {
      return new Diagnostic(
          new Range(
              new Position(Integer.parseInt(log.getLine()) - 1,
                  Integer.parseInt(log.getColumn()) - 1),
              new Position(Integer.parseInt(log.getLine()), 0)),
          log.getValue(), ds, "Kind 2: " + log.getSource());
    }
    return new Diagnostic(new Range(new Position(0, 0), new Position(0, 0)),
        log.getValue(), ds, "Kind 2: " + log.getSource());
  }

  /**
   * Compute a relative filepath from the working directory and file URI,
   * both as absolute filepaths.
   * 
   * @param workingDirectory the current working directory
   * @param uri the uri of the lustre file
   */
  private String computeRelativeFilepath(String workingDirectory, String uri) {
    return Paths.get(URI.create(workingDirectory)).relativize(
                 Paths.get(URI.create(uri)))
                 .toString();
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
      if (workingDirectory == null) {
        workingDirectory = client.workspaceFolders().get().get(0).getUri();
      }
      Kind2Api api = getPresetKind2Api();
      api.setOldFrontend(false);
      api.setOnlyParse(true);
      api.setLsp(true);
      String filepath = computeRelativeFilepath(workingDirectory, uri);
      api.setFakeFilepath(filepath);
      api.includeDir(Paths.get(new URI(uri)).getParent().toString());
      parseResults.put(uri, api.execute(getText(uri)));
    } catch (Kind2Exception | URISyntaxException | IOException
        | InterruptedException | ExecutionException e) {
      throw new ResponseErrorException(
          new ResponseError(ResponseErrorCode.ParseError, e.getMessage(), e));
    }

    List<Diagnostic> diagnostics = new ArrayList<>();

    for (Log log : parseResults.get(uri).getAllKind2Logs()) {
      Diagnostic diagnostic = logToDiagnostic(log);
      if (diagnostic != null) {
        diagnostics.add(diagnostic);
      }
    }

    client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    client.logMessage(new MessageParams(MessageType.Info, "parsing done."));
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(
      InitializeParams params) {
    return CompletableFuture.supplyAsync(() -> {
      client.logMessage(
          new MessageParams(MessageType.Info, "Initializing server..."));
      ServerCapabilities sCapabilities = new ServerCapabilities();
      TextDocumentSyncOptions syncOptions = new TextDocumentSyncOptions();
      syncOptions.setOpenClose(true);
      syncOptions.setChange(TextDocumentSyncKind.Full);
      syncOptions.setSave(new SaveOptions(true));
      sCapabilities.setTextDocumentSync(syncOptions);
      sCapabilities.setDocumentSymbolProvider(true);
      sCapabilities.setDefinitionProvider(true);
      return new InitializeResult(sCapabilities);
    });
  }

  @Override
  public void initialized(InitializedParams params) {
    client
        .logMessage(new MessageParams(MessageType.Info, "Server initialized."));
  }

  private String replacePathWithUri(String json, String mainUri, String path)
      throws URISyntaxException {
    String uri = Paths.get(new URI(mainUri)).getParent().resolve(path)
        .normalize().toUri().toString();
    if (json.contains("\"file\":")) {
      int l = json.indexOf("\"file\":");
      int r = json.indexOf('\"', l + 9) + 1;
      if (json.charAt(r) == ',') {
        r += 1;
      }
      json = json.replace(json.substring(l, r), "");
    }
    return json.substring(0, json.length() - 2) + ",\"file\": \"" + uri + "\"}";
  }

  /**
   * @return the components
   */
  @JsonRequest(value = "kind2/getComponents", useSegment = false)
  public CompletableFuture<List<String>> getComponents(String uri) {
    return CompletableFuture.supplyAsync(() -> {
      List<String> components = new ArrayList<>();
      if (parseResults.containsKey(uri)) {
        try {
          for (AstInfo info : parseResults.get(uri).getAstInfos()) {
            if (info instanceof NodeInfo && !((NodeInfo) info).isImported()
                || info instanceof FunctionInfo
                    && !((FunctionInfo) info).isImported()) {
              components.add(replacePathWithUri(info.getJson(), uri,
                  info.getFile() == null ? new URI(uri).getPath()
                      : info.getFile()));
            }
          }
        } catch (URISyntaxException e) {
          throw new ResponseErrorException(new ResponseError(
              ResponseErrorCode.ParseError, e.getMessage(), e));
        }
      }
      return components;
    });
  }

  @JsonRequest(value = "kind2/check", useSegment = false)
  public CompletableFuture<List<String>> check(String uri, String name) {
    return CompletableFutures.computeAsync(cancelToken -> {
      client.logMessage(new MessageParams(MessageType.Info,
          "Checking component " + name + " in " + uri + "..."));
      analysisResults.get(uri).remove(name);
      Result result = new Result();
      IProgressMonitor monitor = new IProgressMonitor() {
        @Override
        public boolean isCanceled() {
          return cancelToken == null ? false : cancelToken.isCanceled();
        }

        @Override
        public void done() {
        }
      };

      try {
        if (workingDirectory == null) {
          workingDirectory = client.workspaceFolders().get().get(0).getUri();
        }
        Kind2Api api = getCheckKind2Api(name);
        api.includeDir(Paths.get(new URI(uri)).getParent().toString());
        String filepath = computeRelativeFilepath(workingDirectory, uri);
        api.setFakeFilepath(filepath);
        api.execute(getText(uri), 
                            result, 
                            monitor);
      } catch (Kind2Exception | IOException | URISyntaxException
          | InterruptedException | ExecutionException e) {
        throw new ResponseErrorException(new ResponseError(
            ResponseErrorCode.InternalError, e.getMessage(), e));
      }

      if (cancelToken.isCanceled()) {
        client.logMessage(
            new MessageParams(MessageType.Info, "Check cancelled."));
        // Throw an exception for the launcher to handle.
        cancelToken.checkCanceled();
      }

      for (Map.Entry<String, NodeResult> entry : result.getResultMap()
          .entrySet()) {
        analysisResults.get(uri).put(entry.getKey(), entry.getValue());
      }

      List<Diagnostic> diagnostics = new ArrayList<>();
      for (Log log : result.getAllKind2Logs()) {
        Diagnostic diagnostic = logToDiagnostic(log);
        if (diagnostic != null) {
          diagnostics.add(diagnostic);
        }
      }
      client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));

      checkLog(result);

      List<String> nodeResults = new ArrayList<>();

      for (Map.Entry<String, NodeResult> entry : result.getResultMap()
          .entrySet()) {
        List<String> analyses = new ArrayList<>();
        for (Analysis analysis : entry.getValue().getAnalyses()) {
          String json = analysis.getJson();
          json = json.substring(0, json.length() - 2) + ",\"properties\": ";
          List<String> properties = analysis.getProperties().stream().map(p -> {
            try {
              return replacePathWithUri(p.getJson(), uri,
                  p.getFile() == null ? new URI(uri).getPath() : p.getFile());
            } catch (URISyntaxException e) {
              throw new ResponseErrorException(new ResponseError(
                  ResponseErrorCode.ParseError, e.getMessage(), e));
            }
          }).collect(Collectors.toList());
          json = json + properties.toString() + '}';
          analyses.add(json);
        }
        String json = "{\"name\": \"" + entry.getKey() + "\",\"analyses\": "
            + analyses.toString() + "}";
        nodeResults.add(json);
      }

      return nodeResults;
    });
  }

  @JsonRequest(value = "kind2/counterExample", useSegment = false)
  public CompletableFuture<String> counterExample(String uri, String component,
      List<String> abs, List<String> concrete, String property) {
    return CompletableFuture.supplyAsync(() -> {
      if (!analysisResults.containsKey(uri)) {
        return null;
      }
      if (!analysisResults.get(uri).containsKey(component)) {
        return null;
      }
      for (Analysis analysis : analysisResults.get(uri).get(component)
          .getAnalyses()) {
        if (analysis.getAbstractNodes().equals(abs)
            && analysis.getConcreteNodes().equals(concrete)) { 
          for (Property prop : analysis.getFalsifiedProperties()) {
            if (prop.getJsonName().equals(property)) {
              return prop.getCounterExample().getJson();
            }
          }
          for (Property prop : analysis.getReachableProperties()) {
            if (prop.getJsonName().equals(property)) {
              return prop.getExampleTrace().getJson();
            }
          }
        }
      }
      return null;
    });
  }

  private SolverOption stringToSolver(String solver) {
    switch (solver.toUpperCase()) {
    case "BITWUZLA":
      return SolverOption.BITWUZLA;
    case "CVC5":
      return SolverOption.CVC5;
    case "MATHSAT":
      return SolverOption.MATHSAT;
    case "SMTINTERPOL":
      return SolverOption.SMTINTERPOL;
    case "YICES":
      return SolverOption.YICES;
    case "YICES2":
      return SolverOption.YICES2;
    case "Z3":
      return SolverOption.Z3;
    default:
      return null;
    }
  }

  private Module stringToModule(String level) {
    switch (level.toUpperCase()) {
    case "IC3":
      return Module.IC3;
    case "IC3QE":
      return Module.IC3QE;
    case "IC3IA":
      return Module.IC3IA;
    case "BMC":
      return Module.BMC;
    case "IND":
      return Module.IND;
    case "IND2":
      return Module.IND2;
    case "INVGEN":
      return Module.INVGEN;
    case "INVGENOS":
      return Module.INVGENOS;
    case "INVGENINT":
      return Module.INVGENINT;
    case "INVGENINTOS":
      return Module.INVGENINTOS;
    case "INVGENMACH":
      return Module.INVGENMACH;
    case "INVGENMACHOS":
      return Module.INVGENMACHOS;
    case "INVGENREAL":
      return Module.INVGENREAL;
    case "INVGENREALOS":
      return Module.INVGENREALOS;
    case "C2I":
      return Module.C2I;
    case "interpreter":
      return Module.interpreter;
    case "MCS":
      return Module.MCS;
    case "CONTRACTCK":
      return Module.CONTRACTCK;
    default:
      throw new IllegalArgumentException("Error: Unknown module.");
    }
  }

  private LogLevel stringToLevel(String level) {
    switch (level.toUpperCase()) {
    case "OFF":
      return LogLevel.OFF;
    case "FATAL":
      return LogLevel.FATAL;
    case "ERROR":
      return LogLevel.ERROR;
    case "WARN":
      return LogLevel.WARN;
    case "NOTE":
      return LogLevel.NOTE;
    case "INFO":
      return LogLevel.INFO;
    case "DEBUG":
      return LogLevel.DEBUG;
    case "TRACE":
      return LogLevel.TRACE;
    default:
      throw new IllegalArgumentException("Error: Unknown log level.");
    }
  }

  private void setSmtSolverPaths(Kind2Api api,
      JsonObject smtConfigs) throws InterruptedException, ExecutionException {
    if (!smtConfigs.get("bitwuzla_bin").getAsString()
        .equals("bitwuzla")) {
      api.setBitwuzlaBin(smtConfigs.get("bitwuzla_bin").getAsString());
    }
    if (!smtConfigs.get("cvc5_bin").getAsString().equals("cvc5")) {
      api.setcvc5Bin(smtConfigs.get("cvc5_bin").getAsString());
    }
    if (!smtConfigs.get("mathsat_bin").getAsString().equals("mathsat")) {
      api.setMathSATBin(smtConfigs.get("mathsat_bin").getAsString());
    }
    if (!smtConfigs.get("smtinterpol_jar").getAsString().equals("smtinterpol.jar")) {
      api.setSmtInterpolJar(smtConfigs.get("smtinterpol_jar").getAsString());
    }
    if (!smtConfigs.get("yices_bin").getAsString().equals("yices")) {
      api.setYicesBin(smtConfigs.get("yices_bin").getAsString());
    }
    if (!smtConfigs.get("yices2_bin").getAsString().equals("yices-smt2")) {
      api.setYices2Bin(smtConfigs.get("yices2_bin").getAsString());
    }
    if (smtConfigs.get("z3_bin").getAsString().equals("")) {
      api.setZ3Bin(client.getDefaultZ3Path().get());
    } else if (!smtConfigs.get("z3_bin").getAsString().equals("z3")) {
      api.setZ3Bin(smtConfigs.get("z3_bin").getAsString());
    }
  }

  public Kind2Api getPresetKind2Api()
      throws InterruptedException, ExecutionException {
    ConfigurationItem kind2Options = new ConfigurationItem();
    kind2Options.setSection("kind2");
    JsonObject configs = (JsonObject) this.client
        .configuration(new ConfigurationParams(Arrays.asList(kind2Options)))
        .get().get(0);
    if (configs.get("kind2_path").getAsString().equals("")) {
      Kind2Api.KIND2 = client.getDefaultKind2Path().get();
    } else {
      Kind2Api.KIND2 = configs.get("kind2_path").getAsString();
    }
    Kind2Api api = new Kind2Api();
    JsonObject smtConfigs = configs.get("smt").getAsJsonObject();
    SolverOption solver = stringToSolver(
        smtConfigs.get("smt_solver").getAsString());
    if (solver != null) {
      api.setSmtSolver(solver);
    }
    setSmtSolverPaths(api, smtConfigs);
    if (!configs.get("log_level").getAsString().equals("note")) {
      api.setLogLevel(stringToLevel(configs.get("log_level").getAsString()));
    }
    return api;
  }

  public Kind2Api getCheckKind2Api(String name)
      throws InterruptedException, ExecutionException {
    ConfigurationItem kind2Options = new ConfigurationItem();
    kind2Options.setSection("kind2");
    JsonObject configs = (JsonObject) this.client
        .configuration(new ConfigurationParams(Arrays.asList(kind2Options)))
        .get().get(0);
    Kind2Api api = getPresetKind2Api();
    if (!configs.get("smt").getAsJsonObject().get("check_sat_assume")
        .getAsBoolean()) {
      api.setCheckSatAssume(false);
    }
    if (configs.get("ind").getAsJsonObject().get("ind_print_cex")
        .getAsBoolean()) {
      api.setIndPrintCex(true);
    }
    if (configs.get("test").getAsJsonObject().get("testgen").getAsBoolean()) {
      api.setTestgen(true);
    }
    if (configs.get("contracts").getAsJsonObject().get("compositional")
        .getAsBoolean()) {
      api.setCompositional(true);
    }
    if (configs.get("certif").getAsJsonObject().get("certif").getAsBoolean()) {
      api.setIndPrintCex(true);
    }
    if (!configs.get("output_dir").getAsString().equals("")) {
      api.outputDir(configs.get("output_dir").getAsString());
    }
    if (configs.get("dump_cex").getAsBoolean()) {
      api.setDumpCex(true);
    }
    if (configs.get("timeout").getAsFloat() != 0) {
      api.setTimeout(configs.get("timeout").getAsFloat());
    }
    for (JsonElement option : configs.get("modules").getAsJsonArray()) {
      api.enable(stringToModule(option.getAsString()));
    }
    if (configs.get("modular").getAsBoolean()) {
      api.setModular(true);
    }
    if (!configs.get("slice_nodes").getAsBoolean()) {
      api.setSliceNodes(false);
    }
    if (!configs.get("check_reach").getAsBoolean()) {
      api.setCheckReach(false);
    }
    if (!configs.get("check_nonvacuity").getAsBoolean()) {
      api.setCheckNonvacuity(false);
    }
    if (!configs.get("check_subproperties").getAsBoolean()) {
      api.setCheckSubproperties(false);
    }
    ArrayList<String> otherOptions = new ArrayList<>();
    for (JsonElement option : configs.get("other_options").getAsJsonArray()) {
      otherOptions.add(option.getAsString());
    }
    api.setOtherOptions(otherOptions);
    api.setLusMain(name);
    return api;
  }

  @JsonRequest(value = "kind2/interpret", useSegment = false)
  public CompletableFuture<String> interpret(String uri, String main,
      String json) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        Kind2Api api = getPresetKind2Api();
        return api.interpret(new URI(uri), main, json);
      } catch (URISyntaxException | InterruptedException
          | ExecutionException e) {
        throw new ResponseErrorException(new ResponseError(
            ResponseErrorCode.InternalError, e.getMessage(), e));
      }
    });
  }

  @JsonRequest(value = "kind2/getKind2Cmd", useSegment = false)
  public CompletableFuture<List<String>> getKind2Cmd(String uri, String main) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        Kind2Api api = getCheckKind2Api(main);
        List<String> cmd = api.getOptions();
        cmd.set(0, Kind2Api.KIND2);
        cmd.add(new URI(uri).getPath());
        return cmd;
      } catch (InterruptedException | ExecutionException
          | URISyntaxException e) {
        throw new ResponseErrorException(new ResponseError(
            ResponseErrorCode.InternalError, e.getMessage(), e));
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
        analysisResults.put(uri, new HashMap<>());
        CompletableFuture.runAsync(() -> {
          parse(uri);
          client.updateComponents(uri);
        });
      }

      @Override
      public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.replace(uri, params.getContentChanges().get(0).getText());
        analysisResults.put(uri, new HashMap<>());
        CompletableFuture.runAsync(() -> {
          parse(uri);
          client.updateComponents(uri);
        });
      }

      @Override
      public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.remove(params.getTextDocument().getUri());
        parseResults.remove(uri);
        analysisResults.remove(uri);
        client.updateComponents(uri);
        client.publishDiagnostics(
            new PublishDiagnosticsParams(uri, new ArrayList<>()));
      }

      @Override
      public void didSave(DidSaveTextDocumentParams params) {
        openDocuments.replace(params.getTextDocument().getUri(),
            params.getText());
      }

      @Override
      public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
          DocumentSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
          String uri = params.getTextDocument().getUri();
          List<Either<SymbolInformation, DocumentSymbol>> symbols = new ArrayList<>();
          if (parseResults.containsKey(uri)) {
            for (AstInfo info : parseResults.get(uri).getAstInfos()) {
              if (info.getFile() == null) {
                Position startPos = new Position(
                    Integer.parseInt(info.getStartLine()) - 1,
                    Integer.parseInt(info.getStartColumn()) - 1);
                Position endPos = new Position(
                    Integer.parseInt(info.getEndLine()) - 1,
                    Integer.parseInt(info.getEndColumn()) - 1);
                Range range = new Range(startPos, endPos);
                SymbolKind kind;
                if (info instanceof TypeDeclInfo) {
                  kind = SymbolKind.Class;
                } else if (info instanceof ConstDeclInfo) {
                  kind = SymbolKind.Constant;
                } else if (info instanceof NodeInfo) {
                  kind = SymbolKind.Method;
                } else if (info instanceof FunctionInfo) {
                  kind = SymbolKind.Function;
                } else if (info instanceof ContractInfo) {
                  kind = SymbolKind.Interface;
                } else {
                  kind = null;
                }
                symbols.add(Either.forRight(
                    new DocumentSymbol(info.getName(), kind, range, range)));
              }
            }
          }
          return symbols;
        });
      }

      private String getSymbolName(String text, Position pos) {
        List<String> lines = text.lines().collect(Collectors.toList());
        String line = lines.get(pos.getLine());
        if (pos.getCharacter() == line.length()) {
          return "";
        }
        int i = pos.getCharacter();
        while (i >= 0 && (Character.isLetterOrDigit(line.charAt(i))
            || line.charAt(i) == '_')) {
          i -= 1;
        }
        i++;
        int j = pos.getCharacter() + 1;
        while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j))
            || line.charAt(j) == '_')) {
          j += 1;
        }
        return line.substring(i, j);
      }

      @Override
      public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
          DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
          String uri = params.getTextDocument().getUri();
          String name;
          try {
            name = getSymbolName(getText(uri), params.getPosition());
          } catch (IOException | URISyntaxException e) {
            throw new ResponseErrorException(new ResponseError(
                ResponseErrorCode.ParseError, e.getMessage(), e));
          }
          List<Location> decs = new ArrayList<>();
          if (parseResults.containsKey(uri)) {
            for (AstInfo info : parseResults.get(uri).getAstInfos()) {
              if (info.getName().equals(name)) {
                Position startPos = new Position(
                    Integer.parseInt(info.getStartLine()) - 1,
                    Integer.parseInt(info.getStartColumn()) - 1);
                Position endPos = new Position(
                    Integer.parseInt(info.getEndLine()) - 1,
                    Integer.parseInt(info.getEndColumn()) - 1);
                String decUri = info.getFile() == null ? uri : info.getFile();
                Range decRange = new Range(startPos, endPos);
                decs.add(new Location(decUri, decRange));
              }
            }
          }
          return Either.forLeft(decs);
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
