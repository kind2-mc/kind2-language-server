package edu.uiowa.kind2.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.InitializeParams;
import org.junit.jupiter.api.Test;

public class LanguageServerTest {
  @Test
  void simpleTest() {
    LanguageServer s = new LanguageServer();

    try {
      InitializeParams params = new InitializeParams();
      params.setInitializationOptions(new String[] {"-json"});
      s.initialize(params).get();
      String program = new String(Files.readAllBytes(Paths.get("files/stopwatch.lus")));
      System.out.println(s.check(program).get());
      assertEquals(0, (int) s.shutdown().get());
    } catch (InterruptedException | ExecutionException | IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
}
