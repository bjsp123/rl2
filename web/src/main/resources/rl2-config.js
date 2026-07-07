// rl2 web configuration - filled in per deployment.
//
// Create a (free) Supabase project, then paste its URL and anon/public key
// here (Dashboard -> Project Settings -> API). Leave both empty to run the
// web build fully offline: sign-in UI is hidden and saves stay in this
// browser's localStorage.
//
// The anon key is safe to ship to the client by design: all data access is
// enforced server-side by Postgres row-level-security policies (see
// backend/supabase/migrations).
window.RL2_CONFIG = {
  supabaseUrl: "https://jrmyoiqnqmljlqnqabyl.supabase.co",
  supabaseAnonKey: "sb_publishable_0G3gOvYfr8W1Q8Q5CRpwIQ_18MnBZ1S"
};
