package edu.uiowa.kind2.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;

public class Main {
  public static void main(String[] args) {
    org.eclipse.lsp4j.services.LanguageServer server = new LanguageServer();
    Launcher<LanguageClient> launcher =
        LSPLauncher.createServerLauncher(server, System.in, System.out);

    if (server instanceof LanguageClientAware) {
      LanguageClient client = launcher.getRemoteProxy();
      ((LanguageClientAware) server).connect(client);
    }

    launcher.startListening();
  }
}
