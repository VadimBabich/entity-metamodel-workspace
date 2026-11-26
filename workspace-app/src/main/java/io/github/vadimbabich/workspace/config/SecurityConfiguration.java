package io.github.vadimbabich.workspace.config;

import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfiguration {

  /**
   * A resource server only: tokens come from Keycloak, nothing here issues or stores them. Health,
   * info and the Prometheus scrape are served on the management port only, which the container does
   * not publish; the chain applies on both ports, so they stay open by path.
   */
  @Bean
  SecurityWebFilterChain apiSecurity(
      ServerHttpSecurity http,
      ServerAuthenticationEntryPoint authenticationEntryPoint,
      WebEndpointProperties webEndpoints) {
    String managementBasePath = webEndpoints.getBasePath();

    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .headers(SecurityConfiguration::forbidFraming)
        .authorizeExchange(exchanges -> openManagementAndAuthenticateTheRest(exchanges, managementBasePath))
        .exceptionHandling(handling -> handling.authenticationEntryPoint(authenticationEntryPoint))
        .oauth2ResourceServer(server -> acceptBearerTokens(server, authenticationEntryPoint))
        .build();
  }

  private static void openManagementAndAuthenticateTheRest(
      ServerHttpSecurity.AuthorizeExchangeSpec exchanges, String managementBasePath) {
    exchanges
        .pathMatchers(
            managementBasePath + "/health/**",
            managementBasePath + "/prometheus",
            managementBasePath + "/info")
        .permitAll();

    exchanges
        .anyExchange()
        .authenticated();
  }

  private static void acceptBearerTokens(
      ServerHttpSecurity.OAuth2ResourceServerSpec server, ServerAuthenticationEntryPoint authenticationEntryPoint) {
    server
        .authenticationEntryPoint(authenticationEntryPoint)
        .jwt(Customizer.withDefaults());
  }

  private static void forbidFraming(ServerHttpSecurity.HeaderSpec headers) {
    headers.contentSecurityPolicy(policy -> policy.policyDirectives("frame-ancestors 'none'"));
  }
}
