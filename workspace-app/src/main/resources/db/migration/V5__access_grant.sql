create schema acl;

grant usage on schema acl to ${appRole};

create table acl.access_grant (
  access_grant_id bigint   generated always as identity (maxvalue 9007199254740991),
  company_id      bigint   not null,
  node_id         bigint   not null,
  subject_kind    smallint not null,
  subject_id      bigint   not null,
  role            smallint not null,
  constraint access_grant_pkey primary key (access_grant_id),
  constraint access_grant_node_id_subject_kind_subject_id_key unique (node_id, subject_kind, subject_id),
  constraint access_grant_company_id_node_id_fkey foreign key (company_id, node_id)
    references node (company_id, node_id) on delete restrict,
  constraint access_grant_subject_kind_check check (subject_kind between 1 and 3),
  constraint access_grant_role_check check (role between 1 and 5)
);

grant select on acl.access_grant to ${appRole};
