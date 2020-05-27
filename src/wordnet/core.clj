(ns wordnet.core
  (:require
    [clojure.java.io :refer [file]]
    [wordnet.coerce :as coerce])
  (:import
    (clojure.lang Keyword)
    (edu.mit.jwi IDictionary Dictionary RAMDictionary)
    (edu.mit.jwi.data ILoadPolicy)
    (edu.mit.jwi.item IIndexWord ISynset ISynsetID SynsetID
                      IWordID WordID IWord POS IPointer)
    (edu.mit.jwi.morph WordnetStemmer)))

; JWI ICacheDictionary is not threadsafe
(def coarse-lock (Object.))

(defn- from-synset
  "Create a map from the specified synset."
  [^IDictionary dict ^ISynset synset]
    (with-meta ;; JWI objects are stored in metadata to allow future
               ;; traversals
      { :id (str (.getID synset))
        :gloss (.getGloss synset)}
      { :synset synset
        :dict dict }))

(defn- from-word
  "Descends down into each word, expanding synonyms that have not been previously seen"
  [^IDictionary dict ^IWord word]
  (with-meta
    { :id     (str (.getID word))
      :pos    (-> word .getPOS .name coerce/to-keyword)
      :lemma  (.getLemma word) }
    { :word   word
      :dict   dict }))

(defn- fetch-word
  "Look up a JWI word given a word ID."
  [^IDictionary dict ^IWordID word-id]
  (from-word
   dict
   (locking coarse-lock
     (.getWord dict word-id))))

(defn- fetch-synset
  "Look up a JWI synset given a synset ID."
  [^IDictionary dict ^ISynsetID synset-id]
  (from-synset
   dict
   (locking coarse-lock
     (.getSynset dict synset-id))))

(def ^:dynamic *stem?*
  "Whether or not to do stemming when looking up words."
  false)

(defn- stem
  "Find stems of the specified lemma and part of speech."
  [^IDictionary dict lemma part-of-speech]
  (.findStems (WordnetStemmer. dict) lemma (coerce/pos part-of-speech)))

;;; No adjective-satellites in jwi, AFAICS.
(def pos-synset-translation
  {:noun-synsets (coerce/pos "n"),
   :verb-synsets (coerce/pos "v"),
   :adverb-synsets (coerce/pos "r"),
   :adjective-synsets (coerce/pos "a")})

(defn- pos-synsets
  "Return the complete sequence of synsets for a part of speech."
  [^IDictionary dict skey]
  (iterator-seq (locking coarse-lock (.getSynsetIterator
                                      dict
                                      (get pos-synset-translation skey)))))

(defn- word-ids
  "Look up word IDs for a part of speech of particular word."
  ([^IDictionary dict part-of-speech]
     (let [index-words (iterator-seq (locking coarse-lock (.getIndexWordIterator
                                                           dict
                                                           (coerce/pos part-of-speech))))]
       (mapcat (memfn ^IIndexWord getWordIDs) index-words)))
  ([^IDictionary dict lemma part-of-speech]
   (if *stem?*
     (mapcat (memfn ^IIndexWord getWordIDs)
             (for [stem (stem dict lemma part-of-speech)
                   :let [^IIndexWord index-word (locking coarse-lock (.getIndexWord dict stem (coerce/pos part-of-speech)))]
                   :when index-word]
               index-word))
     (when-not (= lemma "")
       (when-let [^IIndexWord index-word (locking coarse-lock (.getIndexWord
                                                               dict lemma
                                                               (coerce/pos part-of-speech)))]
         (.getWordIDs index-word))))))

