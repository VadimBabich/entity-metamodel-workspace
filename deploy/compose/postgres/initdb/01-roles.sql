create role workspace_migrator login nosuperuser nocreatedb nocreaterole
  password 'workspace-migrator-local-development-password';

create role workspace_app login nosuperuser nocreatedb nocreaterole
  password 'workspace-app-local-development-password';

do $$
begin
  execute format('grant create on database %I to workspace_migrator', current_database());
end
$$;

grant create, usage on schema public to workspace_migrator;
grant usage on schema public to workspace_app;
