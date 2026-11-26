package io.github.vadimbabich.workspace.directory;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.FluentSelect;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import io.github.vadimbabich.workspace.domain.UserGroup;
import io.github.vadimbabich.workspace.domain.UserGroup__;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

public interface UserGroupRepository extends ReactiveCrudRepository<UserGroup, Long>, UserGroupQueries {
}

interface UserGroupQueries {

  Mono<UserGroup> withKeycloakId(UUID keycloakId);

  Mono<List<UserGroup>> withKeycloakIds(List<UUID> keycloakIds);
}

@Component
class UserGroupQueriesImpl implements UserGroupQueries {

  private final MetamodelQueryExecutor executor;

  UserGroupQueriesImpl(MetamodelQueryExecutor executor) {
    this.executor = executor;
  }

  @Override
  public Mono<UserGroup> withKeycloakId(UUID keycloakId) {
    FluentSelect<UserGroup> groupWithKeycloakId = FluentSelect.from(UserGroup__.ENTITY)
        .where(UserGroup__.KEYCLOAK_ID.is(keycloakId));

    return executor.one(groupWithKeycloakId);
  }

  @Override
  public Mono<List<UserGroup>> withKeycloakIds(List<UUID> keycloakIds) {
    FluentSelect<UserGroup> groupsWithKeycloakIds = FluentSelect.from(UserGroup__.ENTITY)
        .where(UserGroup__.KEYCLOAK_ID.in(keycloakIds));

    return executor.list(groupsWithKeycloakIds);
  }
}
