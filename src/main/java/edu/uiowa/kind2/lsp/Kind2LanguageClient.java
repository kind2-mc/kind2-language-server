package edu.uiowa.kind2.lsp;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
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
}
