-- Behaviour of schema.sql, checked the way the API would use it: as the roles "anon" and
-- "authenticated", with the id of the person in the JWT claims.
\set ON_ERROR_STOP on
set client_min_messages = warning;
reset role;
drop schema if exists test cascade;
create schema test;
grant usage on schema test to anon, authenticated;
create table test.ids (name text primary key, id uuid not null default gen_random_uuid());
grant select on test.ids to anon, authenticated;
create function test.id(n text) returns uuid language sql stable as $$ select id from test.ids where name = n $$;
create function test.eq(actual anyelement, expected anyelement, label text) returns void language plpgsql as $$
begin
  if actual is distinct from expected then
    raise exception 'TEST FAILED: %: expected %, got %', label, expected, actual;
  end if;
end $$;
-- become a person (or nobody)
create function test.be(n text) returns void language plpgsql as $$
begin
  if n is null then
    perform set_config('request.jwt.claims', '', false);
  else
    perform set_config('request.jwt.claims', json_build_object('sub', test.id(n), 'role', 'authenticated')::text, false);
  end if;
end $$;
grant execute on all functions in schema test to anon, authenticated;

-- clean slate
delete from auth.users;
delete from storage.objects;
update public.app_config set user_quota_bytes = 52428800, total_quota_bytes = 943718400, hide_after_reports = 3, daily_items = 40;

insert into test.ids (name) values ('alice'), ('bob'), ('carol'), ('dave'), ('eve'), ('admin'), ('google'), ('clash'), ('bad');

-- ---------------------------------------------------------------- accounts
insert into auth.users (id, email, raw_user_meta_data) values
  (test.id('alice'), 'alice@x.io', '{"username": "Alice"}'),
  (test.id('bob'), 'bob@x.io', '{"username": "bob"}'),
  (test.id('carol'), 'carol@x.io', '{"username": "Кэрол"}'),
  (test.id('dave'), 'dave@x.io', '{"username": "dave"}'),
  (test.id('eve'), 'eve@x.io', '{"username": "eve"}'),
  (test.id('admin'), 'admin@x.io', '{"username": "boss"}'),
  (test.id('google'), 'g@x.io', '{"name": "Bob  Smith", "email": "g@x.io"}'),
  (test.id('clash'), 'clash@x.io', '{"username": "ALICE"}'),
  (test.id('bad'), 'bad@x.io', '{"username": "a b"}');
update public.profiles set is_admin = true where id = test.id('admin');

select test.eq((select username from public.profiles where id = test.id('alice')), 'Alice', 'name from sign-up');
select test.eq((select username from public.profiles where id = test.id('carol')), 'Кэрол', 'cyrillic name');
select test.eq((select username from public.profiles where id = test.id('google')), 'Bob_Smith', 'name from Google');
select test.eq((select username like 'user\_%' from public.profiles where id = test.id('clash')), true, 'taken name is replaced');
select test.eq((select username like 'user\_%' from public.profiles where id = test.id('bad')), true, 'bad name is replaced');
select test.eq(public.username_free('alice'), false, 'username_free is case-insensitive');
select test.eq(public.username_free('nobody'), true, 'username_free');

-- ---------------------------------------------------------------- nobody (anon)
set role anon;
select test.be(null);
select test.eq((select count(*)::int from public.feed), 0, 'anon reads the empty feed');
do $$ begin
  begin
    insert into public.items (kind, title, device, payload) values ('preset', 'Nope', 'synth', '{}');
    raise exception 'TEST FAILED: anon could publish';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.rate(gen_random_uuid(), 5);
    raise exception 'TEST FAILED: anon could rate';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.delete_account();
    raise exception 'TEST FAILED: anon could call delete_account';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;

-- ---------------------------------------------------------------- publishing
set role authenticated;
select test.be('alice');
insert into public.items (kind, title, tags, device, payload)
  values ('preset', 'Warm keys', '{" Keys ", "keys", "WARM", ""}', 'fm', '{"params": {"ratio": 2}}');
insert into public.items (kind, title, file_path, size_bytes, duration_ms)
  values ('sound', 'Alice kick', test.id('alice')::text || '/kick.wav', 40000, 400);
