;; DEDUP NOTICE (2026-07-09, see ../../../CHANGELOG.md): re-exports kami.webgl.glsl
;; (kotoba-lang/webgpu) — the committed `bb gen-glsl` output was byte-identical between the two
;; repos already, so this just points at the one copy instead of carrying a second one that could
;; drift out of sync with the shaders kami.webgl actually links against.
(ns kotoba.webgl.glsl
  (:require [kami.webgl.glsl :as impl]))

(def sprite-vert "See kami.webgl.glsl/sprite-vert." impl/sprite-vert)
(def sprite-frag "See kami.webgl.glsl/sprite-frag." impl/sprite-frag)
(def sky-vert    "See kami.webgl.glsl/sky-vert."    impl/sky-vert)
(def sky-frag    "See kami.webgl.glsl/sky-frag."    impl/sky-frag)
