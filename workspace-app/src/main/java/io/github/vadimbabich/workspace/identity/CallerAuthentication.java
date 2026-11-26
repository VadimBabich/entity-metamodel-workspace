package io.github.vadimbabich.workspace.identity;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerAuthentication extends AbstractAuthenticationToken {

  private final Caller caller;
  private final Jwt token;

  CallerAuthentication(Caller caller, JwtAuthenticationToken tokenAuthentication) {
    super(tokenAuthentication.getAuthorities());
    this.caller = caller;
    this.token = tokenAuthentication.getToken();
    setAuthenticated(true);
  }

  @Override
  public Caller getPrincipal() {
    return caller;
  }

  @Override
  public Jwt getCredentials() {
    return token;
  }

  @Override
  public String getName() {
    return caller.sub();
  }
}
