(ns kotoba.webgl
  "DEDUP NOTICE (2026-07-09, see CHANGELOG.md): this namespace used to carry its own copy of the
   WebGL2 renderer. That copy diverged from kotoba-lang/webgpu's internal `kami.webgl` — which
   every live consumer (network-isekai, net-babiniku) actually depends on. Interestingly, the
   result here went the OPPOSITE direction from the other two dedup'd namespaces
   (kotoba.sprite-gpu / kotoba.gpu): THIS repo's copy was the more complete one — it was already
   `.cljc` with a `:clj` fallback branch (JVM-safe capability queries + explicit 'browser-only
   executor' errors) that `kami.webgl` lacked (it was plain `.cljs`, so requiring it on the JVM
   would have thrown an opaque `js/navigator is unbound`-style error). So instead of this repo
   losing to webgpu, that `.cljc` structure was ported INTO webgpu's `kami.webgl` first (see
   kotoba-lang/webgpu's CHANGELOG.md), and NOW that it's at full parity (plus the sky/lit/shadow
   feature work this repo's copy never received — `kami.webgl` had real commits after the restore,
   this repo's copy had none), this namespace re-exports it.

   Requiring `kotoba.webgl` still works exactly as before — same public API, same behaviour on
   both `:clj` and `:cljs` — it just delegates to the canonical implementation one hop away now."
  (:require [kami.webgl :as impl]))

(def webgpu-available? "See kami.webgl/webgpu-available?." impl/webgpu-available?)
(def webgl2-context     "See kami.webgl/webgl2-context."     impl/webgl2-context)
(def pick-backend       "See kami.webgl/pick-backend."       impl/pick-backend)
(def caps               "See kami.webgl/caps."               impl/caps)
(def program            "See kami.webgl/program."            impl/program)
(def sprite-renderer    "See kami.webgl/sprite-renderer."    impl/sprite-renderer)
(def render-2d!         "See kami.webgl/render-2d!."         impl/render-2d!)
(def scene-renderer     "See kami.webgl/scene-renderer."     impl/scene-renderer)
(def lit-renderer       "See kami.webgl/lit-renderer."       impl/lit-renderer)
