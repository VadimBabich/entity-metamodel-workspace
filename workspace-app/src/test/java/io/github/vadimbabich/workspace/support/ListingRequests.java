package io.github.vadimbabich.workspace.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.workspace.contract.model.NodePage;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;

public final class ListingRequests {

  public static final String TOP_LEVEL = "/api/v1/top-level-folders";

  private ListingRequests() {
  }

  public static String childrenPath(long folderId) {
    return "/api/v1/folders/" + folderId + "/children";
  }

  public static NodePage pageAt(WebTestClient client, String pathAndQuery, JwtMutator caller) {
    NodePage page = client.mutateWith(caller).get().uri(pathAndQuery).exchange()
        .expectStatus().isOk()
        .expectBody(NodePage.class)
        .returnResult().getResponseBody();

    assertThat(page).isNotNull();
    return page;
  }
}
