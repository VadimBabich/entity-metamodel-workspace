package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.workspace.domain.AccessGrant;
import io.github.vadimbabich.workspace.domain.AccessGrant__;
import io.github.vadimbabich.workspace.query.AccessConditions;
import io.github.vadimbabich.workspace.query.CallerSubjects;
import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

interface AccessGrantRepository extends Repository<AccessGrant, Long>, AccessGrantQueries {
}

interface AccessGrantQueries {

  Mono<List<AccessGrant>> onFolderChainReaching(CallerSubjects subjects, long folderId);

  Mono<List<AccessGrant>> onNodesReaching(CallerSubjects subjects, List<Long> nodeIds);
}

@Component
class AccessGrantQueriesImpl implements AccessGrantQueries {

  private final MetamodelQueryExecutor executor;
  private final AccessConditions access;

  AccessGrantQueriesImpl(MetamodelQueryExecutor executor, AccessConditions access) {
    this.executor = executor;
    this.access = access;
  }

  @Override
  public Mono<List<AccessGrant>> onFolderChainReaching(CallerSubjects subjects, long folderId) {
    Condition reachesTheCaller = subjects.grantReachesThem();
    Condition onTheFolderChain = access.inFolderChain(AccessGrant__.NODE_ID, subjects.companyId(), folderId);

    FluentSelect<AccessGrant> grantsOnFolderChain = FluentSelect.from(AccessGrant__.ENTITY)
        .where(reachesTheCaller.and(onTheFolderChain));

    return executor.list(grantsOnFolderChain);
  }

  @Override
  public Mono<List<AccessGrant>> onNodesReaching(CallerSubjects subjects, List<Long> nodeIds) {
    Condition reachesTheCaller = subjects.grantReachesThem();
    Condition onTheNodes = AccessGrant__.NODE_ID.in(nodeIds);

    FluentSelect<AccessGrant> directGrants = FluentSelect.from(AccessGrant__.ENTITY)
        .where(reachesTheCaller.and(onTheNodes));

    return executor.list(directGrants);
  }
}
