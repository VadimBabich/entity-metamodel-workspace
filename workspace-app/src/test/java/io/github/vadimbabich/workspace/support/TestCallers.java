package io.github.vadimbabich.workspace.support;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import java.util.List;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;

public final class TestCallers {

  public static final String ACME = "acme";
  public static final String GLOBEX = "globex";
  public static final String DEFAULT_SUBJECT = "sub-default-caller";

  private TestCallers() {
  }

  public static JwtMutator acmeMember() {
    return memberOf(ACME, DEFAULT_SUBJECT);
  }

  public static JwtMutator memberOf(String companyAlias, String subject) {
    return withOrganizationClaim(subject, List.of(companyAlias));
  }

  public static JwtMutator withOrganizationClaim(String subject, Object organizationClaim) {
    return mockJwt().jwt(token -> token.subject(subject).claim("organization", organizationClaim));
  }

  public static JwtMutator withoutOrganization(String subject) {
    return mockJwt().jwt(token -> token.subject(subject));
  }
}
