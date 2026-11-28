create temporary table demonstration_folder (
  company_alias     text     not null,
  folder_name       text     not null,
  group_keycloak_id uuid,
  role              smallint,
  creator           text     not null
);

insert into demonstration_folder values
  ('acme', 'listers', '00000000-0000-4000-8000-0000000a0001', 1, '00000000-0000-4000-8000-0000000c0001'),
  ('acme', 'viewers', '00000000-0000-4000-8000-0000000a0002', 2, '00000000-0000-4000-8000-0000000d0001'),
  ('acme', 'contributors', '00000000-0000-4000-8000-0000000a0003', 3, '00000000-0000-4000-8000-0000000e0001'),
  ('acme', 'editors', '00000000-0000-4000-8000-0000000a0004', 4, '00000000-0000-4000-8000-0000000f0001'),
  ('acme', 'managers', '00000000-0000-4000-8000-0000000a0005', 5, '00000000-0000-4000-8000-00000000a11c'),
  ('acme', 'unshared', null, null, '00000000-0000-4000-8000-000000090001'),
  ('globex', 'editors', '00000000-0000-4000-8000-0000000b0004', 4, '00000000-0000-4000-8000-000000000b0b');

do $$
declare
  granted_groups bigint;
  mirrored_groups bigint;
begin
  select count(group_keycloak_id) into granted_groups from demonstration_folder;

  for attempt in 1..90 loop
    select count(*) into mirrored_groups
    from user_group
    where keycloak_id in (select group_keycloak_id from demonstration_folder);
    exit when mirrored_groups = granted_groups;
    perform pg_sleep(2);
  end loop;

  if mirrored_groups < granted_groups then
    raise exception 'only % of the % demonstration groups were mirrored within three minutes: the application '
      'mirrors them after the companies are seeded, so start it first', mirrored_groups, granted_groups;
  end if;
end
$$;

do $$
declare
  demonstration record;
  folder_id bigint;
begin
  if exists (
    select 1 from node join company using (company_id)
    where company.alias = 'acme' and node.name = 'managers' and node.parent_id is null and node.deleted_at is null
  ) then
    raise notice 'the demonstration folders are already seeded';
    return;
  end if;

  for demonstration in
    select company.company_id, seeded.folder_name, seeded.group_keycloak_id, seeded.role, seeded.creator
    from demonstration_folder seeded
    join company on company.alias = seeded.company_alias
  loop
    insert into node (company_id, parent_id, kind_rank, name, created_by)
    values (demonstration.company_id, null, 0, demonstration.folder_name, demonstration.creator)
    returning node_id into folder_id;

    insert into node (company_id, parent_id, kind_rank, name, size_bytes, created_by)
    values
      (demonstration.company_id, folder_id, 0, 'drafts', 0, demonstration.creator),
      (demonstration.company_id, folder_id, 1, 'brief.txt', 2048, demonstration.creator),
      (demonstration.company_id, folder_id, 1, 'budget.txt', 4096, demonstration.creator);

    if demonstration.group_keycloak_id is not null then
      insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role)
      select demonstration.company_id, folder_id, 2, user_group.user_group_id, demonstration.role
      from user_group
      where user_group.company_id = demonstration.company_id
        and user_group.keycloak_id = demonstration.group_keycloak_id;
    end if;
  end loop;
end
$$;
