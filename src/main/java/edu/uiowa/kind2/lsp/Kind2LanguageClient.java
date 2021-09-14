package edu.uiowa.kind2.lsp;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;

interface Kind2LanguageClient extends LanguageClient {

  /**
   * The update components notification is sent from the server to the client to
   * ask client to update its list of components for a file.
   *
   * @param uri uri of the file to update components for
   */
  @JsonNotification("kind2/updateComponents")
  void updateComponents(String uri);

  /**
   * @return the configured path to {@code kind2} executable
   */
  @JsonRequest("kind2/getKind2Path")
  CompletableFuture<String> getKind2Path();

  /**
   * @return the configured SMT solver option to pass to {@code kind2}
   */
  @JsonRequest("kind2/getSmtSolverOption")
  CompletableFuture<String> getSmtSolverOption();

  /**
   * @return the configured path to SMT solver
   */
  @JsonRequest("kind2/getSmtSolverPath")
  CompletableFuture<String> getSmtSolverPath();
}
