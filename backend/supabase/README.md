# rl2 backend (Supabase)

Cloud identity + save sync for the **web** build. Everything server-side lives
here; the game client talks to it through `web/src/main/resources/rl2-bridge.js`.

## One-time setup

1. **Create a project** at [supabase.com](https://supabase.com) (free tier).
2. **Schema:** open the SQL editor and run `migrations/001_kv.sql`
   (or `supabase db push` with the CLI).
3. **Google sign-in:** Dashboard → Authentication → Providers → Google.
   Create OAuth credentials in Google Cloud Console (type: Web application),
   set the authorized redirect URI Supabase shows you, paste client id/secret.
4. **Facebook sign-in:** same page → Facebook. Create an app at
   developers.facebook.com, add the "Facebook Login" product, set the redirect
   URI from Supabase, paste app id/secret. (Facebook app review is required
   before non-developer accounts can sign in — Google-only soft launch works
   fine; the game shows both buttons but Facebook will only accept testers
   until review passes.)
5. **Allowed redirect URLs:** Authentication → URL Configuration → add the
   game's origins, e.g. `http://localhost:8080` for dev and your production
   URL. OAuth returns the player to the exact page they left.
6. **Client config:** paste the project URL + anon key (Project Settings →
   API) into `web/src/main/resources/rl2-config.js`, rebuild
   (`./gradlew :web:buildWeb`). With the config left empty the web build runs
   fully offline: no account button, saves stay in localStorage.

The anon key ships in the client by design; all data access is enforced by the
row-level-security policies in the migration (each user can only touch their
own `kv` rows).

## Data model

One table, `kv(user_id, key, value, client_ts)` — a per-user mirror of the
game's `Persistence` key→blob store. The web client syncs a whitelist of keys
(save slots + metadata, preferences, hall of fame, achievements) with
last-write-wins by `client_ts`; deletions are empty-string tombstones. Save
blobs are gzipped client-side (`gz64:` prefix, ~1.7 MB JSON → typically well
under 200 KB).

## Phase C (not yet built)

Stripe entitlements will add an `entitlements` table plus two Edge Functions
(`create-checkout-session`, `stripe-webhook`) in `functions/`.
