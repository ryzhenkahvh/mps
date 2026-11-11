-- MPS community: the database behind sharing of sounds, presets, projects and tracks.
--
-- How to use: open the Supabase project, SQL Editor, paste this whole file and press Run.
-- It can be run again at any time: nothing that people have published is touched.
--
-- Everything it creates starts with "mps_", so it does not touch tables, functions or files
-- that the project already has (a table called "profiles", for example, is left alone).
--
-- What it sets up
--   mps_profiles   one row per account: name, a few words about the author, colour of the avatar
--   mps_items      everything that is published (kind: sound, preset, project, track)
--   mps_ratings    one mark of 1..5 per person and item
--   mps_downloads  who has taken what, so that a download is counted once
--   mps_reports    complaints; an item with several of them is hidden until a moderator looks
--   mps_config     the limits (one row)
--   storage        one public bucket "mps-files"; everyone uploads into a folder named after their id
--
-- The limits of the free plan (1 GB of files) are guarded by mps_config below.

-- ------------------------------------------------------------------ settings

create table if not exists public.mps_config (
  id boolean primary key default true check (id),
  -- how much one author may keep on the server
  user_quota_bytes bigint not null default 52428800,
  -- uploads stop when the whole bucket reaches this size (the free plan holds 1 GB)
  total_quota_bytes bigint not null default 943718400,
  -- an item is hidden after this many complaints from different people
  hide_after_reports integer not null default 3,
  -- publications per author per day
  daily_items integer not null default 40
);
insert into public.mps_config default values on conflict do nothing;

alter table public.mps_config enable row level security;
drop policy if exists "config is public" on public.mps_config;
create policy "config is public" on public.mps_config for select using (true);
revoke all on public.mps_config from anon, authenticated;
grant select on public.mps_config to anon, authenticated;

-- ------------------------------------------------------------------ profiles of authors

create table if not exists public.mps_profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  username text not null,
  bio text not null default '',
  avatar smallint not null default 0,
  is_admin boolean not null default false,
  created_at timestamptz not null default now(),
  constraint mps_profiles_username_ok check (char_length(username) between 3 and 24 and username !~ '[[:space:][:cntrl:]/\\@<>"]'),
  constraint mps_profiles_bio_ok check (char_length(bio) <= 300),
  constraint mps_profiles_avatar_ok check (avatar between 0 and 15)
);
create unique index if not exists mps_profiles_username_key on public.mps_profiles (lower(username));

alter table public.mps_profiles enable row level security;
drop policy if exists "profiles are public" on public.mps_profiles;
create policy "profiles are public" on public.mps_profiles for select using (true);
drop policy if exists "own profile can be edited" on public.mps_profiles;
create policy "own profile can be edited" on public.mps_profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));

revoke all on public.mps_profiles from anon, authenticated;
grant select on public.mps_profiles to anon, authenticated;
-- is_admin is not in this list on purpose: it is set by hand in the table editor
grant update (username, bio, avatar) on public.mps_profiles to authenticated;

create or replace function public.mps_is_admin() returns boolean
language sql stable security invoker set search_path = '' as $$
  select coalesce((select p.is_admin from public.mps_profiles p where p.id = auth.uid()), false)
$$;

create or replace function public.mps_username_free(name text) returns boolean
language sql stable security invoker set search_path = '' as $$
  select not exists (select 1 from public.mps_profiles p where lower(p.username) = lower(trim(name)))
$$;

-- Changes the profile of the signed-in person.
create or replace function public.mps_update_profile(new_username text, new_bio text, new_avatar integer) returns void
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception 'MPS_SIGN_IN' using errcode = 'P0001';
  end if;
  update public.mps_profiles p set username = trim(new_username), bio = coalesce(new_bio, ''), avatar = coalesce(new_avatar, 0)
  where p.id = auth.uid();
end $$;

-- Every new account gets a profile. The name asked for at sign-up is used when it is free and
-- allowed; otherwise (and for sign-in with Google, which has no such field) one is made up
-- and can be changed later. This function must never fail: a failure here blocks the sign-up.
create or replace function public.mps_handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  wanted text := nullif(trim(coalesce(new.raw_user_meta_data ->> 'username', '')), '');
begin
  if wanted is null then
    wanted := nullif(left(regexp_replace(trim(coalesce(new.raw_user_meta_data ->> 'name', '')), '[[:space:]]+', '_', 'g'), 24), '');
  end if;
  if wanted is not null then
    begin
      insert into public.mps_profiles (id, username) values (new.id, wanted);
      return new;
    exception when others then
      null;
    end;
  end if;
  insert into public.mps_profiles (id, username)
  values (new.id, 'user_' || substr(replace(new.id::text, '-', ''), 1, 10))
  on conflict (id) do nothing;
  return new;
