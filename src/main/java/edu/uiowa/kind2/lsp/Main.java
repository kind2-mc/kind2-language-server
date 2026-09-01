package edu.uiowa.kind2.lsp;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;

public class Main {
  private static final String GATEWAY_LOOPBACK_HOST = "127.0.0.1";

  public static void main(String[] args) {
    try {
      LanguageServer server = new Kind2LanguageServer();
      if (args.length == 0) {
        Launcher<Kind2LanguageClient> launcher = new LSPLauncher.Builder<Kind2LanguageClient>().setLocalService(server)
            .setRemoteInterface(Kind2LanguageClient.class).setInput(System.in).setOutput(System.out).create();
        if (server instanceof LanguageClientAware) {
          ((LanguageClientAware) server).connect(launcher.getRemoteProxy());
        }
        launcher.startListening().get();
      } else {
        int port = Integer.parseInt(args[0]);
        Socket socket = new Socket(GATEWAY_LOOPBACK_HOST, port);
        Launcher<Kind2LanguageClient> launcher = new LSPLauncher.Builder<Kind2LanguageClient>().setLocalService(server)
            .setRemoteInterface(Kind2LanguageClient.class).setInput(socket.getInputStream())
            .setOutput(socket.getOutputStream()).create();
        if (server instanceof LanguageClientAware) {
          ((LanguageClientAware) server).connect(launcher.getRemoteProxy());
        }
        launcher.startListening().get();
        socket.close();
      }
    } catch (IOException | InterruptedException | ExecutionException e) {
      e.printStackTrace();
    }
  }
}
