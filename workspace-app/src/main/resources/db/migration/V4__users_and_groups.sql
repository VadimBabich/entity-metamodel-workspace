create table app_user (
  app_user_id           bigint      generated always as identity (maxvalue 9007199254740991),
  company_id            bigint      not null,
  sub                   text        not null,
  username              text        not null,
  email                 text,
  email_verified        boolean     not null default false,
  display_name          text,
  enabled               boolean     not null default true,
  synced_at             timestamptz not null default now(),
  memberships_loaded_at timestamptz,
  constraint app_user_pkey primary key (app_user_id),
  constraint app_user_sub_key unique (sub),
  constraint app_user_company_id_app_user_id_key unique (company_id, app_user_id),
  constraint app_user_company_id_fkey foreign key (company_id) references company (company_id) on delete restrict
);

grant select, insert, update on app_user to ${appRole};

create table user_group (
  user_group_id bigint generated always as identity (maxvalue 9007199254740991),
  company_id    bigint not null,
  keycloak_id   uuid   not null,
  name          text   not null,
  constraint user_group_pkey primary key (user_group_id),
  constraint user_group_keycloak_id_key unique (keycloak_id),
  constraint user_group_company_id_user_group_id_key unique (company_id, user_group_id),
  constraint user_group_company_id_fkey foreign key (company_id) references company (company_id) on delete restrict
);

grant select, insert, update on user_group to ${appRole};

create table group_membership (
  group_membership_id bigint generated always as identity (maxvalue 9007199254740991),
  company_id          bigint not null,
  user_group_id       bigint not null,
  app_user_id         bigint not null,
  constraint group_membership_pkey primary key (group_membership_id),
  constraint group_membership_user_group_id_app_user_id_key unique (user_group_id, app_user_id),
  constraint group_membership_company_id_user_group_id_fkey foreign key (company_id, user_group_id)
    references user_group (company_id, user_group_id) on delete restrict,
  constraint group_membership_company_id_app_user_id_fkey foreign key (company_id, app_user_id)
    references app_user (company_id, app_user_id) on delete restrict
);

create index group_membership_app_user on group_membership (app_user_id);

grant select, insert, delete on group_membership to ${appRole};