end $$;

drop trigger if exists mps_on_auth_user_created on auth.users;
create trigger mps_on_auth_user_created after insert on auth.users
  for each row execute function public.mps_handle_new_user();

-- ------------------------------------------------------------------ items

create table if not exists public.mps_items (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null default auth.uid() references public.mps_profiles (id) on delete cascade,
  kind text not null,
  title text not null,
  description text not null default '',
  tags text[] not null default '{}',
  -- path inside the bucket "files": "<owner id>/<file>"; presets have no file
  file_path text,
  -- projects: an MP3 to listen to before downloading
  preview_path text,
  size_bytes integer not null default 0,
  duration_ms integer not null default 0,
  bpm real,
  -- presets: the instrument or effect they belong to, and its settings
  device text,
  payload jsonb,
  downloads integer not null default 0,
  rating_sum integer not null default 0,
  rating_count integer not null default 0,
  -- what "best rated" sorts by: the average, pulled towards the middle while marks are few
  score real not null default 0,
  reports integer not null default 0,
  hidden boolean not null default false,
  created_at timestamptz not null default now(),
  constraint mps_items_kind_ok check (kind in ('sound', 'preset', 'project', 'track')),
  constraint mps_items_title_ok check (char_length(title) between 2 and 60),
  constraint mps_items_description_ok check (char_length(description) <= 600),
  constraint mps_items_tags_ok check (cardinality(tags) <= 8 and char_length(array_to_string(tags, '')) <= 160),
  constraint mps_items_file_ok check ((kind = 'preset') = (file_path is null)),
  constraint mps_items_preset_ok check (
    kind <> 'preset' or (device is not null and payload is not null and octet_length(payload::text) <= 8192)
  ),
  constraint mps_items_numbers_ok check (size_bytes between 0 and 20971520 and duration_ms >= 0)
);
create index if not exists mps_items_feed_idx on public.mps_items (kind, created_at desc);
create index if not exists mps_items_owner_idx on public.mps_items (owner);
create index if not exists mps_items_tags_idx on public.mps_items using gin (tags);

alter table public.mps_items enable row level security;
drop policy if exists "visible items" on public.mps_items;
create policy "visible items" on public.mps_items for select
  using (not hidden or owner = (select auth.uid()) or (select public.mps_is_admin()));
drop policy if exists "publish as yourself" on public.mps_items;
create policy "publish as yourself" on public.mps_items for insert to authenticated with check (
  owner = (select auth.uid())
  and (file_path is null or file_path like (select auth.uid())::text || '/%')
  and (preview_path is null or preview_path like (select auth.uid())::text || '/%')
);
drop policy if exists "edit own items" on public.mps_items;
create policy "edit own items" on public.mps_items for update to authenticated
  using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
drop policy if exists "delete own items" on public.mps_items;
create policy "delete own items" on public.mps_items for delete to authenticated
  using (owner = (select auth.uid()) or (select public.mps_is_admin()));

-- Counters, marks and the "hidden" flag cannot be written by the app: only these columns can.
revoke all on public.mps_items from anon, authenticated;
grant select on public.mps_items to anon, authenticated;
grant insert (kind, title, description, tags, file_path, preview_path, size_bytes, duration_ms, bpm, device, payload)
  on public.mps_items to authenticated;
grant update (title, description, tags) on public.mps_items to authenticated;
grant delete on public.mps_items to authenticated;

create or replace function public.mps_check_item() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  today integer;
  allowed integer;
begin
  if tg_op = 'INSERT' then
    select count(*) into today from public.mps_items i where i.owner = new.owner and i.created_at > now() - interval '1 day';
    select c.daily_items into allowed from public.mps_config c;
    if today >= coalesce(allowed, 40) then
      raise exception 'MPS_DAILY_LIMIT' using errcode = 'P0001';
    end if;
  end if;
  -- tags are kept tidy: lower case, no blanks, no repeats
  new.tags := coalesce(
    (select array_agg(distinct lower(trim(t))) from unnest(new.tags) t where trim(t) <> ''), '{}'
  );
  return new;
