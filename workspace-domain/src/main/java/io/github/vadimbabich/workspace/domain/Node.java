package io.github.vadimbabich.workspace.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A folder or a file. Both live in one table so that a folder's listing (folders first, then
 * files) is one index-ordered query.
 */
@Table("node")
public record Node(
    @Id @Column("node_id") @Nullable Long id,
    @Column("company_id") Long companyId,
    @Column("parent_id") @Nullable Long parentId,
    @Column("kind_rank") Short kindRank,
    @Column("name") String name,
    @Column("size_bytes") Long sizeBytes,
    @Column("created_by") String createdBy,
    @Column("created_at") @Nullable Instant createdAt,
    @Column("deleted_at") @Nullable Instant deletedAt) {

  public static final short FOLDER = 0;
  public static final short FILE = 1;

  public boolean isFolder() {
    return kindRank == FOLDER;
  }
}
