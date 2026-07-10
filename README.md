# kotoba-lang/webgl

**SSoT for `kami.webgl`** — WebGL2 browser executor for the same EDN render-IR as
`kami.webgpu`. Capability queries are `.cljc` (JVM-safe); draw paths are `:cljs`.

Depends on `gpu` (`kami.gpu` resolve) and `sprite-gpu` (SDF quads). Does **not**
depend on `webgpu` (avoids the inverted cycle from the pre-addendum-6 dedup).

`kotoba.webgl` / `kotoba.webgl.glsl` are thin facades.

See ADR-2607102200 addendum 6.

## Test

```sh
clojure -M:test
```
