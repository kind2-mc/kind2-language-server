package edu.uiowa.kind2.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;

interface Kind2LanguageClient extends LanguageClient {

  /**
   * The update components notification is sent from the server to the client to
   * ask the client to update its list of components for a file.
   *
   * @param uri uri of the file to update components for
   */
  @JsonNotification("kind2/updateComponents")
  void updateComponents(String uri);

  /**
   * @return the default path to {@code kind2} executable
   */
  @JsonRequest("kind2/getDefaultKind2Path")
  CompletableFuture<String> getDefaultKind2Path();

  /**
   * @return the default path to Z3 SMT solver
   */
  @JsonRequest("kind2/getDefaultZ3Path")
  CompletableFuture<String> getDefaultZ3Path();


  @JsonNotification("kind2/checkResultUpdate")
  void checkResultUpdate(String uri, String name, List<String> values);

  @JsonNotification("kind2/checkComplete")
  void checkComplete(String uri, String name);

  @JsonNotification("kind2/minimalCutSetResultUpdate")
  void minimalCutSetResultUpdate(String uri, String name, List<String> values);

  @JsonNotification("kind2/minimalCutSetComplete")
  void minimalCutSetComplete(String uri, String name);

  @JsonNotification("kind2/realizabilityResultUpdate")
  void realizabilityResultUpdate(String uri, String name, List<String> values);

  @JsonNotification("kind2/realizabilityComplete")
  void realizabilityComplete(String uri, String name);
}
