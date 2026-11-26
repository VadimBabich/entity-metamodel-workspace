package io.github.vadimbabich.workspace.directory;

import io.github.vadimbabich.workspace.domain.AppUser;
import org.jspecify.annotations.Nullable;

public record UserProfile(
    String username,
    @Nullable String email,
    boolean emailVerified,
    @Nullable String displayName) {

  public static UserProfile of(AppUser user) {
    return new UserProfile(user.username(), user.email(), user.emailVerified(), user.displayName());
  }
}
