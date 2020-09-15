package edu.uiowa.kind2.lsp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * LanguageServer
 */
public class LanguageServer implements org.eclipse.lsp4j.services.LanguageServer {

  private Process process;
  private String[] options;

  public LanguageServer() {
    process = null;
    options = null;
  }

  Process createKind2Process(String[] options) throws IOException
  {
    List<String> args = new ArrayList<>();
    args.add("kind2");
    args.addAll(Arrays.asList(options));

    ProcessBuilder builder = new ProcessBuilder(args);
    builder.redirectErrorStream(true);
    return builder.start();
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        options = (String[]) params.getInitializationOptions();
        process = createKind2Process(options);
        return new InitializeResult(new ServerCapabilities());
      } catch (IOException e) {
        e.printStackTrace();
        throw new ResponseErrorException(
            new ResponseError(ResponseErrorCode.InternalError, e.getMessage(), e));
      }
    });
  }

  @JsonRequest
  public CompletableFuture<String> check(String program) {
    return CompletableFutures.computeAsync(cancelToken -> {
      try {
        process.getOutputStream().write(program.getBytes());
        // EOF
        process.getOutputStream().close();

        while (process.isAlive()) {
          // if the request is cancelled, terminate current kind2 process and start a new one
          if (cancelToken.isCanceled()) {
            process.destroy();
            wait(100);
            // forcibly kill kind2 if it refuses to terminate gracefully
            if (process.isAlive()) {
              process.destroyForcibly();
            }
            process = createKind2Process(options);
            // let the LSP SDK know that a cancellation exception occurred
            cancelToken.checkCanceled();
          }
        }

        return  new String(process.getInputStream().readAllBytes());
      } catch (IOException | InterruptedException e) {
        e.printStackTrace();
        throw new ResponseErrorException(
            new ResponseError(ResponseErrorCode.InternalError, e.getMessage(), e));
      }
    });
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        process.destroy();
        process.waitFor();
        return process.exitValue();
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
    // TODO Auto-generated method stub
    return new TextDocumentService() {

      @Override
      public void didOpen(DidOpenTextDocumentParams params) {
        // TODO Auto-generated method stub

      }

      @Override
      public void didChange(DidChangeTextDocumentParams params) {
        // TODO Auto-generated method stub

      }

      @Override
      public void didClose(DidCloseTextDocumentParams params) {
        // TODO Auto-generated method stub

      }

      @Override
      public void didSave(DidSaveTextDocumentParams params) {
        // TODO Auto-generated method stub

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
}