(defn make-dictionary
  "Initializes a dictionary implementation that mounts files on disk
   and has caching, returns a function which takes a lemma and part-of-speech
   and returns list of matching entries"
  [wordnet-dir & opt-flags]
  (let [file (file wordnet-dir)
        ^IDictionary dict (if (:in-memory (set opt-flags))
                            (RAMDictionary. file ILoadPolicy/IMMEDIATE_LOAD)
                            (Dictionary. file))]
      (.open dict)
      (letfn [(lookup
                ([arg]
                 (cond
                  ;; Find by part of speech
                  (#{:noun :verb :adjective :adverb } arg)
                  (map (partial fetch-word dict) (word-ids dict arg))

                  ;; return synsets
                  (contains? pos-synset-translation arg)
                  (map (partial from-synset dict) (pos-synsets dict arg))

                  (empty? ^String arg)
                  nil

                  ;; Find by word ID
                  (.startsWith ^String arg "WID")
                  (fetch-word dict (WordID/parseWordID ^String arg))

                  ;; Find by sysnset ID
                  (.startsWith ^String arg "SID")
                  (fetch-synset dict (SynsetID/parseSynsetID ^String arg))

                  :else
                  (if-let [[_ arg part-of-speech sense] (re-matches #"^(.+)#(.)#(\d+)$" arg)]
                    ;; Find with a POS and sense index, e.g. dog#n#1
                    (lookup arg part-of-speech (Integer/parseInt sense))
                    ;; Find all matching words
                    (mapcat (partial lookup arg) (POS/values)))))

                ([^String lemma pos]
                 ;; Find by lemma and pos
                 (map (partial fetch-word dict) (word-ids dict lemma pos)))

                ([^String lemma pos ^Integer sense]
                 ;; Find by lemma, pos and sense index
                 (nth (lookup lemma pos) (dec sense))))]

        (fn [& args]
          (let [stem #{:stem}]
            ;; If a user puts :stem anywhere in the arg list, turn stemming on. 
            (if (some stem args)
              (binding [*stem?* true]
                (apply lookup (remove stem args)))
              (apply lookup args)))))))

(defn word?
  "Is entry a word?"
  [entry]
  (:word (meta entry)))

(defn synset?
  "Is entry a synset?"
  [entry]
  (:synset (meta entry)))

(defn synset
  "Fetch the synset of word."
  [word]
  {:pre [(word? word)]}
  (let [{^IWord word :word ^IDictionary dict :dict} (meta word)]
    (from-synset dict (.getSynset word))))

(defn words
  "Fetch the words matching the meaning of this synset."
  [synset]
  {:pre [(synset? synset)]}
  (let [{^ISynset synset :synset ^IDictionary dict :dict} (meta synset)]
    (map (partial from-word dict) (.getWords synset))))

(defn related-synsets
  "Returns a list of synsets related to the specified synset by the
  provided pointer."
  [synset pointer]
  {:pre [(synset? synset)]}
  (let [{^ISynset synset :synset ^IDictionary dict :dict} (meta synset)]
    (map
     (partial fetch-synset dict)
     (.getRelatedSynsets synset (coerce/keyword->pointer pointer)))))

(defn semantic-relations
  "Find all semantically related synsets, returning a map of pointers
  to lists of synsets."
  [synset]
  {:pre [(synset? synset)]}
  (let [{^ISynset synset :synset ^IDictionary dict :dict} (meta synset)]
   (into {}
         (map (fn [[^IPointer pointer synset-ids]]
                [(coerce/pointer->keyword pointer)
                 (map (partial fetch-synset dict) synset-ids)])
              (.getRelatedMap synset)))))

(defn related-words
  "Use a lexical pointer to fetch related words, returning a list of words"
  [word pointer]
  {:pre [(word? word)]}
  (let [{^IWord word :word ^IDictionary dict :dict} (meta word)]
   (map
    (partial fetch-word dict)
    (.getRelatedWords word (coerce/keyword->pointer pointer)))))

(defn lexical-relations
  "Find all lexically related words of the specified word, returning a
  map with lexical pointer keywords as keys, and lists of words as
  values."
  [word]
  {:pre [(word? word)]}
  (let [{^IWord word :word ^IDictionary dict :dict} (meta word)]
   (into {}
         (map (fn [[^IPointer pointer word-ids]]
                [(coerce/pointer->keyword pointer)
                 (map (partial fetch-word dict) word-ids)])
              (.getRelatedMap word)))))

(defn traverse
  "Returns a lazy sequence by recursively walking the semantic
  relations of the specified synset via the specified pointers."
  [synset & pointers]
  {:pre [(synset? synset)]}
  (let [next (mapcat (partial related-synsets synset) pointers)]
    (concat next (lazy-seq (mapcat #(apply traverse % pointers) next)))))

(defn hypernyms
  "Returns a lazy sequence of hypernyms of the specified synset. If
  any synset in the sequence has multiple hypernyms, their respective
  hypernyms will be concatenated, so do not rely on the order of
  hypernyms being strictly hyponym/hypernym."
  [synset]
  {:pre [(synset? synset)]}
  (traverse synset :hypernym))

(defn instances
  "Find synsets which describe an instance of the specified synset."
  [synset]
  {:pre [(synset? synset)]}
  (let [hyponyms (related-synsets synset :hyponym)
        hyponym-instances (related-synsets synset :hyponym-instance)]
    (concat hyponym-instances (lazy-seq (mapcat instances hyponyms)))))

(defn hypernym-instances
  "Find synsets of which the specified synset is an instance."
  [synset]
  {:pre [(synset? synset)]}
  (let [hypernym-instances (related-synsets synset :hypernym-instance)]
    (concat hypernym-instances
            (mapcat hypernyms hypernym-instances))))

(defn synonyms
  [^IDictionary dict ^String word ^Keyword pos]
  (let [results (dict word pos)]
    (concat
      (->> results
           (mapcat (comp words synset))
           (reduce (fn [acc x] (conj acc (:lemma x)))
                   []))
      (->> results
           (mapcat (comp #(related-synsets % :hypernym) synset))
           (mapcat words)
           (reduce (fn [acc x] (conj acc (:lemma x)))
                   [])))))

