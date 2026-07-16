(ns webgl-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.webgl :as webgl]))

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
                        (webgl/scene-renderer nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"browser ClojureScript WebGL2 executor"
                        (webgl/render-skinned-mesh-frame!))))
