package io.github.vadimbabich.workspace.mirror;

import io.github.vadimbabich.workspace.directory.AppUserRepository;
import io.github.vadimbabich.workspace.directory.GroupMembershipRepository;
import io.github.vadimbabich.workspace.directory.UserProfile;
import io.github.vadimbabich.workspace.domain.GroupMembership;
import io.github.vadimbabich.workspace.domain.UserGroup;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
public class MembershipService {

  private final GroupMembershipRepository memberships;
  private final AppUserRepository users;

  MembershipService(GroupMembershipRepository memberships, AppUserRepository users) {
    this.memberships = memberships;
    this.users = users;
  }

  @Transactional
  public Mono<Void> moveToCompany(long appUserId, long companyId, UserProfile profile, Instant syncedAt) {
    return memberships.dropAllOf(appUserId)
        .then(Mono.defer(() -> users.moveToCompany(appUserId, companyId, profile, syncedAt)));
  }

  @Transactional
  Mono<Void> replaceMemberships(long companyId, long appUserId, List<UserGroup> userGroups, Instant loadedAt) {
    Set<Long> loadedGroupIds = groupIdsOf(userGroups);

    Mono<Void> membershipsReplaced = memberships.membershipsOf(companyId, appUserId)
        .flatMap(currentMemberships -> applyDifference(companyId, appUserId, currentMemberships, loadedGroupIds));

    return membershipsReplaced
        .then(Mono.defer(() -> users.markMembershipsLoaded(appUserId, loadedAt)));
  }

  private Mono<Void> applyDifference(
      long companyId, long appUserId, List<GroupMembership> currentMemberships, Set<Long> loadedGroupIds) {
    List<GroupMembership> leftMemberships = membershipsOutside(currentMemberships, loadedGroupIds);
    List<GroupMembership> joinedMemberships = newMemberships(companyId, appUserId, currentMemberships, loadedGroupIds);

    return memberships.deleteAll(leftMemberships)
        .thenMany(memberships.saveAll(joinedMemberships))
        .then();
  }

  private static List<GroupMembership> membershipsOutside(
      List<GroupMembership> currentMemberships, Set<Long> loadedGroupIds) {
    List<GroupMembership> leftMemberships = new ArrayList<>();

    for (GroupMembership membership : currentMemberships) {
      boolean leftTheGroup = !loadedGroupIds.contains(membership.userGroupId());

      if (leftTheGroup) {
        leftMemberships.add(membership);
      }
    }

    return leftMemberships;
  }

  private static List<GroupMembership> newMemberships(
      long companyId, long appUserId, List<GroupMembership> currentMemberships, Set<Long> loadedGroupIds) {
    Set<Long> currentGroupIds = groupIdsOfMemberships(currentMemberships);
    List<GroupMembership> joinedMemberships = new ArrayList<>();

    for (Long groupId : loadedGroupIds) {
      boolean joinedTheGroup = !currentGroupIds.contains(groupId);

      if (joinedTheGroup) {
        joinedMemberships.add(new GroupMembership(null, companyId, groupId, appUserId));
      }
    }

    return joinedMemberships;
  }

  private static Set<Long> groupIdsOfMemberships(List<GroupMembership> memberships) {
    Set<Long> groupIds = new HashSet<>();

    for (GroupMembership membership : memberships) {
      groupIds.add(membership.userGroupId());
    }

    return groupIds;
  }

  private static Set<Long> groupIdsOf(List<UserGroup> userGroups) {
    Set<Long> groupIds = new HashSet<>();

    for (UserGroup userGroup : userGroups) {
      groupIds.add(Objects.requireNonNull(userGroup.id()));
    }

    return groupIds;
  }
}
