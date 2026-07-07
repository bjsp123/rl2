-- rl2 cloud persistence: one key->blob row per (user, key), mirroring the
-- game's Persistence interface. Values are plain text; large blobs (saves)
-- arrive gzip+base64 with a "gz64:" prefix (the client compresses/expands).
-- client_ts is the writing device's wall clock - the last-write-wins key the
-- web client's sync layer compares (see web/.../CloudSyncingPersistence.java).
create table if not exists public.kv (
    user_id   uuid not null references auth.users (id) on delete cascade,
    key       text not null,
    value     text not null default '',
    client_ts bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, key)
);

alter table public.kv enable row level security;

-- Each user reads/writes only their own rows. The client uses the anon key +
-- the user's JWT; these policies are the entire access-control story.
create policy "kv select own" on public.kv
    for select using (auth.uid() = user_id);
create policy "kv insert own" on public.kv
    for insert with check (auth.uid() = user_id);
create policy "kv update own" on public.kv
    for update using (auth.uid() = user_id);
create policy "kv delete own" on public.kv
    for delete using (auth.uid() = user_id);

-- Keep updated_at fresh on upsert.
create or replace function public.kv_touch_updated_at()
returns trigger language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end $$;

drop trigger if exists kv_touch on public.kv;
create trigger kv_touch before update on public.kv
    for each row execute function public.kv_touch_updated_at();
