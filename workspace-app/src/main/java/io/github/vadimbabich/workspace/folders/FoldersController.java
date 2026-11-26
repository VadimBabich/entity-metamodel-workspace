package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.workspace.contract.api.FoldersApi;
import io.github.vadimbabich.workspace.contract.model.NodeKind;
import io.github.vadimbabich.workspace.contract.model.NodePage;
import io.github.vadimbabich.workspace.domain.Node;
import io.github.vadimbabich.workspace.domain.Node__;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The skeleton's one endpoint: a folder's first page of children through a typed query. Tenancy,
 * permissions and the cursor arrive in later phases; until then any authenticated caller sees any
 * folder, which is acceptable only because the database holds nothing real.
 */
@RestController
class FoldersController implements FoldersApi {

  private final MetamodelQueryExecutor executor;

  FoldersController(MetamodelQueryExecutor executor) {
    this.executor = executor;
  }

  @Override
  public Mono<NodePage> listFolderChildren(Long folderId, Integer limit, ServerWebExchange exchange) {
    FluentSelect<Node> children = FluentSelect.from(Node__.ENTITY)
        .where(Node__.PARENT_ID.is(folderId).and(Node__.DELETED_AT.isNull()))
        .orderBy(Node__.KIND_RANK.asc(), Node__.NAME.asc(), Node__.ID.asc())
        .limit(limit);

    return executor.list(children).map(FoldersController::pageOf);
  }

  private static NodePage pageOf(List<Node> nodes) {
    List<io.github.vadimbabich.workspace.contract.model.Node> items =
        nodes.stream().map(FoldersController::toContract).toList();

    return new NodePage(items);
  }

  private static io.github.vadimbabich.workspace.contract.model.Node toContract(Node node) {
    NodeKind kind = node.isFolder() ? NodeKind.FOLDER : NodeKind.FILE;

    return new io.github.vadimbabich.workspace.contract.model.Node(node.id(), kind, node.name())
        .sizeBytes(node.sizeBytes());
  }
}
