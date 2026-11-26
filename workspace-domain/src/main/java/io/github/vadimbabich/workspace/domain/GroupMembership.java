package io.github.vadimbabich.workspace.domain;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("group_membership")
public record GroupMembership(
    @Id @Column("group_membership_id") @Nullable Long id,
    @Column("company_id") Long companyId,
    @Column("user_group_id") Long userGroupId,
    @Column("app_user_id") Long appUserId) {
}
