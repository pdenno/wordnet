(ns wordnet.test-client
  (:require
    [wordnet.core :as wordnet]))

;; =========================================================================
;; Don't change the path - it is used for running local and Travis CI builds
;; =========================================================================
;; A git submodule is used to reference the latest wordnet database files.
;; If you dont have any content in ./data/dict, try running the following
;; in the project root directory:
;;
;;    $ git submodule update --init data
;;
(def dict (wordnet/make-dictionary "./data/dict"))
