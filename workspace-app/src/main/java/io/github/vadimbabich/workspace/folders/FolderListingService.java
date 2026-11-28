package io.github.vadimbabich.workspace.folders;

import io.github.vadimbabich.workspace.directory.GroupMembershipRepository;
import io.github.vadimbabich.workspace.domain.AccessGrant;
import io.github.vadimbabich.workspace.domain.GroupMembership;
import io.github.vadimbabich.workspace.domain.Node;
import io.github.vadimbabich.workspace.folders.ListingScope.ChildrenOf;
import io.github.vadimbabich.workspace.folders.ListingScope.TopLevel;
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
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
class FolderListingService {

  private final NodeRepository nodes;
  private final AccessGrantRepository grants;
  private final GroupMembershipRepository memberships;
  private final ListingCursors cursors;

  FolderListingService(
      NodeRepository nodes,
      AccessGrantRepository grants,
      GroupMembershipRepository memberships,
      ListingCursors cursors) {
    this.nodes = nodes;
    this.grants = grants;
    this.memberships = memberships;
    this.cursors = cursors;
  }

  @Transactional(readOnly = true)
  Mono<VisibleNodePage> topLevelFolders(Caller caller, int limit, @Nullable String cursor) {
    ListingQuery topLevel = ListingQuery.of(caller, new TopLevel());

    return subjectsOf(caller).flatMap(subjects -> visiblePage(topLevel, subjects, cursor, limit, Role.LISTER));
  }

  @Transactional(readOnly = true)
  Mono<VisibleNodePage> children(Caller caller, long folderId, int limit, @Nullable String cursor) {
    return subjectsOf(caller)
        .flatMap(subjects -> childrenOfListableFolder(caller, subjects, folderId, limit, cursor));
  }

  private Mono<CallerSubjects> subjectsOf(Caller caller) {
    return memberships.membershipsOf(caller.companyId(), caller.appUserId())
        .map(groupMemberships -> subjectsWithGroups(caller, groupMemberships));
  }

  private Mono<VisibleNodePage> childrenOfListableFolder(
      Caller caller, CallerSubjects subjects, long folderId, int limit, @Nullable String cursor) {
    ListingQuery children = ListingQuery.of(caller, new ChildrenOf(folderId));

    return roleInheritedFrom(subjects, folderId)
        .switchIfEmpty(Mono.error(() -> new ProblemConditionException(ProblemCondition.ITEM_NOT_FOUND)))
        .flatMap(inheritedRole -> visiblePage(children, subjects, cursor, limit, inheritedRole));
  }

  private Mono<VisibleNodePage> visiblePage(
      ListingQuery query, CallerSubjects subjects, @Nullable String cursor, int limit, Role inheritedRole) {
    return pageOfNodes(query, subjects, cursor, limit)
        .flatMap(page -> visibleNodePage(query, subjects, page, inheritedRole));
  }

  private Mono<Slice<Node>> pageOfNodes(
      ListingQuery query, CallerSubjects subjects, @Nullable String cursor, int limit) {
    if (cursor == null) {
      return nodes.firstPageIn(subjects, query.scope(), limit);
    }

    return Mono.fromCallable(() -> cursors.anchorOf(query, cursor))
        .flatMap(anchorId -> pageAfterListedAnchor(subjects, query.scope(), anchorId, limit));
  }

  private Mono<Slice<Node>> pageAfterListedAnchor(
      CallerSubjects subjects, ListingScope scope, long anchorId, int limit) {
    return nodes.isListedIn(subjects, scope, anchorId)
        .filter(Boolean::booleanValue)
        .switchIfEmpty(Mono.error(() -> new ProblemConditionException(ProblemCondition.INVALID_CURSOR)))
        .flatMap(anchorListed -> nodes.pageAfter(subjects, scope, anchorId, limit));
  }

  private Mono<VisibleNodePage> visibleNodePage(
      ListingQuery query, CallerSubjects subjects, Slice<Node> page, Role inheritedRole) {
    Optional<String> nextCursor = nextCursorOf(query, page);

    return withEffectiveRoles(subjects, page, inheritedRole)
        .map(visibleNodes -> new VisibleNodePage(visibleNodes, nextCursor));
  }

  private Optional<String> nextCursorOf(ListingQuery query, Slice<Node> page) {
    if (!page.hasNext()) {
      return Optional.empty();
    }

    Node lastNode = page.getContent().getLast();
    String nextCursor = cursors.cursorAfter(query, Objects.requireNonNull(lastNode.id()));

    return Optional.of(nextCursor);
  }

  private Mono<Role> roleInheritedFrom(CallerSubjects subjects, long folderId) {
    return grants.onFolderChainReaching(subjects, folderId)
        .flatMap(FolderListingService::highestRoleOf);
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
