package io.github.vadimbabich.workspace.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class KeycloakDirectory {

  public static final String ACME_ORGANIZATION = "org-acme";
  public static final String GLOBEX_ORGANIZATION = "org-globex";
  public static final String INITECH_ORGANIZATION = "org-initech";
  public static final String ACME_EDITORS = "00000000-0000-4000-8000-0000000a0004";
  public static final String ACME_MANAGERS = "00000000-0000-4000-8000-0000000a0005";
  public static final String GLOBEX_EDITORS = "00000000-0000-4000-8000-0000000b0004";
  public static final String INITECH_STAFF = "00000000-0000-4000-8000-0000000c0001";

  private final Map<String, String> organizationAliases = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> organizationGroups = new ConcurrentHashMap<>();
  private final Map<String, Membership> members = new ConcurrentHashMap<>();
  private final Set<String> unreachableMembers = ConcurrentHashMap.newKeySet();
  private final AtomicInteger memberGroupReads = new AtomicInteger();
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger organizationReads = new AtomicInteger();
  private final Set<String> hungMembers = ConcurrentHashMap.newKeySet();
  private volatile boolean tokenEndpointHung;
  private volatile boolean firstIgnored;

  KeycloakDirectory() {
    reset();
  }

  public void reset() {
    organizationAliases.clear();
    organizationGroups.clear();
    members.clear();
    unreachableMembers.clear();
    memberGroupReads.set(0);
    tokenRequests.set(0);
    organizationReads.set(0);
    hungMembers.clear();
    tokenEndpointHung = false;
    firstIgnored = false;

    organizationAliases.put(ACME_ORGANIZATION, "acme");
    organizationAliases.put(GLOBEX_ORGANIZATION, "globex");
    organizationAliases.put(INITECH_ORGANIZATION, "initech");
    organizationGroups.put(
        ACME_ORGANIZATION, new LinkedHashMap<>(Map.of(ACME_EDITORS, "editors", ACME_MANAGERS, "managers")));
    organizationGroups.put(GLOBEX_ORGANIZATION, new LinkedHashMap<>(Map.of(GLOBEX_EDITORS, "editors")));
    organizationGroups.put(INITECH_ORGANIZATION, new LinkedHashMap<>(Map.of(INITECH_STAFF, "staff")));
  }

  public void addMember(String sub, String organizationId, String... groupIds) {
    members.put(sub, new Membership(organizationId, List.of(groupIds)));
  }

  public void addOrganization(String organizationId, String alias) {
    organizationAliases.put(organizationId, alias);
  }

  public void addGroup(String organizationId, String groupId, String name) {
    organizationGroups.get(organizationId).put(groupId, name);
  }

  public void renameGroup(String organizationId, String groupId, String newName) {
    organizationGroups.get(organizationId).put(groupId, newName);
  }

  public void makeUnreachable(String sub) {
    unreachableMembers.add(sub);
  }

  public void makeReachable(String sub) {
    unreachableMembers.remove(sub);
  }

  public void makeHang(String sub) {
    hungMembers.add(sub);
  }

  public void ignoreFirstOnGroupLists() {
    firstIgnored = true;
  }

  int firstHonoured(int first) {
    if (firstIgnored) {
      return 0;
    }

    return first;
  }

  public void hangTokenEndpoint() {
    tokenEndpointHung = true;
  }

  public int organizationReads() {
    return organizationReads.get();
  }

  void recordOrganizationRead() {
    organizationReads.incrementAndGet();
  }

  public int tokenRequests() {
    return tokenRequests.get();
  }

  boolean answersTokenRequest() {
    tokenRequests.incrementAndGet();
    return !tokenEndpointHung;
  }

  boolean hangs(String sub) {
    return hungMembers.contains(sub);
  }

  public int memberGroupReads() {
    return memberGroupReads.get();
  }

  boolean isUnreachable(String sub) {
    return unreachableMembers.contains(sub);
  }

  String organizationsJson(int first, int max) {
    List<String> aliases = new ArrayList<>(organizationAliases.values());
    aliases.sort(null);

    Map<String, String> idsByAlias = new LinkedHashMap<>();
    for (Map.Entry<String, String> organization : organizationAliases.entrySet()) {
      idsByAlias.put(organization.getValue(), organization.getKey());
    }

    List<String> organizations = new ArrayList<>();
    for (String alias : aliases.subList(Math.min(first, aliases.size()), Math.min(first + max, aliases.size()))) {
      organizations.add(organizationJson(idsByAlias.get(alias), alias));
    }

    return "[" + String.join(",", organizations) + "]";
  }

  String organizationsOfMemberJson(String sub) {
    Membership membership = members.get(sub);

    if (membership == null) {
      return "[]";
    }

    String alias = organizationAliases.get(membership.organizationId());

    return "[" + organizationJson(membership.organizationId(), alias) + "]";
  }

  String groupsJson(String organizationId, int first, int max) {
    List<String> groupIds = List.copyOf(organizationGroups.getOrDefault(organizationId, Map.of()).keySet());

    return groupsJson(organizationId, page(groupIds, first, max));
  }

  String groupsOfMemberJson(String organizationId, String sub, int first, int max) {
    memberGroupReads.incrementAndGet();
    Membership membership = members.get(sub);

    if (membership == null || !membership.organizationId().equals(organizationId)) {
      return "[]";
    }

    return groupsJson(organizationId, page(membership.groupIds(), first, max));
  }

  private String groupsJson(String organizationId, List<String> groupIds) {
    Map<String, String> groupNames = organizationGroups.getOrDefault(organizationId, Map.of());
    List<String> groups = new ArrayList<>();

    for (String groupId : groupIds) {
      groups.add("{\"id\":\"" + groupId + "\",\"name\":\"" + groupNames.get(groupId) + "\"}");
    }

    return "[" + String.join(",", groups) + "]";
  }

  private static List<String> page(List<String> items, int first, int max) {
    int from = Math.min(first, items.size());
    int to = (int) Math.min((long) from + max, items.size());

    return items.subList(from, to);
  }

  private static String organizationJson(String id, String alias) {
    return "{\"id\":\"" + id + "\",\"alias\":\"" + alias + "\",\"name\":\"" + alias + "\"}";
  }

  private record Membership(String organizationId, List<String> groupIds) {
  }
}
