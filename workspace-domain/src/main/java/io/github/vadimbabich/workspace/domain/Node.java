package io.github.vadimbabich.workspace.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A folder or a file. Both live in one table so that a folder's listing (folders first, then
 * files) is one index-ordered query.
 */
@Table("node")
public class Node {

  /** {@link #kindRank} of a folder; folders sort before files. */
  public static final short FOLDER = 0;

  /** {@link #kindRank} of a file. */
  public static final short FILE = 1;

  @Id
  @Column("node_id")
  private Long id;

  @Column("tenant_id")
  private Long tenantId;

  @Column("parent_id")
  private Long parentId;

  @Column("kind_rank")
  private Short kindRank;

  @Column("name")
  private String name;

  @Column("size_bytes")
  private Long sizeBytes;

  @Column("created_by")
  private String createdBy;

  @Column("created_at")
  private Instant createdAt;

  @Column("deleted_at")
  private Instant deletedAt;

  public Long getId() {
    return id;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public Long getParentId() {
    return parentId;
  }

  public Short getKindRank() {
    return kindRank;
  }

  public String getName() {
    return name;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public boolean isFolder() {
    return kindRank != null && kindRank == FOLDER;
  }
}