select test.eq((select owner from public.items where title = 'Warm keys'), test.id('alice'), 'owner is the signed-in person');
select test.eq((select tags from public.items where title = 'Warm keys'), '{keys,warm}'::text[], 'tags are tidied');
do $$ begin
  begin
    insert into public.items (kind, title, device, payload, downloads) values ('preset', 'Cheat', 'fm', '{}', 999);
    raise exception 'TEST FAILED: counters can be set on insert';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into public.items (kind, title, device, payload, owner) values ('preset', 'As Bob', 'fm', '{}', test.id('bob'));
    raise exception 'TEST FAILED: can publish as somebody else';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into public.items (kind, title, file_path) values ('sound', 'Foreign file', test.id('bob')::text || '/x.wav');
    raise exception 'TEST FAILED: can point at a file of somebody else';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into public.items (kind, title, device, payload, file_path) values ('preset', 'Preset with file', 'fm', '{}', test.id('alice')::text || '/x.wav');
    raise exception 'TEST FAILED: preset with a file';
  exception when check_violation then null;
  end;
  begin
    insert into public.items (kind, title) values ('sound', 'No file');
    raise exception 'TEST FAILED: sound without a file';
  exception when check_violation then null;
  end;
  begin
    insert into public.items (kind, title, device, payload) values ('preset', 'x', 'fm', '{}');
    raise exception 'TEST FAILED: one-letter title';
  exception when check_violation then null;
  end;
  begin
    insert into public.items (kind, title, device, payload) values ('video', 'Wrong kind', 'fm', '{}');
    raise exception 'TEST FAILED: unknown kind';
  exception when check_violation then null;
  end;
  begin
    update public.items set downloads = 5 where title = 'Warm keys';
    raise exception 'TEST FAILED: counters can be updated';
  exception when insufficient_privilege then null;
  end;
  begin
    update public.items set hidden = false where title = 'Warm keys';
    raise exception 'TEST FAILED: hidden can be updated';
  exception when insufficient_privilege then null;
  end;
end $$;
update public.items set title = 'Warm keys 2', description = 'soft' where title = 'Warm keys';
select test.eq((select count(*)::int from public.items where title = 'Warm keys 2'), 1, 'own title can be changed');

select public.update_item((select id from public.items where title = 'Warm keys 2'), ' Warm keys 2 ', 'softer', '{"Pad", "pad ", "Soft"}');
select test.eq((select tags from public.items where title = 'Warm keys 2'), '{pad,soft}'::text[], 'update_item tidies tags');
select test.eq((select description from public.items where title = 'Warm keys 2'), 'softer', 'update_item');

-- somebody else cannot edit or delete it
select test.be('bob');
do $$ begin
  begin
    perform public.update_item((select id from public.items where title = 'Warm keys 2'), 'Hacked', '', '{}');
    raise exception 'TEST FAILED: update_item on the work of somebody else';
  exception when raise_exception then
    if sqlerrm <> 'MPS_NOT_ALLOWED' then raise exception 'TEST FAILED: %', sqlerrm; end if;
  end;
end $$;
update public.items set title = 'Hacked' where title = 'Warm keys 2';
delete from public.items where title = 'Warm keys 2';
select test.eq((select count(*)::int from public.items where title = 'Warm keys 2'), 1, 'others cannot edit or delete');

-- ---------------------------------------------------------------- ratings
select test.eq((select rating_count from public.rate((select id from public.items where title = 'Warm keys 2'), 5)), 1, 'first mark');
select test.eq((select rating_sum from public.items where title = 'Warm keys 2'), 5, 'sum after first mark');
select test.eq((select round(score::numeric, 2) from public.items where title = 'Warm keys 2'), 4.00, 'score is pulled to the middle');
select test.eq((select rating_sum from public.rate((select id from public.items where title = 'Warm keys 2'), 3)), 3, 'mark changed, not added');
select test.eq((select rating_count from public.items where title = 'Warm keys 2'), 1, 'still one mark');
select test.eq((select rating_sum from public.rate((select id from public.items where title = 'Warm keys 2'), 9)), 5, 'marks above five are capped');
select test.be('carol');
select test.eq((select rating_sum from public.rate((select id from public.items where title = 'Warm keys 2'), 4)), 9, 'second person');
select test.eq((select count(*)::int from public.ratings), 1, 'people see only their own marks');
select test.be('bob');
select test.eq((select rating_count from public.rate((select id from public.items where title = 'Warm keys 2'), 0)), 1, 'mark removed');
select test.eq((select rating_sum from public.items where title = 'Warm keys 2'), 4, 'sum after removal');
select test.be('alice');
do $$ begin
  begin
    perform public.rate((select id from public.items where title = 'Warm keys 2'), 5);
    raise exception 'TEST FAILED: own work can be rated';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into public.ratings (item_id, stars, user_id) values ((select id from public.items where title = 'Alice kick'), 5, test.id('bob'));
    raise exception 'TEST FAILED: rating in the name of somebody else';
  exception when insufficient_privilege then null;
  end;
end $$;

