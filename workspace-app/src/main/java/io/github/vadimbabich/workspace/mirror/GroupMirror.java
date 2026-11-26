package io.github.vadimbabich.workspace.mirror;

import io.github.vadimbabich.workspace.directory.CompanyRepository;
import io.github.vadimbabich.workspace.directory.UserGroupRepository;
import io.github.vadimbabich.workspace.domain.UserGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
class GroupMirror {

  private final CompanyRepository companies;
  private final UserGroupRepository userGroups;

  GroupMirror(CompanyRepository companies, UserGroupRepository userGroups) {
    this.companies = companies;
    this.userGroups = userGroups;
  }

  Mono<Void> mirrorOrganizationGroups(String companyAlias, List<KeycloakGroup> keycloakGroups) {
    return companies.withAlias(companyAlias)
        .flatMap(company -> mirrored(Objects.requireNonNull(company.id()), keycloakGroups))
        .then();
  }

  Mono<List<UserGroup>> mirrored(long companyId, List<KeycloakGroup> keycloakGroups) {
    if (keycloakGroups.isEmpty()) {
      return Mono.just(List.of());
    }

    return userGroups.withKeycloakIds(keycloakIdsOf(keycloakGroups))
        .map(GroupMirror::byKeycloakId)
        .flatMap(knownGroups -> mirroredAgainst(companyId, keycloakGroups, knownGroups));
  }

  private Mono<List<UserGroup>> mirroredAgainst(
      long companyId, List<KeycloakGroup> keycloakGroups, Map<UUID, UserGroup> knownGroups) {
    return Flux.fromIterable(keycloakGroups)
        .concatMap(keycloakGroup -> mirroredGroup(companyId, keycloakGroup, knownGroups))
        .collectList();
  }

  private Mono<UserGroup> mirroredGroup(
      long companyId, KeycloakGroup keycloakGroup, Map<UUID, UserGroup> knownGroups) {
    UserGroup knownGroup = knownGroups.get(keycloakGroup.id());

    if (knownGroup == null) {
      return insertedGroup(companyId, keycloakGroup);
    }

    return renamedIfChanged(knownGroup, keycloakGroup);
  }

  private Mono<UserGroup> insertedGroup(long companyId, KeycloakGroup keycloakGroup) {
    UserGroup newGroup = new UserGroup(null, companyId, keycloakGroup.id(), keycloakGroup.name());

    return userGroups.save(newGroup)
        .onErrorResume(DuplicateKeyException.class, _ -> userGroups.withKeycloakId(keycloakGroup.id()));
  }

  private Mono<UserGroup> renamedIfChanged(UserGroup knownGroup, KeycloakGroup keycloakGroup) {
    boolean nameUnchanged = knownGroup.name().equals(keycloakGroup.name());

    if (nameUnchanged) {
      return Mono.just(knownGroup);
    }

    UserGroup renamedGroup =
        new UserGroup(knownGroup.id(), knownGroup.companyId(), knownGroup.keycloakId(), keycloakGroup.name());

    return userGroups.save(renamedGroup);
  }

  private static List<UUID> keycloakIdsOf(List<KeycloakGroup> keycloakGroups) {
    List<UUID> keycloakIds = new ArrayList<>();

    for (KeycloakGroup keycloakGroup : keycloakGroups) {
      keycloakIds.add(keycloakGroup.id());
    }

    return keycloakIds;
  }

  private static Map<UUID, UserGroup> byKeycloakId(List<UserGroup> knownGroups) {
    Map<UUID, UserGroup> groupsByKeycloakId = new HashMap<>();

    for (UserGroup knownGroup : knownGroups) {
      groupsByKeycloakId.put(knownGroup.keycloakId(), knownGroup);
    }

    return groupsByKeycloakId;
  }
}