end $$;

drop trigger if exists mps_items_check on public.mps_items;
create trigger mps_items_check before insert or update of tags on public.mps_items
  for each row execute function public.mps_check_item();

-- Changes the texts of an item of the signed-in person.
create or replace function public.mps_update_item(item uuid, new_title text, new_description text, new_tags text[]) returns void
language plpgsql set search_path = '' as $$
begin
  update public.mps_items i set title = trim(new_title), description = coalesce(new_description, ''), tags = coalesce(new_tags, '{}')
  where i.id = item and i.owner = auth.uid();
  if not found then
    raise exception 'MPS_NOT_ALLOWED' using errcode = 'P0001';
  end if;
end $$;

-- ------------------------------------------------------------------ ratings

create table if not exists public.mps_ratings (
  item_id uuid not null references public.mps_items (id) on delete cascade,
  user_id uuid not null default auth.uid() references public.mps_profiles (id) on delete cascade,
  stars smallint not null,
  created_at timestamptz not null default now(),
  primary key (item_id, user_id),
  constraint mps_ratings_stars_ok check (stars between 1 and 5)
);

create index if not exists mps_ratings_user_idx on public.mps_ratings (user_id);

alter table public.mps_ratings enable row level security;
drop policy if exists "own ratings" on public.mps_ratings;
create policy "own ratings" on public.mps_ratings for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists "rate as yourself" on public.mps_ratings;
-- nobody rates their own work
create policy "rate as yourself" on public.mps_ratings for insert to authenticated with check (
  user_id = (select auth.uid())
  and not exists (select 1 from public.mps_items i where i.id = item_id and i.owner = (select auth.uid()))
);
drop policy if exists "change own rating" on public.mps_ratings;
create policy "change own rating" on public.mps_ratings for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists "remove own rating" on public.mps_ratings;
create policy "remove own rating" on public.mps_ratings for delete to authenticated using (user_id = (select auth.uid()));

revoke all on public.mps_ratings from anon, authenticated;
grant select, delete on public.mps_ratings to authenticated;
grant insert (item_id, stars) on public.mps_ratings to authenticated;
grant update (stars) on public.mps_ratings to authenticated;

create or replace function public.mps_refresh_rating() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  target uuid;
begin
  if tg_op = 'DELETE' then target := old.item_id; else target := new.item_id; end if;
  update public.mps_items i set
    rating_sum = s.total,
    rating_count = s.n,
    score = case when s.n = 0 then 0 else (s.total + 7.0) / (s.n + 2) end
  from (
    select coalesce(sum(r.stars), 0)::integer as total, count(*)::integer as n
    from public.mps_ratings r where r.item_id = target
  ) s
  where i.id = target;
  return null;
end $$;

drop trigger if exists mps_ratings_changed on public.mps_ratings;
create trigger mps_ratings_changed after insert or update or delete on public.mps_ratings
  for each row execute function public.mps_refresh_rating();

-- Sets (1..5) or removes (0) the mark of the signed-in person; returns the new totals of the item.
create or replace function public.mps_rate(item uuid, stars integer)
returns table (rating_sum integer, rating_count integer)
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception 'MPS_SIGN_IN' using errcode = 'P0001';
  end if;
  if stars <= 0 then
    delete from public.mps_ratings r where r.item_id = item and r.user_id = auth.uid();
  else
    insert into public.mps_ratings (item_id, stars) values (item, least(stars, 5))
    on conflict (item_id, user_id) do update set stars = excluded.stars;
  end if;
  return query select i.rating_sum, i.rating_count from public.mps_items i where i.id = item;
end $$;

-- ------------------------------------------------------------------ downloads

create table if not exists public.mps_downloads (
  item_id uuid not null references public.mps_items (id) on delete cascade,
  -- the account, or "d:<random id of an installation>" for people who are not signed in
  who text not null,
  created_at timestamptz not null default now(),
  primary key (item_id, who)
);
alter table public.mps_downloads enable row level security;
revoke all on public.mps_downloads from anon, authenticated;

-- Counts a download (for tracks: a listen) once per person. Returns the new total.
create or replace function public.mps_add_download(item uuid, device text default '') returns integer
language plpgsql security definer set search_path = '' as $$
declare
  me text := coalesce(auth.uid()::text, 'd:' || left(coalesce(device, ''), 40));
  total integer;