-- ---------------------------------------------------------------- downloads
reset role;
set role anon;
select test.be(null);
select test.eq(public.add_download((select id from public.items where title = 'Alice kick'), 'device-0001'), 1, 'anonymous download');
select test.eq(public.add_download((select id from public.items where title = 'Alice kick'), 'device-0001'), 1, 'same device again');
select test.eq(public.add_download((select id from public.items where title = 'Alice kick'), 'device-0002'), 2, 'another device');
select test.eq(public.add_download((select id from public.items where title = 'Alice kick'), 'x'), 2, 'no device id: not counted');
do $$ begin
  begin
    perform count(*) from public.downloads;
    raise exception 'TEST FAILED: downloads table is readable';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;
set role authenticated;
select test.be('bob');
select test.eq(public.add_download((select id from public.items where title = 'Alice kick')), 3, 'signed-in download');
select test.eq(public.add_download((select id from public.items where title = 'Alice kick')), 3, 'signed-in again');

-- ---------------------------------------------------------------- profile editing
update public.profiles set bio = 'I make beats', avatar = 3 where id = test.id('bob');
select test.eq((select bio from public.profiles where id = test.id('bob')), 'I make beats', 'own profile');
select public.update_profile(' Bobby ', 'Beats and bass', 5);
select test.eq((select username || '|' || bio || '|' || avatar from public.profiles where id = test.id('bob')), 'Bobby|Beats and bass|5', 'update_profile');
do $$ begin
  begin
    perform public.update_profile('alice', '', 0);
    raise exception 'TEST FAILED: update_profile with a taken name';
  exception when unique_violation then null;
  end;
  begin
    perform public.update_profile('a b', '', 0);
    raise exception 'TEST FAILED: update_profile with a bad name';
  exception when check_violation then null;
  end;
end $$;
update public.profiles set bio = 'hacked' where id = test.id('alice');
select test.eq((select bio from public.profiles where id = test.id('alice')), '', 'profile of somebody else');
do $$ begin
  begin
    update public.profiles set username = 'ALICE' where id = test.id('bob');
    raise exception 'TEST FAILED: taken name';
  exception when unique_violation then null;
  end;
  begin
    update public.profiles set username = 'x' where id = test.id('bob');
    raise exception 'TEST FAILED: short name';
  exception when check_violation then null;
  end;
  begin
    update public.profiles set is_admin = true where id = test.id('bob');
    raise exception 'TEST FAILED: anyone can become a moderator';
  exception when insufficient_privilege then null;
  end;
end $$;

-- ---------------------------------------------------------------- complaints
insert into public.reports (item_id, reason) values ((select id from public.items where title = 'Alice kick'), 'stolen');
do $$ begin
  begin
    insert into public.reports (item_id, reason) values ((select id from public.items where title = 'Alice kick'), 'again');
    raise exception 'TEST FAILED: two complaints from one person';
  exception when unique_violation then null;
  end;
  begin
    perform public.moderate((select id from public.items where title = 'Alice kick'), true);
    raise exception 'TEST FAILED: anyone can moderate';
  exception when raise_exception then
    if sqlerrm <> 'MPS_NOT_ALLOWED' then raise exception 'TEST FAILED: %', sqlerrm; end if;
  end;
end $$;
select test.be('carol');
insert into public.reports (item_id, reason) values ((select id from public.items where title = 'Alice kick'), '');
select test.eq((select hidden from public.items where title = 'Alice kick'), false, 'two complaints do not hide');
select test.be('dave');
insert into public.reports (item_id) values ((select id from public.items where title = 'Alice kick'));
select test.eq((select count(*)::int from public.feed where title = 'Alice kick'), 0, 'hidden after three complaints');
select test.eq((select count(*)::int from public.reports), 1, 'people see only their own complaints');
select test.be('alice');
select test.eq((select count(*)::int from public.feed where title = 'Alice kick' and hidden and reports = 3), 1, 'the author still sees it');
select test.be('admin');
select test.eq((select count(*)::int from public.feed where title = 'Alice kick'), 1, 'a moderator sees it');
select test.eq((select count(*)::int from public.reports), 3, 'a moderator sees the complaints');
select public.moderate((select id from public.items where title = 'Alice kick'), false);
select test.be('eve');
select test.eq((select count(*)::int from public.feed where title = 'Alice kick' and reports = 0), 1, 'shown again');
select test.be('admin');
select public.moderate((select id from public.items where title = 'Alice kick'), true);
reset role;
set role anon;
select test.be(null);
select test.eq((select count(*)::int from public.feed where title = 'Alice kick'), 0, 'hidden by a moderator');
select test.eq((select items from public.authors where username = 'Alice'), 1, 'hidden items are not in the totals');
reset role;
set role authenticated;
select test.be('admin');
select public.moderate((select id from public.items where title = 'Alice kick'), false);
select test.eq((select downloads from public.authors where username = 'Alice'), 3, 'author totals: downloads');
select test.eq((select rating_sum from public.authors where username = 'Alice'), 4, 'author totals: marks');

