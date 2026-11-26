package io.github.vadimbabich.workspace.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("app_user")
public record AppUser(
    @Id @Column("app_user_id") @Nullable Long id,
    @Column("company_id") Long companyId,
    @Column("sub") String sub,
    @Column("username") String username,
    @Column("email") @Nullable String email,
    @Column("email_verified") boolean emailVerified,
    @Column("display_name") @Nullable String displayName,
    @Column("enabled") boolean enabled,
    @Column("synced_at") @Nullable Instant syncedAt,
    @Column("memberships_loaded_at") @Nullable Instant membershipsLoadedAt) {
}
