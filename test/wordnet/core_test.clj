(ns wordnet.core-test
  (:require
    [clojure.test :refer :all]
    [wordnet.core :refer :all]
    [wordnet.test-client :as test-client]))

(deftest fetch-with-noun
  (is (= "dog" (:lemma (first (test-client/dict "dog" :noun))))))

(deftest fetch-exact
  (is (= "metal supports for logs in a fireplace; \"the andirons were too hot to touch\""
         (-> "dog#n#7" test-client/dict synset :gloss))))

(deftest fetch-without-pos
  (is (= "dog" (:lemma (first (test-client/dict "dog "))))))

(deftest fetch-by-stemming
  (is (= nil (:lemma (first (test-client/dict "dogs")))))
  (is (= "dog" (:lemma (first (test-client/dict "dogs" :stem)))))
  (is (= "buy" (:lemma (first (test-client/dict "bought" :stem))))))

(deftest fetch-unknown-word
  (is (empty? (test-client/dict "fdssfsfs"))))

(deftest fetch-empty-word
  (is (empty? (test-client/dict ""))))

(deftest fetch-nil-word
  (is (empty? (test-client/dict nil))))

(deftest word-id-lookup
  (let [dog (first (test-client/dict "dog" :noun))]
    (is (= dog (test-client/dict "WID-02086723-N-01-dog")))))

(deftest synset-id-lookup
  (is (= "a member of the genus Canis (probably descended from the common wolf) that has been domesticated by man since prehistoric times; occurs in many breeds; \"the dog barked all night\""
         (:gloss (test-client/dict "SID-02086723-N")))))

(deftest synset-words
  (is (= '("dog" "domestic_dog" "Canis_familiaris")
         (map :lemma (words (test-client/dict "SID-02086723-N"))))))

(deftest related-synset-test
  (is (= '("SID-02085998-N" "SID-01320032-N")
         (map (comp str :id) (related-synsets (test-client/dict "SID-02086723-N") :hypernym)))))

(deftest semantic-relations-test
  (is (= '(:holonym-member :hypernym :hyponym :meronym-part)
         (sort (keys (semantic-relations (test-client/dict "SID-02086723-N")))))))

(deftest lexical-relations-test
  (is (:derivationally-related (lexical-relations (test-client/dict "WID-00982557-A-01-quick")))))

(deftest hypernym-test
  (is (= '("SID-02085998-N" "SID-01320032-N" "SID-02077948-N" "SID-01889397-N" "SID-01864419-N" "SID-01474323-N" "SID-01468898-N" "SID-00015568-N" "SID-00004475-N" "SID-00004258-N" "SID-00003553-N" "SID-00002684-N" "SID-00001930-N" "SID-00001740-N" "SID-00015568-N" "SID-00004475-N" "SID-00004258-N" "SID-00003553-N" "SID-00002684-N" "SID-00001930-N" "SID-00001740-N")
         (map :id (hypernyms (synset (test-client/dict "dog#n#1")))))))

(deftest hypernym-instance-test
  (is (= '("SID-08714745-N" "SID-08562388-N" "SID-08508836-N" "SID-08569713-N" "SID-08648560-N" "SID-00027365-N" "SID-00002684-N" "SID-00001930-N" "SID-00001740-N")
         (map :id (hypernym-instances (synset (test-client/dict "england#n#1")))))))
