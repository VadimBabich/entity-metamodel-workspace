package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.workspace.directory.GroupMembershipRepository;
import io.github.vadimbabich.workspace.domain.AccessGrant;
import io.github.vadimbabich.workspace.domain.GroupMembership;
import io.github.vadimbabich.workspace.domain.Node;
import io.github.vadimbabich.workspace.identity.Caller;
import io.github.vadimbabich.workspace.query.CallerSubjects;
import io.github.vadimbabich.workspace.query.Role;
import io.github.vadimbabich.workspace.web.ProblemCondition;
import io.github.vadimbabich.workspace.web.ProblemConditionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
class FolderListingService {

  private final NodeRepository nodes;
  private final AccessGrantRepository grants;
  private final GroupMembershipRepository memberships;

  FolderListingService(NodeRepository nodes, AccessGrantRepository grants, GroupMembershipRepository memberships) {
    this.nodes = nodes;
    this.grants = grants;
    this.memberships = memberships;
  }

  @Transactional(readOnly = true)
  Mono<List<VisibleNode>> topLevelFolders(Caller caller, int limit) {
    return subjectsOf(caller).flatMap(subjects -> visibleTopLevelFolders(subjects, limit));
  }

  @Transactional(readOnly = true)
  Mono<List<VisibleNode>> children(Caller caller, long folderId, int limit) {
    return subjectsOf(caller).flatMap(subjects -> childrenOfListableFolder(subjects, folderId, limit));
  }

  private Mono<CallerSubjects> subjectsOf(Caller caller) {
    return memberships.membershipsOf(caller.companyId(), caller.appUserId())
        .map(groupMemberships -> subjectsWithGroups(caller, groupMemberships));
  }

  private Mono<List<VisibleNode>> visibleTopLevelFolders(CallerSubjects subjects, int limit) {
    return nodes.topLevelFoldersVisibleTo(subjects, limit)
        .flatMap(page -> withEffectiveRoles(subjects, page, Role.LISTER));
  }

  private Mono<List<VisibleNode>> childrenOfListableFolder(CallerSubjects subjects, long folderId, int limit) {
    return roleInheritedFrom(subjects, folderId)
        .switchIfEmpty(Mono.error(() -> new ProblemConditionException(ProblemCondition.ITEM_NOT_FOUND)))
        .flatMap(inheritedRole -> visibleChildren(subjects, folderId, limit, inheritedRole));
  }

  private Mono<Role> roleInheritedFrom(CallerSubjects subjects, long folderId) {
    return grants.onFolderChainReaching(subjects, folderId)
        .flatMap(FolderListingService::highestRoleOf);
  }

  private Mono<List<VisibleNode>> visibleChildren(
      CallerSubjects subjects, long folderId, int limit, Role inheritedRole) {
    return nodes.childrenVisibleTo(subjects, folderId, limit)
        .flatMap(page -> withEffectiveRoles(subjects, page, inheritedRole));
  }

  private Mono<List<VisibleNode>> withEffectiveRoles(CallerSubjects subjects, Slice<Node> page, Role inheritedRole) {
    List<Node> pageNodes = page.getContent();

    return directRolesOn(subjects, pageNodes)
        .map(directRoles -> visibleNodes(pageNodes, directRoles, inheritedRole));
  }

  private Mono<Map<Long, Role>> directRolesOn(CallerSubjects subjects, List<Node> pageNodes) {
    if (pageNodes.isEmpty()) {
      return Mono.just(Map.of());
    }

    return grants.onNodesReaching(subjects, idsOf(pageNodes))
        .map(FolderListingService::highestRoleByNode);
  }

  private static List<VisibleNode> visibleNodes(List<Node> pageNodes, Map<Long, Role> directRoles, Role inheritedRole) {
    List<VisibleNode> visibleNodes = new ArrayList<>();

    for (Node node : pageNodes) {
      Role directRole = directRoles.getOrDefault(node.id(), inheritedRole);
      Role effectiveRole = inheritedRole.atLeast(directRole);

      visibleNodes.add(new VisibleNode(node, effectiveRole));
    }

    return List.copyOf(visibleNodes);
  }

  private static CallerSubjects subjectsWithGroups(Caller caller, List<GroupMembership> groupMemberships) {
    List<Long> groupIds = new ArrayList<>();

    for (GroupMembership membership : groupMemberships) {
      groupIds.add(membership.userGroupId());
    }

    return new CallerSubjects(caller.companyId(), caller.appUserId(), groupIds);
  }

  private static Mono<Role> highestRoleOf(List<AccessGrant> grants) {
    if (grants.isEmpty()) {
      return Mono.empty();
    }

    Role highestRole = Role.LISTER;

    for (AccessGrant grant : grants) {
      highestRole = highestRole.atLeast(Role.fromRank(grant.role()));
    }

    return Mono.just(highestRole);
  }

  private static Map<Long, Role> highestRoleByNode(List<AccessGrant> grants) {
    Map<Long, Role> highestRoles = new HashMap<>();

    for (AccessGrant grant : grants) {
      Role grantedRole = Role.fromRank(grant.role());
      Role highestSoFar = highestRoles.getOrDefault(grant.nodeId(), grantedRole);

      highestRoles.put(grant.nodeId(), highestSoFar.atLeast(grantedRole));
    }

    return Map.copyOf(highestRoles);
  }

  private static List<Long> idsOf(List<Node> pageNodes) {
    List<Long> ids = new ArrayList<>();

    for (Node node : pageNodes) {
      ids.add(Objects.requireNonNull(node.id()));
    }

    return ids;
  }
}
