(ns kami.webgl
  "kami-webgl — a WebGL2 renderer driven by the SAME EDN as kami.webgpu, for browsers without
   WebGPU. It consumes the same render-IR (globals + instances) and the same render-graph (resolved
   through kami.gpu for the :webgl2 tier, which drops compute passes), running the GLSL ES 3.00
   shaders that `bb gen-glsl` transpiles from the one EDN shader (kami.wgsl → naga → GLSL).

   The naga GLSL convention this binds to: per-instance vertex inputs at `layout(location=0..)`,
   uniforms in a std140 block, the full-quad corners from gl_VertexID. So `(pick-backend)` selects
   WebGPU when navigator.gpu exists and falls back here otherwise — one EDN, two GPU runtimes."
  (:require [kami.gpu :as gpu]
            [kami.sprite-gpu :as sg]
            [kami.webgl.glsl :as glsl]))

(defn interleave-mesh-vertices
  "Flatten parallel position/normal/UV vectors to the canonical p3+n3+uv2
  scalar stream used by both upload and CPU-skin updates."
  [positions normals uvs]
  (vec (mapcat (fn [position normal uv]
                 (concat position normal uv))
               positions normals uvs)))

;; `.cljc`: the CLJS branch is the real browser WebGL2 executor; the CLJ branch is a JVM-safe stand-
;; in (ported from kotoba-lang/webgl's `kotoba.webgl`, ADR/CHANGELOG.md — that repo's copy carried
;; this platform split, kami.webgl.cljs here didn't, so this repo silently regressed portability
;; when the two diverged). Requiring this namespace on the JVM (e.g. from a `bb`/`clj` test or REPL
;; that transitively pulls it in) no longer throws an opaque "js/navigator is unbound" — the
;; capability-query fns (`webgpu-available?`, `pick-backend`, `caps`) return real, useful JVM
;; answers, and the browser-only executors fail fast with a clear `ex-info` instead.
#?(:cljs
   (do

;; ── backend selection ────────────────────────────────────────────────────────────────────────────
(defn webgpu-available? [] (boolean (and js/navigator (.-gpu js/navigator))))

(defn webgl2-context
  "A WebGL2 rendering context for the canvas (premultiplied alpha, antialias), or nil."
  [canvas]
  (.getContext canvas "webgl2" #js {:antialias true :premultipliedAlpha true}))

(defn pick-backend
  "The best available GPU backend for this browser: :webgpu if WebGPU is present, else :webgl2.
   Both consume the same render-IR; the caller routes to kami.webgpu or kami.webgl accordingly."
  []
  (if (webgpu-available?) :webgpu :webgl2))

(defn caps
  "The kami.gpu capability tier for a running WebGL2 context (no compute / no storage, instancing
   via ANGLE_instanced_arrays core in WebGL2)."
  [_gl]
  (gpu/caps-from-device :webgl2 {:compute false :storage false :instancing true}))

;; ── GLSL program compilation ───────────────────────────────────────────────────────────────────
(defn- compile-shader [gl kind src]
  (let [s (.createShader gl kind)]
    (.shaderSource gl s src)
    (.compileShader gl s)
    (when-not (.getShaderParameter gl s (.-COMPILE_STATUS gl))
      (throw (ex-info (str "GLSL compile error:\n" (.getShaderInfoLog gl s)) {:src src})))
    s))

(defn program
  "Compile + link a GLSL ES 3.00 program from vertex/fragment source (as produced by bb gen-glsl).
   Throws with the info log on failure."
  [gl vsrc fsrc]
  (let [p  (.createProgram gl)
        vs (compile-shader gl (.-VERTEX_SHADER gl) vsrc)
        fs (compile-shader gl (.-FRAGMENT_SHADER gl) fsrc)]
    (.attachShader gl p vs) (.attachShader gl p fs) (.linkProgram gl p)
    (when-not (.getProgramParameter gl p (.-LINK_STATUS gl))
      (throw (ex-info (str "GLSL link error:\n" (.getProgramInfoLog gl p)) {})))
    p))

;; ── arbitrary indexed 3D mesh fallback ──────────────────────────────────────
(def ^:private mesh-vertex-shader
  "#version 300 es
precision highp float;
layout(location=0) in vec3 a_position;
layout(location=1) in vec3 a_normal;
layout(location=7) in vec2 a_uv;
layout(location=2) in vec4 a_mvp0;
layout(location=3) in vec4 a_mvp1;
layout(location=4) in vec4 a_mvp2;
layout(location=5) in vec4 a_mvp3;
layout(location=6) in vec4 a_color;
uniform vec2 u_uv_offset;
uniform vec2 u_uv_scale;
out vec3 v_normal;
out vec3 v_color;
out vec2 v_uv;
void main(){ mat4 mvp=mat4(a_mvp0,a_mvp1,a_mvp2,a_mvp3); gl_Position=mvp*vec4(a_position,1.0); v_normal=a_normal; v_color=a_color.rgb; v_uv=a_uv*u_uv_scale+u_uv_offset; }")
(def ^:private mesh-fragment-shader
  "#version 300 es
precision highp float;
in vec3 v_normal;
in vec3 v_color;
in vec2 v_uv;
uniform vec2 u_material;
uniform vec3 u_shading;
uniform sampler2D u_base_color;
out vec4 out_color;
void main(){ vec4 base=texture(u_base_color,v_uv)*vec4(v_color,1.0); vec3 n=normalize(v_normal); vec3 light=normalize(vec3(0.4,0.8,0.6)); float ndl=max(dot(n,light),0.0); float smooth_w=max(u_shading.z,0.0001); float toon_edge=smoothstep(u_shading.y-smooth_w,u_shading.y+smooth_w,ndl); float toon_l=mix(0.32,1.0,toon_edge); float continuous_l=0.25+0.75*ndl; float l=mix(continuous_l,toon_l,step(0.5,u_shading.x)); float metallic=clamp(u_material.x,0.0,1.0); float roughness=clamp(u_material.y,0.04,1.0); vec3 h=normalize(light+vec3(0.0,0.0,1.0)); float spec=pow(max(dot(n,h),0.0),mix(128.0,2.0,roughness))*ndl; vec3 f0=mix(vec3(0.04),base.rgb,metallic); out_color=vec4(base.rgb*l*(1.0-metallic*0.45)+f0*spec,base.a); }")

(defn init-mesh-viewport!
  "Initialize the canonical arbitrary-mesh WebGL2 fallback for a canvas."
  [canvas]
  (when-let [gl (webgl2-context canvas)]
    (let [width (max 1 (.-clientWidth canvas)) height (max 1 (.-clientHeight canvas))
          prog (program gl mesh-vertex-shader mesh-fragment-shader)
          instance-buffer (.createBuffer gl)
          white-texture (.createTexture gl)]
      (set! (.-width canvas) width) (set! (.-height canvas) height)
      (.enable gl (.-DEPTH_TEST gl))
      (.bindTexture gl (.-TEXTURE_2D gl) white-texture)
      (.texImage2D gl (.-TEXTURE_2D gl) 0 (.-RGBA gl) 1 1 0 (.-RGBA gl)
                   (.-UNSIGNED_BYTE gl) (js/Uint8Array. #js [255 255 255 255]))
      ;; WebGL's default TEXTURE_MIN_FILTER requires a complete mip chain.
      ;; This fallback texture intentionally has only level 0; without an
      ;; explicit non-mipmap filter every untextured draw fails with
      ;; GL_INVALID_OPERATION at drawElementsInstanced (the whole VRM frame
      ;; disappeared even though fetch/parse/upload reported ready).
      (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_MIN_FILTER gl) (.-LINEAR gl))
      (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_MAG_FILTER gl) (.-LINEAR gl))
      {:backend :webgl2 :gl gl :program prog :instance-buffer instance-buffer
       :white-texture white-texture :width width :height height})))

(defn upload-mesh!
  "Upload {:positions :normals :indices} to a fallback viewport."
  [{:keys [gl]} {:keys [positions normals uvs indices joints weights] :as geometry}]
  (let [vao (.createVertexArray gl) vertex-buffer (.createBuffer gl) index-buffer (.createBuffer gl)
        instance-buffer (.createBuffer gl)
        uvs (if (seq uvs) uvs (repeat (count positions) [0.0 0.0]))
        ;; One vertex is eight scalar floats. `mapcat concat (map vector ...)`
        ;; leaves p/n/uv as three nested arrays; Float32Array coerces each array
        ;; to NaN and allocates only 12 bytes per vertex. The VAO advertises a
        ;; 32-byte stride, so WebGL correctly rejects every indexed draw.
        vertices (js/Float32Array.
                  (clj->js (interleave-mesh-vertices positions normals uvs)))
        index-data (js/Uint32Array. (clj->js indices))]
    (.bindVertexArray gl vao)
    (.bindBuffer gl (.-ARRAY_BUFFER gl) vertex-buffer)
    (.bufferData gl (.-ARRAY_BUFFER gl) vertices (.-STATIC_DRAW gl))
    (doseq [[location size offset] [[0 3 0] [1 3 12] [7 2 24]]]
      (.enableVertexAttribArray gl location)
      (.vertexAttribPointer gl location size (.-FLOAT gl) false 32 offset))
    (.bindBuffer gl (.-ELEMENT_ARRAY_BUFFER gl) index-buffer)
    (.bufferData gl (.-ELEMENT_ARRAY_BUFFER gl) index-data (.-STATIC_DRAW gl))
    (.bindBuffer gl (.-ARRAY_BUFFER gl) instance-buffer)
    (doseq [[location size offset] [[2 4 0] [3 4 16] [4 4 32] [5 4 48] [6 4 64]]]
      (.enableVertexAttribArray gl location)
      (.vertexAttribPointer gl location size (.-FLOAT gl) false 80 offset)
      (.vertexAttribDivisor gl location 1))
    (.bindVertexArray gl nil)
    {:vao vao :vertex-buffer vertex-buffer :index-buffer index-buffer :instance-buffer instance-buffer
     :instance-cache (atom nil) :index-count (count indices)
     :geometry geometry :positions positions :normals normals :uvs uvs :joints joints :weights weights}))

(defn upload-texture!
  "Decode an embedded VRM image and upload it as a WebGL2 texture."
  [{:keys [gl]} {:keys [bytes mime-type]}]
  (-> (js/createImageBitmap
       (js/Blob. #js [(js/Uint8Array. (clj->js (vec bytes)))] #js {:type mime-type}))
      (.then (fn [bitmap]
               (let [texture (.createTexture gl)]
                 (.bindTexture gl (.-TEXTURE_2D gl) texture)
                 (.pixelStorei gl (.-UNPACK_FLIP_Y_WEBGL gl) true)
                 (.texImage2D gl (.-TEXTURE_2D gl) 0 (.-RGBA gl)
                              (.-RGBA gl) (.-UNSIGNED_BYTE gl) bitmap)
                 (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_MIN_FILTER gl) (.-LINEAR_MIPMAP_LINEAR gl))
                 (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_MAG_FILTER gl) (.-LINEAR gl))
                 (.generateMipmap gl (.-TEXTURE_2D gl))
                 texture)))))

(defn render-mesh-scene!
  "Render fallback meshes with one instanced call per shared geometry. Each
  draw is {:buffers :mvp :color}; MVP is a column-major Float32Array."
  [{:keys [gl program white-texture width height]} draws]
  (.viewport gl 0 0 width height)
  (.clearColor gl 0.035 0.055 0.10 1.0)
  (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
  (.useProgram gl program)
  (.uniform1i gl (.getUniformLocation gl program "u_base_color") 0)
  (doseq [[_ group] (group-by :buffers draws)]
    (let [{:keys [buffers]} (first group) {:keys [vao index-count instance-buffer instance-cache]} buffers
          material (:material (first group))
          [uv-x uv-y] (or (:uv-offset material) [0.0 0.0])
          [uv-sx uv-sy] (or (:uv-scale material) [1.0 1.0])
          cached @instance-cache
          cache-hit? (= group (:draws cached))
          packed (if cache-hit? (:packed cached)
                   (js/Float32Array.
                    (clj->js (mapcat (fn [{:keys [mvp color]}]
                                       (concat (array-seq mvp) color [1.0])) group))))]
      (when-not cache-hit? (reset! instance-cache {:draws group :packed packed}))
      (.uniform2f gl (.getUniformLocation gl program "u_material")
                  (or (:metallic material) 0.0) (or (:roughness material) 0.5))
      (.uniform3f gl (.getUniformLocation gl program "u_shading")
                  (or (:shade-kind material) 0.0)
                  (or (:toon-threshold material) 0.4)
                  (or (:toon-smooth material) 0.08))
      (.uniform2f gl (.getUniformLocation gl program "u_uv_offset") uv-x uv-y)
      (.uniform2f gl (.getUniformLocation gl program "u_uv_scale") uv-sx uv-sy)
      (.bindBuffer gl (.-ARRAY_BUFFER gl) instance-buffer)
      (.activeTexture gl (.-TEXTURE0 gl))
      (.bindTexture gl (.-TEXTURE_2D gl)
                    (or (get-in (first group) [:material :texture]) white-texture))
      (when-not cache-hit? (.bufferData gl (.-ARRAY_BUFFER gl) packed (.-DYNAMIC_DRAW gl)))
      (.bindVertexArray gl vao)
      (.drawElementsInstanced gl (.-TRIANGLES gl) index-count (.-UNSIGNED_INT gl) 0 (count group))))
  (.bindVertexArray gl nil))

(defn render-mesh-frame!
  "Render one fallback mesh frame."
  [viewport buffers mvp color]
  (render-mesh-scene! viewport [{:buffers buffers :mvp mvp :color color}]))

(defn- transform-joint [matrix [x y z] direction?]
  [(+ (* (nth matrix 0) x) (* (nth matrix 4) y) (* (nth matrix 8) z) (if direction? 0 (nth matrix 12)))
   (+ (* (nth matrix 1) x) (* (nth matrix 5) y) (* (nth matrix 9) z) (if direction? 0 (nth matrix 13)))
   (+ (* (nth matrix 2) x) (* (nth matrix 6) y) (* (nth matrix 10) z) (if direction? 0 (nth matrix 14)))])

(defn render-skinned-mesh-frame!
  "WebGL2 compatibility skinning. Updates the existing vertex buffer from
  immutable source geometry, then uses the canonical mesh scene renderer."
  [{:keys [gl] :as viewport} {:keys [positions normals joints weights vertex-buffer] :as buffers}
   mvp color joint-matrices]
  (let [skin (fn [value joint-ids joint-weights direction?]
               (reduce (fn [result [joint weight]]
                         (mapv + result (mapv #(* weight %) (transform-joint (nth joint-matrices joint) value direction?))))
                       [0 0 0] (map vector joint-ids joint-weights)))
        skinned-positions (mapv skin positions joints weights (repeat false))
        skinned-normals (mapv skin normals joints weights (repeat true))
        vertices (js/Float32Array.
                  (clj->js (interleave-mesh-vertices
                            skinned-positions skinned-normals (:uvs buffers))))]
    (.bindBuffer gl (.-ARRAY_BUFFER gl) vertex-buffer)
    (.bufferSubData gl (.-ARRAY_BUFFER gl) 0 vertices)
    (render-mesh-scene! viewport [{:buffers buffers :mvp mvp :color color}])))

(defn skin-mesh!
  "CPU-skin one uploaded mesh and return its buffers for a shared scene pass."
  [{:keys [gl]} {:keys [positions normals joints weights vertex-buffer] :as buffers} joint-matrices]
  (let [skin (fn [value joint-ids joint-weights direction?]
               (reduce (fn [result [joint weight]]
                         (mapv + result (mapv #(* weight %) (transform-joint (nth joint-matrices joint) value direction?))))
                       [0 0 0] (map vector joint-ids joint-weights)))
        vertices (js/Float32Array.
                  (clj->js (interleave-mesh-vertices
                            (mapv skin positions joints weights (repeat false))
                            (mapv skin normals joints weights (repeat true))
                            (:uvs buffers))))]
    (.bindBuffer gl (.-ARRAY_BUFFER gl) vertex-buffer)
    (.bufferSubData gl (.-ARRAY_BUFFER gl) 0 vertices)
    buffers))

(defn render-skinned-mesh-scene! [viewport draws]
  (render-mesh-scene! viewport
    (mapv (fn [{:keys [buffers joint-matrices] :as draw}]
            (assoc draw :buffers
                   (if (and (seq (:joints buffers)) (seq (:weights buffers))
                            (seq joint-matrices))
                     (skin-mesh! viewport buffers joint-matrices)
                     buffers)))
          draws)))

;; ── 2D sprite pass: instanced SDF quads (the GPU-2D path, identical output to WebGPU) ───────────
;; instance layout = kami.sprite-gpu/pack-instances: 12 floats — ipos(2) isize(2) irot(1) ishape(1)
;; icolor(4) pad(2); 48-byte stride. Quad corners come from gl_VertexID (6 verts), no corner buffer.
(def ^:private F4 4)
(defn sprite-renderer
  "Build a 2D-sprite draw fn for this WebGL2 context from the generated GLSL (sprite.vert/.frag).
   The returned `(draw! quad-instances [w h])` packs + uploads the instances and issues one
   instanced draw — the whole 2D frame in a single call, rendering the SDF shapes on the GPU."
  [gl & [{:keys [vert frag] :or {vert glsl/sprite-vert frag glsl/sprite-frag}}]]
  (let [prog (program gl vert frag)
        vao  (.createVertexArray gl)
        ibuf (.createBuffer gl)
        ublk (.getUniformBlockIndex gl prog "U_block_0Vertex")
        ubuf (.createBuffer gl)]
    (.bindVertexArray gl vao)
    (.bindBuffer gl (.-ARRAY_BUFFER gl) ibuf)
    ;; per-instance attributes at locations 0..4 (naga _p2vs_locationN), divisor 1, stride 48
    (let [stride 48
          attrs [[0 2 0] [1 2 8] [2 1 16] [3 1 20] [4 4 24]]]   ;; [loc size byte-offset]
      (doseq [[loc n off] attrs]
        (.enableVertexAttribArray gl loc)
        (.vertexAttribPointer gl loc n (.-FLOAT gl) false stride off)
        (.vertexAttribDivisor gl loc 1)))
    (when (not= ublk (.-INVALID_INDEX gl)) (.uniformBlockBinding gl prog ublk 0))
    (.bindVertexArray gl nil)
    (fn draw! [quad-instances [w h]]
      (let [data (js/Float32Array. (clj->js (sg/pack-instances quad-instances)))
            n    (count quad-instances)]
        (.useProgram gl prog)
        (.bindVertexArray gl vao)
        (.bindBuffer gl (.-ARRAY_BUFFER gl) ibuf)
        (.bufferData gl (.-ARRAY_BUFFER gl) data (.-DYNAMIC_DRAW gl))
        ;; uniform block U = { viewport: vec2, _p0: vec2 }
        (.bindBuffer gl (.-UNIFORM_BUFFER gl) ubuf)
        (.bufferData gl (.-UNIFORM_BUFFER gl) (js/Float32Array. #js [w h 0 0]) (.-DYNAMIC_DRAW gl))
        (.bindBufferBase gl (.-UNIFORM_BUFFER gl) 0 ubuf)
        (.enable gl (.-BLEND gl))
        (.blendFunc gl (.-ONE gl) (.-ONE_MINUS_SRC_ALPHA gl))
        (.drawArraysInstanced gl (.-TRIANGLES gl) 0 6 n)   ;; 6 corner verts × n instances
        (.bindVertexArray gl nil)))))

;; ── frame entry: resolve the graph for WebGL2, run the runnable passes ─────────────────────────
(defn render-2d!
  "Render a 2D sprite frame on WebGL2: clear, then draw the quad instances (from
   kami.sprite-gpu/draw-ops->quads) via the sprite pass. The :sprites pass has no kami.gpu
   :requires, so it runs on this tier; compute passes in a richer graph are dropped by resolve."
  [gl {:keys [draw-sprites! clear]} quad-instances [w h]]
  (.viewport gl 0 0 w h)
  (let [[r g b a] (or clear [0.04 0.05 0.08 1.0])]
    (.clearColor gl r g b a) (.clear gl (.-COLOR_BUFFER_BIT gl)))
  (draw-sprites! quad-instances [w h]))

;; ── 3D lit + shadow pass (instanced meshes, depth-FBO shadow map) ──────────────────────────────
;; Mirrors kami.webgpu's two-pass lit path on the WebGL2 API. The matrix math (camera→vp, sun→
;; light_vp) is shared with WebGPU and computed by the caller, who hands us the packed G uniform
;; (60 f32: vp[16] sun_dir[4] sun_col[4] sky[4] light_vp[16] light_a..d[16]) — identical to the
;; native `gf` array, so the render is the same. We own the GLSL plumbing: programs, the depth
;; framebuffer, the mesh + instance buffers, and the shadow→main draw order.
;; mesh attrs: pos(loc0,vec3) normal(loc1,vec3), divisor 0. instance attrs: m0..m3(loc2-5,vec4)
;; color(loc6,vec4) material(loc7,vec4), divisor 1 — 24 f32 / instance, the kami.webgpu layout.
(def ^:private SHADOW-FS "#version 300 es\nprecision highp float;\nvoid main() {}")   ;; depth-only

(defn scene-renderer
  "Build a whole-2D-frame draw fn from the embedded GLSL: a sky gradient pass (fullscreen triangle)
   then the instanced sprite/text quad pass. (render! {:sky {:zenith :ground} :quads [...]} [w h])
   draws the full kami.scene2d frame on the GPU — the Canvas2D draw-2d! replacement."
  [gl]
  (let [sky-prog (program gl glsl/sky-vert glsl/sky-frag)
        sky-ub   (.createBuffer gl)
        sky-blk  (.getUniformBlockIndex gl sky-prog "SU_block_0Fragment")
        draw!    (sprite-renderer gl)]
    (when (not= sky-blk (.-INVALID_INDEX gl)) (.uniformBlockBinding gl sky-prog sky-blk 0))
    (fn render-frame! [{:keys [sky quads]} [w h]]
      (.viewport gl 0 0 w h)
      ;; sky gradient pass
      (.useProgram gl sky-prog)
      (.bindBuffer gl (.-UNIFORM_BUFFER gl) sky-ub)
      (.bufferData gl (.-UNIFORM_BUFFER gl)
                   (js/Float32Array. (clj->js (concat (:zenith sky) (:ground sky)))) (.-DYNAMIC_DRAW gl))
      (.bindBufferBase gl (.-UNIFORM_BUFFER gl) 0 sky-ub)
      (.disable gl (.-BLEND gl))
      (.drawArrays gl (.-TRIANGLES gl) 0 3)
      ;; sprites + text, blended over the sky
      (draw! quads [w h]))))

(defn- mesh-vao [gl vbuf ibuf inst]
  (let [vao (.createVertexArray gl)]
    (.bindVertexArray gl vao)
    (.bindBuffer gl (.-ARRAY_BUFFER gl) vbuf)                       ;; interleaved pos(3)+normal(3) = 24B
    (doseq [[loc off] [[0 0] [1 12]]]
      (.enableVertexAttribArray gl loc) (.vertexAttribPointer gl loc 3 (.-FLOAT gl) false 24 off))
    (.bindBuffer gl (.-ELEMENT_ARRAY_BUFFER gl) ibuf)
    (.bindBuffer gl (.-ARRAY_BUFFER gl) inst)                       ;; 24 f32 / instance, stride 96
    (doseq [[loc off] [[2 0] [3 16] [4 32] [5 48] [6 64] [7 80]]]
      (.enableVertexAttribArray gl loc)
      (.vertexAttribPointer gl loc 4 (.-FLOAT gl) false 96 off)
      (.vertexAttribDivisor gl loc 1))
    (.bindVertexArray gl nil) vao))

(defn- depth-fbo [gl size]
  (let [tex (.createTexture gl) fbo (.createFramebuffer gl)]
    (.bindTexture gl (.-TEXTURE_2D gl) tex)
    (.texImage2D gl (.-TEXTURE_2D gl) 0 (.-DEPTH_COMPONENT32F gl) size size 0
                 (.-DEPTH_COMPONENT gl) (.-FLOAT gl) nil)
    (doseq [[k v] [[(.-TEXTURE_MIN_FILTER gl) (.-LINEAR gl)] [(.-TEXTURE_MAG_FILTER gl) (.-LINEAR gl)]
                   [(.-TEXTURE_COMPARE_MODE gl) (.-COMPARE_REF_TO_TEXTURE gl)]
                   [(.-TEXTURE_COMPARE_FUNC gl) (.-LEQUAL gl)]]]
      (.texParameteri gl (.-TEXTURE_2D gl) k v))
    (.bindFramebuffer gl (.-FRAMEBUFFER gl) fbo)
    (.framebufferTexture2D gl (.-FRAMEBUFFER gl) (.-DEPTH_ATTACHMENT gl) (.-TEXTURE_2D gl) tex 0)
    (.bindFramebuffer gl (.-FRAMEBUFFER gl) nil)
    {:tex tex :fbo fbo :size size}))

(defn lit-renderer
  "Build the 3D lit+shadow draw for this WebGL2 context. `shaders` {:lit {:vert :frag} :shadow {:vert}}
   are the GLSL ES 3.00 from bb gen-glsl. Returns (draw! packed-G mesh instances [w h]) where mesh is
   {:vbuf :ibuf :count}, instances a Float32Array (24 f32/instance) with metadata :count on the map
   passed as the 3rd-arg wrapper {:buf :count}."
  [gl shaders & [{:keys [shadow-size] :or {shadow-size 2048}}]]
  (let [lit-p (program gl (get-in shaders [:lit :vert]) (get-in shaders [:lit :frag]))
        shd-p (program gl (get-in shaders [:shadow :vert]) SHADOW-FS)
        sm    (depth-fbo gl shadow-size)
        gbuf  (.createBuffer gl)
        ibuf  (.createBuffer gl)
        bind-g (fn [prog n] (let [i (.getUniformBlockIndex gl prog n)]
                              (when (not= i (.-INVALID_INDEX gl)) (.uniformBlockBinding gl prog i 0))))]
    (bind-g lit-p "G_block_0Vertex") (bind-g lit-p "G_block_0Fragment") (bind-g shd-p "G_block_0Vertex")
    (fn draw! [packed-G mesh instances [w h]]
      (.bindBuffer gl (.-UNIFORM_BUFFER gl) gbuf)
      (.bufferData gl (.-UNIFORM_BUFFER gl) packed-G (.-DYNAMIC_DRAW gl))
      (.bindBufferBase gl (.-UNIFORM_BUFFER gl) 0 gbuf)
      (.bindBuffer gl (.-ARRAY_BUFFER gl) ibuf)
      (.bufferData gl (.-ARRAY_BUFFER gl) (:buf instances) (.-DYNAMIC_DRAW gl))
      (let [vao (mesh-vao gl (:vbuf mesh) (:ibuf mesh) ibuf)
            n   (:count instances)]
        (.enable gl (.-DEPTH_TEST gl))
        ;; pass 1 — depth into the shadow map from the sun's POV
        (.bindFramebuffer gl (.-FRAMEBUFFER gl) (:fbo sm))
        (.viewport gl 0 0 (:size sm) (:size sm)) (.clear gl (.-DEPTH_BUFFER_BIT gl))
        (.useProgram gl shd-p) (.bindVertexArray gl vao)
        (.drawElementsInstanced gl (.-TRIANGLES gl) (:count mesh) (.-UNSIGNED_SHORT gl) 0 n)
        ;; pass 2 — main, sampling the shadow map
        (.bindFramebuffer gl (.-FRAMEBUFFER gl) nil)
        (.viewport gl 0 0 w h) (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))
        (.useProgram gl lit-p)
        (.activeTexture gl (.-TEXTURE0 gl)) (.bindTexture gl (.-TEXTURE_2D gl) (:tex sm))
        (.uniform1i gl (.getUniformLocation gl lit-p "_group_0_binding_1_fs") 0)
        (.drawElementsInstanced gl (.-TRIANGLES gl) (:count mesh) (.-UNSIGNED_SHORT gl) 0 n)
        (.bindVertexArray gl nil)))))

   )

   :clj
   (do
     (defn webgpu-available?
       []
       false)

     (defn pick-backend
       []
       :webgl2)

     (defn caps
       [_gl]
       (gpu/caps-from-device :webgl2 {:compute false :storage false :instancing true}))

     (defn- browser-only [f data]
       (throw (ex-info (str "kami.webgl/" f " is a browser ClojureScript WebGL2 executor")
                       (merge {:namespace 'kami.webgl :platform :clj} data))))

     (defn webgl2-context [canvas] (browser-only "webgl2-context" {:canvas canvas}))
     (defn program [gl vsrc fsrc] (browser-only "program" {:gl gl :vert vsrc :frag fsrc}))
     (defn init-mesh-viewport! [canvas] (browser-only "init-mesh-viewport!" {:canvas canvas}))
     (defn upload-mesh! [& args] (browser-only "upload-mesh!" {:args args}))
     (defn render-mesh-scene! [& args] (browser-only "render-mesh-scene!" {:args args}))
     (defn render-mesh-frame! [& args] (browser-only "render-mesh-frame!" {:args args}))
     (defn render-skinned-mesh-frame! [& args] (browser-only "render-skinned-mesh-frame!" {:args args}))
     (defn sprite-renderer [& args] (browser-only "sprite-renderer" {:args args}))
     (defn render-2d! [& args] (browser-only "render-2d!" {:args args}))
     (defn scene-renderer [& args] (browser-only "scene-renderer" {:args args}))
     (defn lit-renderer [& args] (browser-only "lit-renderer" {:args args}))))
