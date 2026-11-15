-- Folders and files in one table, so that a folder's listing is one index-ordered query.
create table node (
  node_id    bigint generated always as identity primary key,
  tenant_id  bigint      not null,
  parent_id  bigint      references node (node_id),
  kind_rank  smallint    not null check (kind_rank in (0, 1)),
  name       text        not null,
  size_bytes bigint      not null default 0 check (size_bytes >= 0),
  created_by text        not null,
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);

-- A name is unique among the live children of a folder, the root included.
create unique index node_live_name on node (tenant_id, parent_id, name) nulls not distinct
  where deleted_at is null;

-- Serves the listing: folders first, then by name, with a unique tie-breaker for cursors.
create index node_listing on node (parent_id, kind_rank, name, node_id);
