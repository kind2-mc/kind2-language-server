package edu.uiowa.kind2.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

public class LanguageServerTest implements LanguageClient {
  @Test
  void simpleTest() {
    LanguageServer s = new LanguageServer();

    try {
      InitializeParams params = new InitializeParams();
      params.setInitializationOptions(new String[] {"-json"});
      String program = new String(Files.readAllBytes(Paths.get("files/stopwatch.lus")));
      // System.out.println(s.check(program).get());
      //s.parse("file:///home/abdoo8080/Research/kind2-lsp/files/stopwatch.lus");
      assertEquals(0, (int) s.shutdown().get());
    } catch (InterruptedException | ExecutionException | IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public void telemetryEvent(Object object) {
    // TODO Auto-generated method stub

  }

  @Override
  public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
    // TODO Auto-generated method stub

  }

  @Override
  public void showMessage(MessageParams messageParams) {
    // TODO Auto-generated method stub

  }

  @Override
  public CompletableFuture<MessageActionItem> showMessageRequest(
      ShowMessageRequestParams requestParams) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void logMessage(MessageParams message) {
    // TODO Auto-generated method stub

  }
}
