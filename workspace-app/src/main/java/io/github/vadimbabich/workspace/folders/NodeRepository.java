package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.workspace.domain.AccessGrant__;
import io.github.vadimbabich.workspace.domain.Node;
import io.github.vadimbabich.workspace.domain.Node__;
import io.github.vadimbabich.workspace.query.AccessConditions;
import io.github.vadimbabich.workspace.query.CallerSubjects;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

interface NodeRepository extends Repository<Node, Long>, NodeQueries {
}

interface NodeQueries {

  Mono<Slice<Node>> topLevelFoldersVisibleTo(CallerSubjects subjects, int limit);

  Mono<Slice<Node>> childrenVisibleTo(CallerSubjects subjects, long folderId, int limit);
}

@Component
class NodeQueriesImpl implements NodeQueries {

  private final MetamodelQueryExecutor executor;
  private final AccessConditions access;

  NodeQueriesImpl(MetamodelQueryExecutor executor, AccessConditions access) {
    this.executor = executor;
    this.access = access;
  }

  @Override
  public Mono<Slice<Node>> topLevelFoldersVisibleTo(CallerSubjects subjects, int limit) {
    Condition grantOnTheNode = AccessGrant__.NODE_ID.eq(Node__.ID);
    Condition liveTopLevelFolder = Node__.COMPANY_ID.is(subjects.companyId())
        .and(Node__.PARENT_ID.isNull())
        .and(Node__.KIND_RANK.is(Node.FOLDER))
        .and(Node__.DELETED_AT.isNull());
    Condition reachesTheCaller = subjects.grantReachesThem();

    FluentSelect<Node> topLevelFolders = FluentSelect.from(Node__.ENTITY)
        .join(AccessGrant__.ENTITY).on(grantOnTheNode)
        .where(liveTopLevelFolder.and(reachesTheCaller))
        .distinct();

    return firstPageInListingOrder(topLevelFolders, limit);
  }

  @Override
  public Mono<Slice<Node>> childrenVisibleTo(CallerSubjects subjects, long folderId, int limit) {
    Condition grantOnTheFolderChain = access.inFolderChain(AccessGrant__.NODE_ID, subjects.companyId(), folderId);
    Condition liveChild = Node__.COMPANY_ID.is(subjects.companyId())
        .and(Node__.PARENT_ID.is(folderId))
        .and(Node__.DELETED_AT.isNull());
    Condition reachesTheCaller = subjects.grantReachesThem();

    FluentSelect<Node> children = FluentSelect.from(Node__.ENTITY)
        .join(AccessGrant__.ENTITY).on(grantOnTheFolderChain)
        .where(liveChild.and(reachesTheCaller))
        .distinct();

    return firstPageInListingOrder(children, limit);
  }

  private Mono<Slice<Node>> firstPageInListingOrder(FluentSelect<Node> visibleNodes, int limit) {
    FluentSelect<Node> inListingOrder =
        visibleNodes.orderBy(Node__.KIND_RANK.asc(), Node__.NAME.asc(), Node__.ID.asc());

    return executor.slice(inListingOrder, PageRequest.of(0, limit));
  }
}
