package io.github.vadimbabich.workspace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerAuthenticationTest {

  private final Jwt token = Jwt.withTokenValue("token")
      .header("alg", "RS256")
      .subject("sub-alice")
      .issuedAt(Instant.now())
      .expiresAt(Instant.now().plusSeconds(60))
      .build();
  private final JwtAuthenticationToken tokenAuthentication =
      new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("SCOPE_workspace-api")));
  private final Caller caller = new Caller(1L, 7L, "sub-alice", false);

  @Test
  void theCallerIsThePrincipalAndTheSubjectItsName() {
    CallerAuthentication authentication = new CallerAuthentication(caller, tokenAuthentication);

    assertThat(authentication.getPrincipal()).isEqualTo(caller);
    assertThat(authentication.getName()).isEqualTo("sub-alice");
    assertThat(authentication.isAuthenticated()).isTrue();
  }

  @Test
  void theTokenStaysTheCredentialsAndItsAuthoritiesAreKept() {
    CallerAuthentication authentication = new CallerAuthentication(caller, tokenAuthentication);

    assertThat(authentication.getCredentials()).isEqualTo(token);
    assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("SCOPE_workspace-api");
  }
}
