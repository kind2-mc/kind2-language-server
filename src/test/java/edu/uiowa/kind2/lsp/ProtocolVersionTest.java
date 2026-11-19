package edu.uiowa.kind2.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.InitializeError;
import org.eclipse.lsp4j.InitializeErrorCode;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class ProtocolVersionTest {
  private static JsonObject options(Object version) {
    JsonObject options = new JsonObject();
    if (version instanceof Number) {
      options.addProperty(Kind2LanguageServer.PROTOCOL_VERSION_OPTION, (Number) version);
    } else {
      options.addProperty(Kind2LanguageServer.PROTOCOL_VERSION_OPTION, (String) version);
    }
    return options;
  }

  private static void assertRejected(Object initializationOptions, String expectedText) {
    ResponseError error = Kind2LanguageServer.checkProtocolVersion(initializationOptions);
    assertNotNull(error);
    assertEquals(InitializeErrorCode.unknownProtocolVersion, error.getCode());
    assertFalse(((InitializeError) error.getData()).isRetry());
    assertTrue(error.getMessage().contains(expectedText), error.getMessage());
  }

  private static Kind2LanguageServer connectedServer() {
    Kind2LanguageServer server = new Kind2LanguageServer();
    server.connect((Kind2LanguageClient) Proxy.newProxyInstance(
        Kind2LanguageClient.class.getClassLoader(),
        new Class<?>[] { Kind2LanguageClient.class }, (proxy, method, args) -> null));
    return server;
  }

  @Test
  void acceptsClientsWithoutAVersion() {
    assertNull(Kind2LanguageServer.checkProtocolVersion(null));
    assertNull(Kind2LanguageServer.checkProtocolVersion(new JsonObject()));
    assertNull(Kind2LanguageServer.checkProtocolVersion(new String[] { "-json" }));
  }

  @Test
  void acceptsSupportedVersions() {
    for (int version = Kind2LanguageServer.MIN_PROTOCOL_VERSION;
        version <= Kind2LanguageServer.PROTOCOL_VERSION; version++) {
      assertNull(Kind2LanguageServer.checkProtocolVersion(options(version)));
    }
  }

  @Test
  void rejectsUnsupportedOrInvalidVersions() {
    assertRejected(options(Kind2LanguageServer.MIN_PROTOCOL_VERSION - 1),
        "Please update the extension");
    assertRejected(options(Kind2LanguageServer.PROTOCOL_VERSION + 1),
        "needs a newer Kind 2 server");
    assertRejected(options("1"), "invalid protocol version");
    assertRejected(options(1.5), "invalid protocol version");
  }

  @Test
  void initializeReportsVersionOrRejects() throws Exception {
    InitializeParams params = new InitializeParams();
    params.setInitializationOptions(options(Kind2LanguageServer.PROTOCOL_VERSION));
    InitializeResult result = connectedServer().initialize(params).get();
    JsonObject experimental = (JsonObject) result.getCapabilities().getExperimental();
    assertEquals(Kind2LanguageServer.PROTOCOL_VERSION,
        experimental.get(Kind2LanguageServer.PROTOCOL_VERSION_OPTION).getAsInt());

    params.setInitializationOptions(options(Kind2LanguageServer.PROTOCOL_VERSION + 1));
    ExecutionException e = assertThrows(ExecutionException.class,
        () -> connectedServer().initialize(params).get());
    assertTrue(e.getCause() instanceof ResponseErrorException, String.valueOf(e.getCause()));
  }
}
