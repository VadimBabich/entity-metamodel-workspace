package io.github.vadimbabich.workspace.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_group")
public record UserGroup(
    @Id @Column("user_group_id") @Nullable Long id,
    @Column("company_id") Long companyId,
    @Column("keycloak_id") UUID keycloakId,
    @Column("name") String name) {
}
