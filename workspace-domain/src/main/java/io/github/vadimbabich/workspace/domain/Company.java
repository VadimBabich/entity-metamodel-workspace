package io.github.vadimbabich.workspace.domain;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("company")
public record Company(
    @Id @Column("company_id") @Nullable Long id,
    @Column("alias") String alias,
    @Column("name") String name) {
}
