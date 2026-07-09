# kotoba-lang/webgl

Kotoba runtime package for `kotoba.webgl`.

## Status: thin re-export (2026-07-09)

`src/kotoba/webgl.cljc` (and `src/kotoba/webgl/glsl.cljc`) no longer carry their own
implementation. They re-export
[`kami.webgl`](https://github.com/kotoba-lang/webgpu/blob/main/src/kami/webgl.cljc) from
`kotoba-lang/webgpu`, which is the copy every live consumer (network-isekai, net-babiniku)
actually depends on.

**This one went the opposite direction from `kotoba-lang/sprite-gpu`/`kotoba-lang/gpu`:** this
repo's copy was actually the more complete one — it already had a `:clj` reader-conditional
fallback branch (JVM-safe capability queries, explicit "browser-only executor" errors) that
`kami.webgl` lacked. Rather than throw that away, it was ported into `kami.webgl` first (see
`kotoba-lang/webgpu`'s CHANGELOG.md), and only then did this repo switch to re-exporting it. See
CHANGELOG.md for the full rationale.

Requiring `kotoba.webgl` / `kotoba.webgl.glsl` still works exactly as before — same public API,
same behaviour on both `:clj` and `:cljs`.

## Test

```sh
clojure -M:test
```