-- ---------------------------------------------------------------- files
select test.be('alice');
insert into storage.objects (bucket_id, name, metadata) values ('files', test.id('alice')::text || '/a.wav', '{"size": 3000000}');
do $$ begin
  begin
    insert into storage.objects (bucket_id, name, metadata) values ('files', test.id('bob')::text || '/a.wav', '{"size": 10}');
    raise exception 'TEST FAILED: upload into a folder of somebody else';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into storage.objects (bucket_id, name, metadata) values ('other', test.id('alice')::text || '/a.wav', '{"size": 10}');
    raise exception 'TEST FAILED: upload into another bucket';
  exception when insufficient_privilege or foreign_key_violation then null;
  end;
end $$;
select test.eq((select used from public.my_storage()), 3000000::bigint, 'used space');
reset role;
update public.app_config set user_quota_bytes = 2000000;
set role authenticated;
select test.be('alice');
do $$ begin
  begin
    insert into storage.objects (bucket_id, name, metadata) values ('files', test.id('alice')::text || '/b.wav', '{"size": 10}');
    raise exception 'TEST FAILED: upload over the personal quota';
  exception when insufficient_privilege then null;
  end;
end $$;
select test.be('bob');
insert into storage.objects (bucket_id, name, metadata) values ('files', test.id('bob')::text || '/b.wav', '{"size": 500}');
delete from storage.objects where name like test.id('alice')::text || '/%';
reset role;
select test.eq((select count(*)::int from storage.objects), 2, 'files of others cannot be deleted');
update public.app_config set user_quota_bytes = 52428800, total_quota_bytes = 1000;
set role authenticated;
select test.be('bob');
do $$ begin
  begin
    insert into storage.objects (bucket_id, name, metadata) values ('files', test.id('bob')::text || '/c.wav', '{"size": 10}');
    raise exception 'TEST FAILED: upload when the bucket is full';
  exception when insufficient_privilege then null;
  end;
end $$;
select test.be('admin');
delete from storage.objects where name like test.id('bob')::text || '/%';
reset role;
select test.eq((select count(*)::int from storage.objects), 1, 'a moderator can delete files');
update public.app_config set total_quota_bytes = 943718400;

-- ---------------------------------------------------------------- daily limit
update public.app_config set daily_items = 2;
set role authenticated;
select test.be('eve');
insert into public.items (kind, title, device, payload) values ('preset', 'Eve 1', 'synth', '{}'), ('preset', 'Eve 2', 'synth', '{}');
do $$ begin
  begin
    insert into public.items (kind, title, device, payload) values ('preset', 'Eve 3', 'synth', '{}');
    raise exception 'TEST FAILED: daily limit';
  exception when raise_exception then
    if sqlerrm <> 'MPS_DAILY_LIMIT' then raise exception 'TEST FAILED: wrong message %', sqlerrm; end if;
  end;
end $$;
reset role;
update public.app_config set daily_items = 40;

-- ---------------------------------------------------------------- deleting things
set role authenticated;
select test.be('admin');
delete from public.items where title = 'Eve 2';
select test.eq((select count(*)::int from public.items where title = 'Eve 2'), 0, 'a moderator can delete items');
select test.be('carol');
select test.eq((select count(*)::int from public.ratings), 1, 'carol has a mark on the work of alice');
select test.be('alice');
select public.delete_account();
reset role;
select test.eq((select count(*)::int from auth.users where id = test.id('alice')), 0, 'account is gone');
select test.eq((select count(*)::int from public.items where owner = test.id('alice')), 0, 'its items are gone');
select test.eq((select count(*)::int from public.ratings), 0, 'marks on its items are gone');
select test.eq((select count(*)::int from public.profiles), 8, 'the others are still there');
-- a person who rated something and then leaves: the mark goes, the totals follow
set role authenticated;
select test.be('bob');
select public.rate((select id from public.items where title = 'Eve 1'), 5);
select test.be('carol');
select public.rate((select id from public.items where title = 'Eve 1'), 2);
select public.delete_account();
reset role;
select test.eq((select rating_sum from public.items where title = 'Eve 1'), 5, 'totals after a rater left');
select test.eq((select rating_count from public.items where title = 'Eve 1'), 1, 'count after a rater left');

select 'ALL SQL TESTS PASSED' as result;
