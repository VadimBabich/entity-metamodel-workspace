package io.github.vadimbabich.workspace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfiguration {

  /**
   * A resource server only: tokens come from Keycloak, nothing here issues or stores them. Health
   * and the Prometheus scrape stay open on this port for the compose stack; in a real deployment
   * they belong on a management port that is not published.
   */
  @Bean
  SecurityWebFilterChain apiSecurity(ServerHttpSecurity http) {
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(exchanges -> exchanges
            .pathMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
            .anyExchange().authenticated())
        .oauth2ResourceServer(server -> server.jwt(jwt -> {}))
        .build();
  }
}
