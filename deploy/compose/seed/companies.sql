do $$
begin
  for attempt in 1..60 loop
    exit when to_regclass('public.company') is not null;
    commit;
    perform pg_sleep(2);
  end loop;

  if to_regclass('public.company') is null then
    raise exception 'no company table after two minutes: start the application first, it runs the migrations';
  end if;
end
$$;

insert into company (alias, name) values
  ('acme', 'Acme'),
  ('globex', 'Globex')
on conflict (alias) do nothing;
