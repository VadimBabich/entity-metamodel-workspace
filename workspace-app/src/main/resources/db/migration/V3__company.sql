create table company (
  company_id bigint      generated always as identity (maxvalue 9007199254740991),
  alias      text        not null,
  name       text        not null,
  constraint company_pkey primary key (company_id),
  constraint company_alias_key unique (alias)
);

grant select on company to ${appRole};

alter table node rename column tenant_id to company_id;

alter table node alter column node_id set maxvalue 9007199254740991;

alter table node
  add constraint node_company_id_fkey foreign key (company_id) references company (company_id) on delete restrict;

alter table node add constraint node_company_id_node_id_key unique (company_id, node_id);

alter table node drop constraint node_parent_id_fkey;

alter table node
  add constraint node_parent_same_company_fkey foreign key (company_id, parent_id)
  references node (company_id, node_id) on delete restrict;

alter table node add constraint node_kind_rank_parent_id_check check (kind_rank = 0 or parent_id is not null);

drop index node_listing;

alter table node alter column name type text collate "C";

create index node_listing on node (company_id, parent_id, kind_rank, name, node_id);

create index node_top_level on node (company_id, kind_rank, name, node_id)
  where parent_id is null and deleted_at is null;
