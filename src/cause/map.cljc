(ns cause.map
  (:require [cause.util :as u :refer [<<]]
            [cause.shared :as s]
            [cause.protocols :as proto]
            #? (:cljs [cljs.reader]))
  #? (:clj
      (:import (clojure.lang IPersistentCollection IPersistentMap IHashEq Associative ILookup Counted Seqable IMapIterable IKVReduce IFn IObj IMeta)
               (java.io Writer)
               (java.util Date Collection)
               (java.lang Object))))

(defn new-causal-tree []
  {::s/type ::s/map
   ::s/lamport-ts 0
   ::s/uuid (u/uid)
   ::s/site-id (s/site-id)
   ::s/nodes {}
   ::s/yarns {}
   ::s/weave {}})

; NOTE: a weave is a map of :key [[id value] [id value]] pairs.
;   The vector of values is sorted by id with the newest id
;   at the front of this vector.
(defn weave
  "Returns a causal tree with its nodes ordered into a weave O(n^2).
  If a node is passed only that node will be woven in O(n). This is
  much fast in practice. The worst case only occurs when one key
  is continually changed. Most maps will have many keys that are
  infrequently changed."
  ([causal-tree]
   (reduce weave (assoc causal-tree ::s/weave {})
           (map s/node (sort (::s/nodes causal-tree)))))
  ([causal-tree [id k v :as node]]
   (if (not (get-in causal-tree [::s/nodes id]))
     causal-tree
     (loop [left []
            right (or (get-in causal-tree [::s/weave k]) [])]
       (let [nr (first right)]
         (if (or (empty? right)
                 (<< (first nr) id))
           (assoc-in causal-tree [::s/weave k]
                     (vec (concat left [[id v]] right)))
           (recur (conj left nr) (rest right))))))))

; Specialty helper functions

(defn get-
  ([causal-tree k]
   (last (first (get-in causal-tree [::s/weave k])))))

(defn count- [causal-tree]
  (reduce-kv
   (fn [acc k v] (if (not= (last (first v)) ::s/delete) (inc acc) acc))
   0 (::s/weave causal-tree)))

(defn assoc-
  ([causal-tree k v]
   (if (not= v (get- causal-tree k)) ; don't set a key to the same value twice
     (s/append weave causal-tree k v)
     causal-tree))
  ([causal-tree k v & kvs]
   (apply assoc- (assoc- causal-tree k v) kvs)))

(defn dissoc-
  ([causal-tree k]
   (if (get- causal-tree k) ; only delete keys that are already in the tree
     (s/append weave causal-tree k ::s/delete)
     causal-tree))
  ([causal-tree k & ks]
   (apply dissoc- (dissoc- causal-tree k) ks)))

(defn empty- [causal-tree]
  (conj (new-causal-tree) (select-keys causal-tree [::s/site-id ::s/uuid])))

#? (:clj
    (deftype CausalMap [ct]
      Counted
      (count [this] (count- (.ct this)))

      IPersistentCollection
      (cons [this o] (CausalMap. (apply assoc- (.ct this) (flatten (vec o)))))
      (empty [this] (CausalMap. (empty- (.ct this))))
      (equiv [this other] (.equiv ^IPersistentCollection (.ct this) other))

      IPersistentMap
      (assoc [this k v] (CausalMap. (assoc- (.ct this) k v)))
      (assocEx [this k v] (throw (Exception.)))
      (without [this k] (CausalMap. (dissoc- (.ct this) k)))

      Object
          ; TODO: what should equality mean? Should it be maps that materialize
          ;   to the same value? Or should all the nodes have to match?
      (equals [this o] (.equals (.ct this) o))
      (hashCode [this] (.hashCode (.ct this)))
      (toString [this] (.toString (s/causal->edn (.ct this))))

      ILookup
      (valAt [this k] (get- (.ct this) k))
      (valAt [this k not-found] (or (.valAt this k) not-found))

      IMapIterable
      (keyIterator [this] (.keyIterator ^IMapIterable (s/causal->edn (.ct this) :deref-atoms false)))
      (valIterator [this] (.valIterator ^IMapIterable (s/causal->edn (.ct this) :deref-atoms false)))

      IKVReduce
      (kvreduce [this f init] (.kvreduce ^IKVReduce (s/causal->edn (.ct this) :deref-atoms false) f init))

      IHashEq
      (hasheq [this] (.hasheq ^IHashEq (.ct this)))

      Seqable
      (seq [this] (.seq ^Seqable (s/causal->edn (.ct this) :deref-atoms false)))

      IFn
      (invoke [this k] (.invoke ^IFn (.ct this) k))
      (invoke [this k not-found] (.invoke ^IFn (.ct this) k not-found))

      IObj
      (withMeta [this meta] (CausalMap. (with-meta ^IObj (.ct this) meta)))

      IMeta
      (meta [this] (.meta ^IMeta (.ct this))))
    :cljs
    (deftype CausalMap [ct]
      ICounted
      (-count [this] (count- (.-ct this)))

      IEmptyableCollection
      (-empty [this] (CausalMap. (empty- (.-ct this))))

      ICollection
      (-conj [this o] (CausalMap. (apply assoc- (.-ct this) (flatten (vec o)))))

      IAssociative
      (-contains-key? [this k] (some? (get- (.-ct this) k)))
      (-assoc [this k v] (CausalMap. (assoc- (.-ct this) k v)))

      IFind
      (-find [this k] (get- (.-ct this) k))

      IMap
      (-dissoc [this k] (CausalMap. (dissoc- (.-ct this) k)))

      IKVReduce
      (-kv-reduce [this f init] (-kv-reduce (s/causal->edn (.-ct this) :deref-atoms false) f init))

      IEquiv
      (-equiv [this other] (-equiv (.-ct this) other))

      ILookup
      (-lookup [this o] (get- (.-ct this) o))
      (-lookup [this o not-found] (-lookup (.-ct this) o not-found))

      IPrintWithWriter
      (-pr-writer [o writer opts]
        (-write writer (str "#causal/map " (pr-str {:causal->edn (s/causal->edn (.-ct o))
                                                    :ct (.-ct o)}))))

      IHash
      (-hash [this] (-hash (.-ct this)))

      IFn
      (-invoke [this o] ((.-ct this) o))
      (-invoke [this o not-found] ((.-ct this) o not-found))

      ISeqable
      (-seq [this] (-seq (s/causal->edn (.-ct this) :deref-atoms false)))

      Object
      (toString [this] (.toString (s/causal->edn (.-ct this))))

      IMeta
      (-meta [this] (-meta (.-ct this)))

      IWithMeta
      (-with-meta [this meta] (CausalMap. (-with-meta (.-ct this) meta)))))

