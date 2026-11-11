-- The parts of a Supabase database that schema.sql relies on, for a plain local PostgreSQL.
create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;
create role authenticator noinherit login password 'local';
grant anon, authenticated, service_role to authenticator;

create schema auth;
create table auth.users (
  id uuid primary key default gen_random_uuid(),
  email text unique,
  raw_user_meta_data jsonb not null default '{}',
  email_confirmed_at timestamptz,
  created_at timestamptz not null default now()
);
create function auth.uid() returns uuid language sql stable as $$
  select nullif(nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub', '')::uuid
$$;
create function auth.role() returns text language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role', 'anon')
$$;

create schema storage;
create table storage.buckets (
  id text primary key, name text, public boolean default false, file_size_limit bigint, allowed_mime_types text[]
);
create table storage.objects (
  id uuid primary key default gen_random_uuid(),
  bucket_id text references storage.buckets (id),
  name text,
  owner uuid,
  metadata jsonb,
  created_at timestamptz default now(),
  unique (bucket_id, name)
);
alter table storage.objects enable row level security;
create function storage.foldername(name text) returns text[] language sql immutable as $$
  select (string_to_array(name, '/'))[1 : array_length(string_to_array(name, '/'), 1) - 1]
$$;

grant usage on schema public, auth, storage to anon, authenticated, service_role;
grant execute on all functions in schema auth to anon, authenticated, service_role;
grant all on storage.objects, storage.buckets to anon, authenticated, service_role;
-- Supabase hands every new table, view and function in "public" to the API roles;
-- schema.sql has to take back what should not be open.
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;

-- only for the tests: make an account the way the auth service would
create function public.test_create_user(uid uuid, mail text, meta jsonb, confirmed boolean default true) returns void
language sql security definer as $$
  insert into auth.users (id, email, raw_user_meta_data, email_confirmed_at)
  values (uid, mail, coalesce(meta, '{}'), case when confirmed then now() end)
$$;
-- what the auth service does when the code from the letter is accepted
create function public.test_confirm_user(uid uuid) returns void
language sql security definer as $$
  update auth.users set email_confirmed_at = now() where id = uid
$$;
revoke all on function public.test_create_user(uuid, text, jsonb, boolean), public.test_confirm_user(uuid) from public, anon, authenticated;
grant execute on function public.test_create_user(uuid, text, jsonb, boolean), public.test_confirm_user(uuid) to service_role;

-- only for the tests: what the storage service does to its table when a file arrives or goes
create function public.test_storage_put(path text, bytes bigint) returns void
language sql security definer as $$
  insert into storage.objects (bucket_id, name, metadata) values ('mps-files', path, jsonb_build_object('size', bytes))
  on conflict (bucket_id, name) do update set metadata = excluded.metadata
$$;
create function public.test_storage_delete(path text) returns void
language sql security definer as $$
  delete from storage.objects where bucket_id = 'mps-files' and name = path
$$;
create function public.test_config(user_quota bigint, daily integer, hide_after integer) returns void
language plpgsql security definer as $$
begin
  update public.mps_config set user_quota_bytes = user_quota, daily_items = daily, hide_after_reports = hide_after;
end $$;
create function public.test_make_admin(uid uuid) returns void
language plpgsql security definer as $$
begin
  update public.mps_profiles set is_admin = true where id = uid;
end $$;
-- empties the database between tests of the screens
create function public.test_reset() returns void
language plpgsql security definer as $$
begin
  delete from auth.users;
  delete from storage.objects;
  delete from public.mps_downloads;
end $$;
revoke all on function public.test_storage_put(text, bigint), public.test_storage_delete(text), public.test_config(bigint, integer, integer), public.test_make_admin(uuid), public.test_reset() from public, anon, authenticated;
grant execute on function public.test_storage_put(text, bigint), public.test_storage_delete(text), public.test_config(bigint, integer, integer), public.test_make_admin(uuid), public.test_reset() to service_role;

-- A project is rarely empty. These stand for what it may already have: tables with the most
-- common names, a row of data, and the "enable RLS on every new table" option of new projects.
-- schema.sql must work next to them and leave them exactly as they are (checked at the end of tests.sql).
create function public.rls_auto_enable() returns event_trigger language plpgsql as $$
declare
  r record;
begin
  for r in select * from pg_event_trigger_ddl_commands() where command_tag = 'CREATE TABLE' and schema_name = 'public' loop
    execute format('alter table if exists %s enable row level security', r.object_identity);
  end loop;
end $$;
create event trigger ensure_rls on ddl_command_end when tag in ('CREATE TABLE') execute function public.rls_auto_enable();

create table public.profiles (id uuid primary key default gen_random_uuid(), username text, avatar_url text, created_at timestamptz default now());
create table public.projects (id uuid primary key default gen_random_uuid(), owner_id uuid references public.profiles (id), name text, bpm integer);
create table public.items (id serial primary key, label text);
create policy "anyone reads" on public.profiles for select using (true);
insert into public.profiles (username) values ('someone');
insert into public.items (label) values ('theirs');

create schema stub;
create table stub.before as
  select c.relname, c.relacl::text as acl, c.relrowsecurity as rls,
         (select string_agg(a.attname, ',' order by a.attnum) from pg_attribute a where a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped) as columns,
         (select count(*) from pg_policy p where p.polrelid = c.oid) as policies,
         (select count(*) from pg_trigger t where t.tgrelid = c.oid and not t.tgisinternal) as triggers,
         (select count(*) from pg_index i where i.indrelid = c.oid) as indexes
  from pg_class c where c.relnamespace = 'public'::regnamespace and c.relkind = 'r';
