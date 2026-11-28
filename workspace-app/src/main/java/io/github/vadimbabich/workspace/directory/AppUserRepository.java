package io.github.vadimbabich.workspace.directory;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.QueryRenderer;
import io.github.vadimbabich.workspace.domain.AppUser;
import io.github.vadimbabich.workspace.domain.AppUser__;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

public interface AppUserRepository extends ReactiveCrudRepository<AppUser, Long>, AppUserQueries, AppUserUpdates {
}

interface AppUserQueries {

  Mono<AppUser> withSub(String sub);
}

@Component
class AppUserQueriesImpl implements AppUserQueries {

  private static final Logger LOG = LoggerFactory.getLogger(AppUserQueriesImpl.class);

  private final MetamodelQueryExecutor executor;
  private final QueryRenderer renderer;

  AppUserQueriesImpl(MetamodelQueryExecutor executor, QueryRenderer renderer) {
    this.executor = executor;
    this.renderer = renderer;
  }

  @Override
  public Mono<AppUser> withSub(String sub) {
    FluentSelect<AppUser> userWithSub = FluentSelect.from(AppUser__.ENTITY)
        .where(AppUser__.SUB.is(sub));

    LOG.atDebug().addArgument(() -> renderer.render(userWithSub).preview()).log("withSub: {}");

    return executor.one(userWithSub);
  }
}

interface AppUserUpdates {

  Mono<Void> refreshProfile(long appUserId, UserProfile profile, Instant syncedAt);

  Mono<Void> moveToCompany(long appUserId, long companyId, UserProfile profile, Instant syncedAt);

  Mono<Void> markMembershipsLoaded(long appUserId, Instant loadedAt);
}

@Component
class AppUserUpdatesImpl implements AppUserUpdates {

  private final R2dbcEntityTemplate entityTemplate;

  AppUserUpdatesImpl(R2dbcEntityTemplate entityTemplate) {
    this.entityTemplate = entityTemplate;
  }

  @Override
  public Mono<Void> refreshProfile(long appUserId, UserProfile profile, Instant syncedAt) {
    Update refreshedProfile = profileOf(profile, syncedAt);

    return updateRow(appUserId, refreshedProfile);
  }

  @Override
  public Mono<Void> moveToCompany(long appUserId, long companyId, UserProfile profile, Instant syncedAt) {
    Update movedToCompany = profileOf(profile, syncedAt)
        .set(AppUser__.COMPANY_ID.name(), companyId)
        .set(AppUser__.MEMBERSHIPS_LOADED_AT.name(), null);

    return updateRow(appUserId, movedToCompany);
  }

  @Override
  public Mono<Void> markMembershipsLoaded(long appUserId, Instant loadedAt) {
    Update membershipsLoaded = Update.update(AppUser__.MEMBERSHIPS_LOADED_AT.name(), loadedAt);

    return updateRow(appUserId, membershipsLoaded);
  }

  private Mono<Void> updateRow(long appUserId, Update changes) {
    Query userRow = Query.query(Criteria.where(AppUser__.ID.name()).is(appUserId));

    return entityTemplate.update(userRow, changes, AppUser.class).then();
  }

  private static Update profileOf(UserProfile profile, Instant syncedAt) {
    return Update.update(AppUser__.USERNAME.name(), profile.username())
        .set(AppUser__.EMAIL.name(), profile.email())
        .set(AppUser__.EMAIL_VERIFIED.name(), profile.emailVerified())
        .set(AppUser__.DISPLAY_NAME.name(), profile.displayName())
        .set(AppUser__.SYNCED_AT.name(), syncedAt);
  }
}
