package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.workspace.contract.api.FoldersApi;
import io.github.vadimbabich.workspace.contract.model.Capabilities;
import io.github.vadimbabich.workspace.contract.model.Node;
import io.github.vadimbabich.workspace.contract.model.NodeKind;
import io.github.vadimbabich.workspace.contract.model.NodePage;
import io.github.vadimbabich.workspace.identity.Caller;
import io.github.vadimbabich.workspace.query.Role;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
class FoldersController implements FoldersApi {

  private final FolderListingService listing;

  FoldersController(FolderListingService listing) {
    this.listing = listing;
  }

  @Override
  public Mono<NodePage> listTopLevelFolders(Integer limit, @Nullable String cursor, ServerWebExchange exchange) {
    return Caller.current()
        .flatMap(caller -> listing.topLevelFolders(caller, limit, cursor))
        .map(FoldersController::pageOf);
  }

  @Override
  public Mono<NodePage> listFolderChildren(
      Long folderId, Integer limit, @Nullable String cursor, ServerWebExchange exchange) {
    return Caller.current()
        .flatMap(caller -> listing.children(caller, folderId, limit, cursor))
        .map(FoldersController::pageOf);
  }

  private static NodePage pageOf(VisibleNodePage visiblePage) {
    List<Node> items = new ArrayList<>();

    for (VisibleNode visibleNode : visiblePage.nodes()) {
      items.add(toContract(visibleNode.node(), visibleNode.effectiveRole()));
    }

    NodePage page = new NodePage(items);
    visiblePage.nextCursor().ifPresent(page::nextCursor);

    return page;
  }

  private static Node toContract(io.github.vadimbabich.workspace.domain.Node node, Role effectiveRole) {
    NodeKind kind = kindOf(node);
    boolean mayListChildren = node.isFolder() && effectiveRole.mayList();
    Capabilities capabilities = new Capabilities(mayListChildren, effectiveRole.mayRead());
    OffsetDateTime createdAt = Objects.requireNonNull(node.createdAt()).atOffset(ZoneOffset.UTC);

    return new Node(node.id(), kind, node.name(), createdAt, capabilities)
        .parentId(node.parentId())
        .sizeBytes(node.sizeBytes());
  }

  private static NodeKind kindOf(io.github.vadimbabich.workspace.domain.Node node) {
    if (node.isFolder()) {
      return NodeKind.FOLDER;
    }

    return NodeKind.FILE;
  }
}
