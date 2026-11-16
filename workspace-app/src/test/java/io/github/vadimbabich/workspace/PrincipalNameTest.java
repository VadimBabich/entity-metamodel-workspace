package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

class PrincipalNameTest {

  private static final String SUBJECT = "6f1c2b4e-0000-4000-8000-00000000a11c";

  @Test
  void springNamesTheCallerByTheTokenSubjectNotTheUserName() {
    Jwt token = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(SUBJECT)
        .claim("preferred_username", "alice")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();

    Authentication caller = new JwtAuthenticationConverter().convert(token);

    assertThat(caller.getName()).isEqualTo(SUBJECT);
  }
}
