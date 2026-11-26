package io.github.vadimbabich.workspace.identity;

import io.github.vadimbabich.workspace.directory.AppUserRepository;
import io.github.vadimbabich.workspace.directory.CompanyRepository;
import io.github.vadimbabich.workspace.directory.UserProfile;
import io.github.vadimbabich.workspace.domain.AppUser;
import io.github.vadimbabich.workspace.domain.Company;
import io.github.vadimbabich.workspace.mirror.MembershipLoader;
import io.github.vadimbabich.workspace.mirror.MembershipService;
import java.time.Clock;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
class CallerResolver {

  private final CompanyRepository companies;
  private final AppUserRepository users;
  private final MembershipService membershipService;
  private final MembershipLoader membershipLoader;
  private final Clock clock;

  CallerResolver(
      CompanyRepository companies,
      AppUserRepository users,
      MembershipService membershipService,
      MembershipLoader membershipLoader,
      Clock clock) {
    this.companies = companies;
    this.users = users;
    this.membershipService = membershipService;
    this.membershipLoader = membershipLoader;
    this.clock = clock;
  }

  Mono<Caller> resolve(Jwt token) {
    return Mono.fromCallable(() -> TokenIdentity.from(token))
        .flatMap(this::callerFor);
  }

  private Mono<Caller> callerFor(TokenIdentity identity) {
    return companies.withAlias(identity.companyAlias())
        .switchIfEmpty(Mono.error(() -> new CallerRefusedException("the token names an unknown organization")))
        .flatMap(company -> callerWithMemberships(identity, company));
  }

  private Mono<Caller> callerWithMemberships(TokenIdentity identity, Company company) {
    long tokenCompanyId = Objects.requireNonNull(company.id());

    return userRowOf(identity, tokenCompanyId)
        .switchIfEmpty(Mono.error(() -> new CallerRefusedException("the caller's user row could not be read")))
        .flatMap(user -> callerInTokenCompany(user, tokenCompanyId, identity));
  }

  private Mono<Caller> callerInTokenCompany(AppUser user, long tokenCompanyId, TokenIdentity identity) {
    long appUserId = Objects.requireNonNull(user.id());
    Caller caller = new Caller(tokenCompanyId, appUserId, user.sub(), identity.administrator());

    if (movedAwayByAConcurrentRequest(user, tokenCompanyId)) {
      return Mono.just(caller);
    }

    return membershipLoader.ensureCurrent(user, identity.companyAlias()).thenReturn(caller);
  }

  private static boolean movedAwayByAConcurrentRequest(AppUser user, long tokenCompanyId) {
    return user.companyId() != tokenCompanyId;
  }

  private Mono<AppUser> userRowOf(TokenIdentity identity, long tokenCompanyId) {
    return users.withSub(identity.sub())
        .flatMap(CallerResolver::refusedIfDisabled)
        .flatMap(existingUser -> synchronised(existingUser, identity, tokenCompanyId))
        .switchIfEmpty(Mono.defer(() -> insertedUser(identity, tokenCompanyId)));
  }

  private static Mono<AppUser> refusedIfDisabled(AppUser existingUser) {
    if (!existingUser.enabled()) {
      return Mono.error(new CallerRefusedException("the user is disabled"));
    }

    return Mono.just(existingUser);
  }

  private Mono<AppUser> insertedUser(TokenIdentity identity, long tokenCompanyId) {
    UserProfile profile = identity.profile();
    AppUser newUser = new AppUser(
        null,
        tokenCompanyId,
        identity.sub(),
        profile.username(),
        profile.email(),
        profile.emailVerified(),
        profile.displayName(),
        true,
        null,
        null);

    return users.save(newUser)
        .onErrorResume(DuplicateKeyException.class, _ -> users.withSub(identity.sub()));
  }

  private Mono<AppUser> synchronised(AppUser existingUser, TokenIdentity identity, long tokenCompanyId) {
    boolean movedToAnotherCompany = existingUser.companyId() != tokenCompanyId;

    if (movedToAnotherCompany) {
      return movedToTokenCompany(existingUser, identity, tokenCompanyId);
    }

    boolean profileChanged = !identity.profile().equals(UserProfile.of(existingUser));

    if (profileChanged) {
      return profileRefreshed(existingUser, identity);
    }

    return Mono.just(existingUser);
  }

  private Mono<AppUser> profileRefreshed(AppUser existingUser, TokenIdentity identity) {
    long appUserId = Objects.requireNonNull(existingUser.id());

    return users.refreshProfile(appUserId, identity.profile(), clock.instant())
        .then(reread(existingUser));
  }

  private Mono<AppUser> movedToTokenCompany(AppUser existingUser, TokenIdentity identity, long tokenCompanyId) {
    long appUserId = Objects.requireNonNull(existingUser.id());

    return membershipService.moveToCompany(appUserId, tokenCompanyId, identity.profile(), clock.instant())
        .then(reread(existingUser));
  }

  private Mono<AppUser> reread(AppUser existingUser) {
    return Mono.defer(() -> users.withSub(existingUser.sub()));
  }
}
