(ns webgl-test
  (:require [clojure.test :refer [deftest is]]
            [kami.webgl :as kami-webgl]
            [kotoba.webgl :as webgl]))

(deftest mesh-vertices-use-eight-scalar-floats-per-vertex
  (let [stream (kami-webgl/interleave-mesh-vertices
                [[1 2 3] [4 5 6]]
                [[0 1 0] [0 0 1]]
                [[0.25 0.5] [0.75 1.0]])]
    (is (= 16 (count stream)))
    (is (= [1 2 3 0 1 0 0.25 0.5
            4 5 6 0 0 1 0.75 1.0]
           stream))
    (is (every? number? stream))))

(deftest fallback-source-keeps-backend-neutral-toon-contract
  (let [source (slurp "src/kami/webgl.cljc")]
    (is (re-find #"uniform vec3 u_shading" source))
    (is (re-find #"smoothstep\(u_shading\.y-smooth_w,u_shading\.y\+smooth_w,ndl\)" source))
    (is (re-find #":shade-kind material" source))
    (is (re-find #":toon-threshold material" source))
    (is (re-find #":toon-smooth material" source))))

(deftest webgl-capability-data-is-cljc
  (is (false? (webgl/webgpu-available?)))
  (is (= :webgl2 (webgl/pick-backend)))
  (is (= :webgl2 (:backend (webgl/caps nil))))
  (is (false? (:compute (webgl/caps nil)))))

(deftest webgl-executor-is-explicitly-platform-bound
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"browser ClojureScript WebGL2 executor"
                        (webgl/webgl2-context nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"browser ClojureScript WebGL2 executor"
                        (webgl/scene-renderer nil))))