#? (:clj (defmethod print-method CausalMap [^CausalMap o ^java.io.Writer w]
           (.write w (str "#causal/map " (pr-str {:causal->edn (s/causal->edn (.ct o))
                                                  :ct (.ct o)})))))

(defn read-edn-map
  [read-object]
  (let [[ct] read-object]
    (CausalMap. ct)))

#? (:cljs (cljs.reader/register-tag-parser! 'causal/map read-edn-map))

(extend-type CausalMap
  proto/CausalTree
  (get-uuid [this] (::s/uuid (.-ct this)))
  (get-ts [this] (::s/lamport-ts (.-ct this)))
  (get-site-id [this] (::s/site-id (.-ct this)))
  (get-weave [this] (::s/weave (.-ct this)))
  (insert [this node]
    (CausalMap. (s/insert weave (.-ct this) node)))
  (append [this cause value]
    (CausalMap. (s/append weave (.-ct this) cause value)))
  (weft [this ids-to-cut-yarns]
    (CausalMap. (s/weft weave new-causal-tree (.-ct this) ids-to-cut-yarns)))
  (causal-merge [causal-map1 causal-map2]
    (CausalMap. (s/merge-trees weave (.-ct causal-map1) (.-ct causal-map2)))))

(defn new-causal-map []
  (CausalMap. (new-causal-tree)))

(defn new-causal-map
  "Creates a new causal map containing the alternating keys and values."
  [& kvs]
  (let [cm (CausalMap. (new-causal-tree))]
    (if kvs (apply assoc cm kvs) cm)))

(comment
  (do
    (def ct (atom (new-causal-map :yo "lo")))
    (swap! ct assoc :foo "bar")
    (swap! ct assoc :fizz "bang")
    (swap! ct dissoc :foo)
    (swap! ct assoc :foo "bop")
    (swap! ct assoc :flip "flop"))
  (proto/get-uuid @ct)
  (proto/append @ct :wat "sup")
  (swap! ct dissoc :fizz)
  (count @ct)
  (hash @ct)
  (str @ct)
  (deref ct)
  (count- (.ct @ct))
  (empty @ct)
  (do
    (def ct2 (atom @ct))
    (swap! ct2 proto/insert (s/node 6 (s/site-id) :foo "boo"))
    (swap! ct2 proto/insert (s/node 3 (s/site-id) :flip "grip"))
    (swap! ct2 proto/insert (s/node 23 (s/site-id) :stew "art"))
    (swap! ct assoc :flim "flam")
    (proto/causal-merge @ct @ct2))
  (swap! ct assoc :ct2 ct2)
  (= @ct @ct2)
  (deref ct)
  (cons "wat" @ct)
  (conj @ct {:a 2 :b 3 :foo "wat"})
  (get @ct :fizz)
  (:foo @ct)
  (@ct ::s/uuid)
  (get @ct :gloop "glop")
  (keys @ct)
  (vals @ct)
  (type->str (type @ct))
  (str (type @ct))
  (instance? cause.map.CausalMap @ct)
  (s/causal->edn @ct :deref-atoms false)
  (s/causal->edn @ct)
  (seq @ct)
  (first @ct)
  (ffirst @ct)
  (second @ct)
  (last @ct)
  (next @ct)
  (rest @ct)
  (map (partial map clojure.string/upper-case) @ct)
  (reduce-kv conj [] @ct)
  (empty? @ct)
  (swap! ct dissoc :foo)
  (swap! ct dissoc :fizz)
  (swap! ct dissoc :flip)
  (empty? @ct)
  (+ 1 1))