package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.QueryRenderer;
import io.github.vadimbabich.workspace.domain.AccessGrant__;
import io.github.vadimbabich.workspace.domain.Node;
import io.github.vadimbabich.workspace.domain.Node__;
import io.github.vadimbabich.workspace.folders.ListingScope.ChildrenOf;
import io.github.vadimbabich.workspace.folders.ListingScope.TopLevel;
import io.github.vadimbabich.workspace.query.AccessConditions;
import io.github.vadimbabich.workspace.query.CallerSubjects;
import io.github.vadimbabich.workspace.query.ListingOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

interface NodeRepository extends Repository<Node, Long>, NodeQueries {
}

interface NodeQueries {

  Mono<Slice<Node>> firstPageIn(CallerSubjects subjects, ListingScope scope, int limit);

  Mono<Slice<Node>> pageAfter(CallerSubjects subjects, ListingScope scope, long anchorId, int limit);

  Mono<Boolean> isListedIn(CallerSubjects subjects, ListingScope scope, long nodeId);
}

@Component
class NodeQueriesImpl implements NodeQueries {

  private static final Logger LOG = LoggerFactory.getLogger(NodeQueriesImpl.class);

  private final MetamodelQueryExecutor executor;
  private final QueryRenderer renderer;
  private final AccessConditions access;
  private final ListingOrder listingOrder;

  NodeQueriesImpl(
      MetamodelQueryExecutor executor, QueryRenderer renderer, AccessConditions access, ListingOrder listingOrder) {
    this.executor = executor;
    this.renderer = renderer;
    this.access = access;
    this.listingOrder = listingOrder;
  }

  @Override
  public Mono<Slice<Node>> firstPageIn(CallerSubjects subjects, ListingScope scope, int limit) {
    FluentSelect<Node> visibleNodes = visibleIn(subjects, scope);

    return pageOf("firstPageIn", scope, visibleNodes, limit);
  }

  @Override
  public Mono<Slice<Node>> pageAfter(CallerSubjects subjects, ListingScope scope, long anchorId, int limit) {
    FluentSelect<Node> visibleNodesAfterTheAnchor = visibleIn(subjects, scope)
        .where(listingOrder.after(anchorId));

    return pageOf("pageAfter", scope, visibleNodesAfterTheAnchor, limit);
  }

  @Override
  public Mono<Boolean> isListedIn(CallerSubjects subjects, ListingScope scope, long nodeId) {
    FluentSelect<Node> visibleNode = visibleIn(subjects, scope)
        .where(Node__.ID.is(nodeId));

    LOG.atDebug()
        .addArgument(scope)
        .addArgument(() -> renderer.renderExistsProbe(visibleNode).preview())
        .log("isListedIn {}: {}");

    return executor.exists(visibleNode);
  }

  private FluentSelect<Node> visibleIn(CallerSubjects subjects, ListingScope scope) {
    return switch (scope) {
      case TopLevel topLevel -> visibleTopLevelFolders(subjects);
      case ChildrenOf childrenOf -> visibleChildren(subjects, childrenOf.folderId());
    };
  }

  private FluentSelect<Node> visibleTopLevelFolders(CallerSubjects subjects) {
    Condition grantOnTheNode = AccessGrant__.NODE_ID.eq(Node__.ID);
    Condition liveTopLevelFolder = Node__.COMPANY_ID.is(subjects.companyId())
        .and(Node__.PARENT_ID.isNull())
        .and(Node__.KIND_RANK.is(Node.FOLDER))
        .and(Node__.DELETED_AT.isNull());
    Condition reachesTheCaller = subjects.grantReachesThem();

    return FluentSelect.from(Node__.ENTITY)
        .join(AccessGrant__.ENTITY).on(grantOnTheNode)
        .where(liveTopLevelFolder.and(reachesTheCaller))
        .distinct();
  }

  private FluentSelect<Node> visibleChildren(CallerSubjects subjects, long folderId) {
    Condition grantOnTheFolderChain = access.inFolderChain(AccessGrant__.NODE_ID, subjects.companyId(), folderId);
    Condition liveChild = Node__.COMPANY_ID.is(subjects.companyId())
        .and(Node__.PARENT_ID.is(folderId))
        .and(Node__.DELETED_AT.isNull());
    Condition reachesTheCaller = subjects.grantReachesThem();

    return FluentSelect.from(Node__.ENTITY)
        .join(AccessGrant__.ENTITY).on(grantOnTheFolderChain)
        .where(liveChild.and(reachesTheCaller))
        .distinct();
  }

  private Mono<Slice<Node>> pageOf(String queryName, ListingScope scope, FluentSelect<Node> visibleNodes, int limit) {
    FluentSelect<Node> inListingOrder = visibleNodes.orderBy(listingOrder.sortOrders());

    LOG.atDebug()
        .addArgument(queryName)
        .addArgument(scope)
        .addArgument(limit)
        .addArgument(() -> renderer.render(inListingOrder).preview())
        .log("{} {}, page of {} rows: {}");

    return executor.slice(inListingOrder, PageRequest.of(0, limit));
  }
}
