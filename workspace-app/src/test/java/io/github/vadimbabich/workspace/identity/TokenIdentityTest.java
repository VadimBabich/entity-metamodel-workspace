package io.github.vadimbabich.workspace.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TokenIdentityTest {

  @Test
  void theCompanyIsTheTokensOneOrganizationAlias() {
    TokenIdentity identity = TokenIdentity.from(token(claims -> claims.claim("organization", List.of("acme"))));

    assertThat(identity.companyAlias()).isEqualTo("acme");
    assertThat(identity.sub()).isEqualTo("sub-alice");
  }

  @Test
  void aTokenWithoutAnOrganizationIsRefused() {
    Jwt withoutOrganization = token(_ -> {});

    assertThatThrownBy(() -> TokenIdentity.from(withoutOrganization))
        .isInstanceOf(CallerRefusedException.class);
  }

  @Test
  void aTokenWithoutASubjectIsRefused() {
    Jwt withoutSubject = token(claims -> claims
        .claim("organization", List.of("acme"))
        .claims(allClaims -> allClaims.remove("sub")));

    assertThatThrownBy(() -> TokenIdentity.from(withoutSubject)).isInstanceOf(CallerRefusedException.class);
  }

  @Test
  void aTokenWithTwoOrganizationsIsRefused() {
    Jwt twoOrganizations = token(claims -> claims.claim("organization", List.of("acme", "globex")));

    assertThatThrownBy(() -> TokenIdentity.from(twoOrganizations)).isInstanceOf(CallerRefusedException.class);
  }

  @Test
  void anOrganizationClaimThatIsNotAListOfAliasesIsRefused() {
    Jwt objectClaim = token(claims -> claims.claim("organization", Map.of("acme", Map.of())));

    assertThatThrownBy(() -> TokenIdentity.from(objectClaim)).isInstanceOf(CallerRefusedException.class);
  }

  @Test
  void theCompanyAdministratorRoleMakesTheCallerAnAdministrator() {
    Jwt administratorToken = token(claims -> claims
        .claim("organization", List.of("acme"))
        .claim("realm_access", Map.of("roles", List.of("offline_access", "company-admin"))));

    assertThat(TokenIdentity.from(administratorToken).administrator()).isTrue();
  }

  @Test
  void withoutTheRoleTheCallerIsNoAdministrator() {
    Jwt memberToken = token(claims -> claims
        .claim("organization", List.of("acme"))
        .claim("realm_access", Map.of("roles", List.of("offline_access"))));

    assertThat(TokenIdentity.from(memberToken).administrator()).isFalse();
  }

  @Test
  void theDisplayFieldsComeFromTheStandardClaims() {
    TokenIdentity identity = TokenIdentity.from(token(claims -> claims
        .claim("organization", List.of("acme"))
        .claim("preferred_username", "alice")
        .claim("email", "alice@example.org")
        .claim("email_verified", true)
        .claim("name", "Alice Example")));

    assertThat(identity.username()).isEqualTo("alice");
    assertThat(identity.email()).isEqualTo("alice@example.org");
    assertThat(identity.emailVerified()).isTrue();
    assertThat(identity.displayName()).isEqualTo("Alice Example");
  }

  @Test
  void withoutAPreferredUsernameTheSubjectNamesTheUser() {
    TokenIdentity identity = TokenIdentity.from(token(claims -> claims.claim("organization", List.of("acme"))));

    assertThat(identity.username()).isEqualTo("sub-alice");
    assertThat(identity.email()).isNull();
    assertThat(identity.emailVerified()).isFalse();
  }

  private static Jwt token(Consumer<Jwt.Builder> claims) {
    Jwt.Builder builder = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject("sub-alice")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60));

    claims.accept(builder);
    return builder.build();
  }
}
