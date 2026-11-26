package io.github.vadimbabich.workspace.domain;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "acl", value = "access_grant")
public record AccessGrant(
    @Id @Column("access_grant_id") @Nullable Long id,
    @Column("company_id") Long companyId,
    @Column("node_id") Long nodeId,
    @Column("subject_kind") Short subjectKind,
    @Column("subject_id") Long subjectId,
    @Column("role") Short role) {

  public static final short USER = 1;
  public static final short GROUP = 2;
  public static final short COMPANY = 3;
}
