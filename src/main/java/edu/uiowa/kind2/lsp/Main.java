package edu.uiowa.kind2.lsp;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;

public class Main {
  public static void main(String[] args) {
    Socket socket;
    try {
      socket = new Socket("localhost", 23555);
      org.eclipse.lsp4j.services.LanguageServer server = new LanguageServer();
      Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server,
          socket.getInputStream(), socket.getOutputStream());

      if (server instanceof LanguageClientAware) {
        LanguageClient client = launcher.getRemoteProxy();
        ((LanguageClientAware) server).connect(client);
      }

      launcher.startListening().get();
      socket.close();
    } catch (IOException | InterruptedException | ExecutionException e) {
      e.printStackTrace();
    }
  }
}
