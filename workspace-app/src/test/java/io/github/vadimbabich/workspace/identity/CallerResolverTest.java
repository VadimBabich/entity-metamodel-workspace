package io.github.vadimbabich.workspace.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.vadimbabich.workspace.directory.AppUserRepository;
import io.github.vadimbabich.workspace.directory.CompanyRepository;
import io.github.vadimbabich.workspace.domain.AppUser;
import io.github.vadimbabich.workspace.domain.Company;
import io.github.vadimbabich.workspace.mirror.MembershipLoader;
import io.github.vadimbabich.workspace.mirror.MembershipService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CallerResolverTest {

  private static final long ACME = 1;
  private static final long GLOBEX = 2;
  private static final String SUB = "sub-alice";

  private final CompanyRepository companies = mock(CompanyRepository.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final MembershipService memberships = mock(MembershipService.class);
  private final MembershipLoader membershipLoader = mock(MembershipLoader.class);
  private final CallerResolver callers =
      new CallerResolver(companies, users, memberships, membershipLoader, Clock.systemUTC());

  @Test
  void aRowMovedBackByAConcurrentRequestLeavesTheCallerInTheTokensCompanyWithoutLoadingMemberships() {
    when(companies.withAlias("globex")).thenReturn(Mono.just(new Company(GLOBEX, "globex", "Globex")));
    when(users.withSub(SUB)).thenReturn(Mono.just(aliceInAcme()));
    when(memberships.moveToCompany(anyLong(), eq(GLOBEX), any(), any())).thenReturn(Mono.empty());
    when(membershipLoader.ensureCurrent(any(), any())).thenReturn(Mono.empty());

    StepVerifier.create(callers.resolve(globexToken()))
        .expectNextMatches(caller -> caller.companyId() == GLOBEX)
        .verifyComplete();

    verify(membershipLoader, never()).ensureCurrent(any(), any());
  }

  @Test
  void aLostInsertRaceWhoseRereadFindsNothingIsRefused() {
    when(companies.withAlias("globex")).thenReturn(Mono.just(new Company(GLOBEX, "globex", "Globex")));
    when(users.withSub(SUB)).thenReturn(Mono.empty());
    when(users.save(any())).thenReturn(Mono.error(new DuplicateKeyException("app_user_sub_key")));

    StepVerifier.create(callers.resolve(globexToken()))
        .expectError(CallerRefusedException.class)
        .verify();
  }

  private static AppUser aliceInAcme() {
    return new AppUser(7L, ACME, SUB, SUB, null, false, null, true, Instant.EPOCH, Instant.EPOCH);
  }

  private static Jwt globexToken() {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(SUB)
        .claim("organization", List.of("globex"))
        .build();
  }
}
