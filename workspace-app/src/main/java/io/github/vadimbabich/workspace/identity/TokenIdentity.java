package io.github.vadimbabich.workspace.identity;

import io.github.vadimbabich.workspace.directory.UserProfile;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;

record TokenIdentity(
    String sub,
    String companyAlias,
    String username,
    @Nullable String email,
    boolean emailVerified,
    @Nullable String displayName,
    boolean administrator) {

  private static final String ORGANIZATION = "organization";
  private static final String REALM_ACCESS = "realm_access";
  private static final String ROLES = "roles";
  private static final String ADMINISTRATOR_ROLE = "company-admin";

  static TokenIdentity from(Jwt token) {
    String sub = subjectOf(token);
    String username = usernameOf(token, sub);
    String companyAlias = onlyOrganizationOf(token);
    String email = token.getClaimAsString(StandardClaimNames.EMAIL);
    boolean emailVerified = Boolean.TRUE.equals(token.getClaimAsBoolean(StandardClaimNames.EMAIL_VERIFIED));
    String displayName = token.getClaimAsString(StandardClaimNames.NAME);
    boolean administrator = holdsAdministratorRole(token);

    return new TokenIdentity(sub, companyAlias, username, email, emailVerified, displayName, administrator);
  }

  UserProfile profile() {
    return new UserProfile(username, email, emailVerified, displayName);
  }

  private static String subjectOf(Jwt token) {
    String sub = token.getSubject();

    if (sub == null || sub.isBlank()) {
      throw new CallerRefusedException("the token names no subject");
    }

    return sub;
  }

  private static String usernameOf(Jwt token, String sub) {
    String preferredUsername = token.getClaimAsString(StandardClaimNames.PREFERRED_USERNAME);

    if (preferredUsername == null) {
      return sub;
    }

    return preferredUsername;
  }

  private static String onlyOrganizationOf(Jwt token) {
    Object organizationClaim = token.getClaims().get(ORGANIZATION);

    if (!(organizationClaim instanceof List<?> aliases)) {
      throw new CallerRefusedException("the token names no organization");
    }

    if (aliases.size() != 1) {
      throw new CallerRefusedException("the token must name exactly one organization");
    }

    if (!(aliases.getFirst() instanceof String alias)) {
      throw new CallerRefusedException("the token's organization is not a name");
    }

    return alias;
  }

  private static boolean holdsAdministratorRole(Jwt token) {
    Object realmAccessClaim = token.getClaims().get(REALM_ACCESS);

    if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
      return false;
    }

    Object rolesClaim = realmAccess.get(ROLES);

    if (!(rolesClaim instanceof List<?> realmRoles)) {
      return false;
    }

    return realmRoles.contains(ADMINISTRATOR_ROLE);
  }
}