begin
  if auth.uid() is null and char_length(coalesce(device, '')) < 8 then
    return (select i.downloads from public.mps_items i where i.id = item);
  end if;
  -- authors do not raise their own numbers
  if exists (select 1 from public.mps_items i where i.id = item and i.owner = auth.uid()) then
    return (select i.downloads from public.mps_items i where i.id = item);
  end if;
  insert into public.mps_downloads (item_id, who) values (item, me) on conflict do nothing;
  if found then
    update public.mps_items i set downloads = i.downloads + 1 where i.id = item returning i.downloads into total;
  else
    select i.downloads into total from public.mps_items i where i.id = item;
  end if;
  return total;
end $$;

-- ------------------------------------------------------------------ complaints and moderation

create table if not exists public.mps_reports (
  item_id uuid not null references public.mps_items (id) on delete cascade,
  user_id uuid not null default auth.uid() references public.mps_profiles (id) on delete cascade,
  reason text not null default '',
  created_at timestamptz not null default now(),
  primary key (item_id, user_id),
  constraint mps_reports_reason_ok check (char_length(reason) <= 300)
);

create index if not exists mps_reports_user_idx on public.mps_reports (user_id);

alter table public.mps_reports enable row level security;
drop policy if exists "see own reports" on public.mps_reports;
create policy "see own reports" on public.mps_reports for select to authenticated
  using (user_id = (select auth.uid()) or (select public.mps_is_admin()));
drop policy if exists "report as yourself" on public.mps_reports;
create policy "report as yourself" on public.mps_reports for insert to authenticated with check (user_id = (select auth.uid()));

revoke all on public.mps_reports from anon, authenticated;
grant select on public.mps_reports to authenticated;
grant insert (item_id, reason) on public.mps_reports to authenticated;

create or replace function public.mps_count_report() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  n integer;
  enough integer;
begin
  select count(*) into n from public.mps_reports r where r.item_id = new.item_id;
  select c.hide_after_reports into enough from public.mps_config c;
  update public.mps_items i set reports = n, hidden = i.hidden or n >= coalesce(enough, 3) where i.id = new.item_id;
  return null;
end $$;

drop trigger if exists mps_reports_added on public.mps_reports;
create trigger mps_reports_added after insert on public.mps_reports
  for each row execute function public.mps_count_report();

-- For moderators (mps_profiles.is_admin): hide an item, or show it again and forget the complaints.
create or replace function public.mps_moderate(item uuid, hide boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not public.mps_is_admin() then
    raise exception 'MPS_NOT_ALLOWED' using errcode = 'P0001';
  end if;
  if hide then
    update public.mps_items i set hidden = true where i.id = item;
  else
    delete from public.mps_reports r where r.item_id = item;
    update public.mps_items i set hidden = false, reports = 0 where i.id = item;
  end if;
end $$;

-- Removes the account of the signed-in person with everything they published
-- (the app deletes their files from the storage first).
create or replace function public.mps_delete_account() returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception 'MPS_SIGN_IN' using errcode = 'P0001';
  end if;
  delete from auth.users u where u.id = auth.uid();
end $$;

-- ------------------------------------------------------------------ what the app reads

-- Items together with the name of their author.
drop view if exists public.mps_feed;
create view public.mps_feed with (security_invoker = true) as
select i.id, i.owner, i.kind, i.title, i.description, i.tags, i.file_path, i.preview_path, i.size_bytes,
       i.duration_ms, i.bpm, i.device, i.payload, i.downloads, i.rating_sum, i.rating_count, i.score,
       i.reports, i.hidden, i.created_at, p.username as author, p.avatar as author_avatar
from public.mps_items i join public.mps_profiles p on p.id = i.owner;

-- Authors with the totals shown on their page.
drop view if exists public.mps_authors;
create view public.mps_authors with (security_invoker = true) as
select p.id, p.username, p.bio, p.avatar, p.is_admin, p.created_at,
       count(i.id)::integer as items,
       coalesce(sum(i.downloads), 0)::integer as downloads,
       coalesce(sum(i.rating_sum), 0)::integer as rating_sum,
       coalesce(sum(i.rating_count), 0)::integer as rating_count
from public.mps_profiles p left join public.mps_items i on i.owner = p.id and not i.hidden
group by p.id;

revoke all on public.mps_feed, public.mps_authors from anon, authenticated;
grant select on public.mps_feed, public.mps_authors to anon, authenticated;

-- ------------------------------------------------------------------ files

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('mps-files', 'mps-files', true, 10485760, array['audio/wav', 'audio/x-wav', 'audio/mpeg', 'application/zip'])
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create or replace function public.mps_storage_used(who uuid) returns bigint
language sql stable security definer set search_path = '' as $$
  select coalesce(sum((o.metadata ->> 'size')::bigint), 0)::bigint
  from storage.objects o
  where o.bucket_id = 'mps-files' and o.name like who::text || '/%'
    -- one's own use only (moderators: anybody's)
    and (who = auth.uid() or public.mps_is_admin())
