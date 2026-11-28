package io.github.vadimbabich.workspace.directory;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.QueryRenderer;
import io.github.vadimbabich.workspace.domain.GroupMembership;
import io.github.vadimbabich.workspace.domain.GroupMembership__;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

public interface GroupMembershipRepository
    extends ReactiveCrudRepository<GroupMembership, Long>, GroupMembershipQueries, GroupMembershipUpdates {
}

interface GroupMembershipQueries {

  Mono<List<GroupMembership>> membershipsOf(long companyId, long appUserId);
}

@Component
class GroupMembershipQueriesImpl implements GroupMembershipQueries {

  private static final Logger LOG = LoggerFactory.getLogger(GroupMembershipQueriesImpl.class);

  private final MetamodelQueryExecutor executor;
  private final QueryRenderer renderer;

  GroupMembershipQueriesImpl(MetamodelQueryExecutor executor, QueryRenderer renderer) {
    this.executor = executor;
    this.renderer = renderer;
  }

  @Override
  public Mono<List<GroupMembership>> membershipsOf(long companyId, long appUserId) {
    FluentSelect<GroupMembership> membershipsOfTheUser = FluentSelect.from(GroupMembership__.ENTITY)
        .where(GroupMembership__.COMPANY_ID.is(companyId)
            .and(GroupMembership__.APP_USER_ID.is(appUserId)));

    LOG.atDebug().addArgument(() -> renderer.render(membershipsOfTheUser).preview()).log("membershipsOf: {}");

    return executor.list(membershipsOfTheUser);
  }
}

interface GroupMembershipUpdates {

  Mono<Void> dropAllOf(long appUserId);
}

@Component
class GroupMembershipUpdatesImpl implements GroupMembershipUpdates {

  private final R2dbcEntityTemplate entityTemplate;

  GroupMembershipUpdatesImpl(R2dbcEntityTemplate entityTemplate) {
    this.entityTemplate = entityTemplate;
  }

  @Override
  public Mono<Void> dropAllOf(long appUserId) {
    Query membershipsOfTheUser = Query.query(Criteria.where(GroupMembership__.APP_USER_ID.name()).is(appUserId));

    return entityTemplate.delete(membershipsOfTheUser, GroupMembership.class).then();
  }
}