$$;

create or replace function public.mps_storage_total() returns bigint
language sql stable security definer set search_path = '' as $$
  select coalesce(sum((o.metadata ->> 'size')::bigint), 0)::bigint
  from storage.objects o
  where o.bucket_id = 'mps-files'
$$;

-- How much room the signed-in person has used and may use.
create or replace function public.mps_my_storage() returns table (used bigint, quota bigint)
language sql stable security invoker set search_path = '' as $$
  select public.mps_storage_used(auth.uid()), c.user_quota_bytes from public.mps_config c
$$;

drop policy if exists "mps: upload into own folder" on storage.objects;
create policy "mps: upload into own folder" on storage.objects for insert to authenticated with check (
  bucket_id = 'mps-files'
  and (storage.foldername(name))[1] = (select auth.uid())::text
  and public.mps_storage_used((select auth.uid())) < (select c.user_quota_bytes from public.mps_config c)
  and public.mps_storage_total() < (select c.total_quota_bytes from public.mps_config c)
);
drop policy if exists "mps: see own files" on storage.objects;
create policy "mps: see own files" on storage.objects for select to authenticated using (
  bucket_id = 'mps-files' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select public.mps_is_admin()))
);
drop policy if exists "mps: delete own files" on storage.objects;
create policy "mps: delete own files" on storage.objects for delete to authenticated using (
  bucket_id = 'mps-files' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select public.mps_is_admin()))
);

-- ------------------------------------------------------------------ who may call what

revoke all on function public.mps_is_admin() from public, anon, authenticated;
revoke all on function public.mps_username_free(text) from public, anon, authenticated;
revoke all on function public.mps_rate(uuid, integer) from public, anon, authenticated;
revoke all on function public.mps_update_profile(text, text, integer) from public, anon, authenticated;
revoke all on function public.mps_update_item(uuid, text, text, text[]) from public, anon, authenticated;
revoke all on function public.mps_add_download(uuid, text) from public, anon, authenticated;
revoke all on function public.mps_moderate(uuid, boolean) from public, anon, authenticated;
revoke all on function public.mps_delete_account() from public, anon, authenticated;
revoke all on function public.mps_storage_used(uuid) from public, anon, authenticated;
revoke all on function public.mps_storage_total() from public, anon, authenticated;
revoke all on function public.mps_my_storage() from public, anon, authenticated;
revoke all on function public.mps_handle_new_user() from public, anon, authenticated;
revoke all on function public.mps_check_item() from public, anon, authenticated;
revoke all on function public.mps_refresh_rating() from public, anon, authenticated;
revoke all on function public.mps_count_report() from public, anon, authenticated;

grant execute on function public.mps_is_admin() to anon, authenticated;
grant execute on function public.mps_username_free(text) to anon, authenticated;
grant execute on function public.mps_add_download(uuid, text) to anon, authenticated;
grant execute on function public.mps_rate(uuid, integer) to authenticated;
grant execute on function public.mps_update_profile(text, text, integer) to authenticated;
grant execute on function public.mps_update_item(uuid, text, text, text[]) to authenticated;
grant execute on function public.mps_moderate(uuid, boolean) to authenticated;
grant execute on function public.mps_delete_account() to authenticated;
grant execute on function public.mps_my_storage() to authenticated;
-- used inside the storage rules, which run as the person who uploads
grant execute on function public.mps_storage_used(uuid) to authenticated;
grant execute on function public.mps_storage_total() to authenticated;

-- The owner of the project (dashboard, secret key) keeps full access, whatever the default grants of the project are.
grant all on public.mps_config, public.mps_profiles, public.mps_items, public.mps_ratings, public.mps_downloads, public.mps_reports
  to service_role;
grant select on public.mps_feed, public.mps_authors to service_role;

-- tell the API layer about the new tables and functions
notify pgrst, 'reload schema';
