(ns clojure.core.typed.cs-gen
  (:require [clojure.core.typed.utils :as u]
            [clojure.core.typed.type-rep :as r :refer []]
            [clojure.core.typed.type-ctors :as c]
            [clojure.core.typed.filter-rep :as fr]
            [clojure.core.typed.filter-ops :as fo]
            [clojure.core.typed.object-rep :as or]
            [clojure.core.typed.subtype :as sub]
            [clojure.core.typed.parse-unparse :as prs]
            [clojure.core.typed.cs-rep :as cr]
            [clojure.core.typed.current-impl :as impl]
            [clojure.core.typed.util-vars :as vs]
            [clojure.core.typed.dvar-env :as denv]
            [clojure.core.typed.frees :as frees]
            [clojure.core.typed.free-ops :as free-ops]
            [clojure.core.typed.promote-demote :as prmt]
            [clojure.core.typed.subst :as subst]
            [clojure.core.typed :as t :refer [for> fn>]]
            [clojure.set :as set])
  (:import (clojure.core.typed.type_rep F Value Poly TApp Union FnIntersection
                                        Result AnyValue Top HeterogeneousSeq RClass HeterogeneousList
                                        HeterogeneousVector DataType HeterogeneousMap PrimitiveArray
                                        Function Protocol Bounds FlowSet TCResult)
           (clojure.core.typed.cs_rep c cset dcon dmap cset-entry)
           (clojure.core.typed.filter_rep TypeFilter)
           (clojure.lang Symbol ISeq IPersistentList APersistentVector APersistentMap)))

(t/ann ^:no-check clojure.core.typed.subtype/subtype? [r/AnyType r/AnyType -> Boolean])
(t/ann ^:no-check clojure.set/union (All [x] [(t/Set x) * -> (t/Set x)]))
(t/ann ^:no-check clojure.core.typed.current-impl/current-impl [-> Any])
(t/ann ^:no-check clojure.core.typed.current-impl/any-impl Any)
(t/ann ^:no-check clojure.core.typed.current-impl/checking-clojure? [-> Any])

(t/ann fail! [Any Any -> Nothing])
(defn fail! [s t]
  (throw u/cs-gen-exn))

(defmacro handle-failure [& body]
  `(u/handle-cs-gen-failure ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constraint Generation

(t/ann meet [r/Type r/Type -> r/Type])
(defn meet [s t] (c/In s t))

(t/ann join [r/Type r/Type -> r/Type])
(defn join [s t] (c/Un s t))

(t/ann c-meet (Fn [c c (U nil Symbol) -> c]
                  [c c -> c]))
(defn c-meet 
  ([c1 c2] (c-meet c1 c2 nil))
  ([{S  :S X  :X T  :T bnds  :bnds :as c1}
    {S* :S X* :X T* :T bnds* :bnds :as c2}
    var]
   (when-not (or var (= X X*))
     (u/int-error (str "Non-matching vars in c-meet:" X X*)))
   (when-not (= bnds bnds*)
     (u/int-error (str "Non-matching bounds in c-meet:" bnds bnds*)))
   (let [S (join S S*)
         T (meet T T*)]
     (when-not (sub/subtype? S T)
       (fail! S T))
     (cr/->c S (or var X) T bnds))))

(declare dmap-meet)

;FIXME flow error when checking
(t/ann ^:no-check cset-meet [cset cset -> cset])
(defn cset-meet [{maps1 :maps :as x} {maps2 :maps :as y}]
  {:pre [(cr/cset? x)
         (cr/cset? y)]}
  (let [maps (doall (t/for> :- cset-entry
                      [[e1 e2] :- '[cset-entry cset-entry], (map (t/fn> [m1 :- cset-entry,
                                                                         m2 :- cset-entry]
                                                                    (vector m1 m2)) 
                                                                 maps1 maps2)]
                      (let [{map1 :fixed dmap1 :dmap delay1 :delayed-checks} e1
                            {map2 :fixed dmap2 :dmap delay2 :delayed-checks} e2]
                        (cr/->cset-entry (merge-with c-meet map1 map2)
                                         (dmap-meet dmap1 dmap2)
                                         (set/union delay1 delay2)))))]
    (when (empty? maps)
      (fail! maps1 maps2))
    (cr/->cset maps)))

(t/ann cset-meet* [(U nil (t/Seqable cset)) -> cset])
(defn cset-meet* [args]
  {:pre [(every? cr/cset? args)]
   :post [(cr/cset? %)]}
  (reduce cset-meet
          (cr/->cset [(cr/->cset-entry {} (cr/->dmap {}) #{})])
          args))

(t/ann cset-combine [(U nil (t/Seqable cset)) -> cset])
(defn cset-combine [l]
  {:pre [(every? cr/cset? l)]}
  (let [mapss (let [get-maps (t/ann-form (t/inst :maps (U nil (t/Seqable cset-entry) ))
                                         ['{:maps (U nil (t/Seqable cset-entry))} -> (U nil (t/Seqable cset-entry))])
                    map' (t/inst map (U nil (t/Seqable cset-entry)) '{:maps (U nil (t/Seqable cset-entry))})]
                (map' get-maps l))]
    (t/ann-form mapss (t/Seqable (U nil (t/Seqable cset-entry))))
    (cr/->cset (apply concat mapss))))

;add new constraint to existing cset
(t/ann insert-constraint [cset Symbol r/TCType r/TCType Bounds
                                     -> cset])
(defn insert-constraint [cs var S T bnds]
  {:pre [(cr/cset? cs)
         (symbol? var)
         (r/Type? S)
         (r/Type? T)
         (r/Bounds? bnds)]
   :post [(cr/cset? %)]}
  (cr/->cset (doall
               (for> :- cset-entry
                 [{fmap :fixed dmap :dmap :keys [delayed-checks]} :- cset-entry, (:maps cs)]
                 (cr/->cset-entry (assoc fmap var (cr/->c S var T bnds))
                                  dmap
                                  delayed-checks)))))

(t/ann insert-delayed-constraint [cset r/TCType r/TCType -> cset])
(defn insert-delayed-constraint [cs S T]
  {:pre [(cr/cset? cs)
         (r/Type? S)
         (r/Type? T)]
   :post [(cr/cset? %)]}
  (cr/->cset
    (doall
      (for> :- cset-entry
        [{fmap :fixed dmap :dmap :keys [delayed-checks]} :- cset-entry, (:maps cs)]
        (cr/->cset-entry fmap dmap (conj delayed-checks [S T]))))))

; FIXME no-checked because of massive performance issues. revisit
(t/ann ^:no-check dcon-meet [cr/DCon cr/DCon -> cr/DCon])
(defn dcon-meet [dc1 dc2]
  {:pre [(cr/dcon-c? dc1)
         (cr/dcon-c? dc2)]
   :post [(cr/dcon-c? %)]}
  (cond
    (and (cr/dcon-exact? dc1)
         (or (cr/dcon? dc2) 
             (cr/dcon-exact? dc2)))
    (let [{fixed1 :fixed rest1 :rest} dc1
          {fixed2 :fixed rest2 :rest} dc2]
      (when-not (and rest2 (= (count fixed1) (count fixed2)))
        (fail! fixed1 fixed2))
      (cr/->dcon-exact
        (doall
          (let [vector' (t/ann-form vector [c c -> '[c c]])]
            (for> :- c
              [[c1 c2] :- '[c c], (map vector' fixed1 fixed2)]
              (c-meet c1 c2 (:X c1)))))
        (c-meet rest1 rest2 (:X rest1))))
    ;; redo in the other order to call the first case
    (and (cr/dcon? dc1)
         (cr/dcon-exact? dc2))
    (dcon-meet dc2 dc1)

    (and (cr/dcon? dc1)
         (not (:rest dc1))
         (cr/dcon? dc2)
         (not (:rest dc2)))
    (let [{fixed1 :fixed} dc1
          {fixed2 :fixed} dc2]
      (when-not (= (count fixed1) (count fixed2))
        (fail! fixed1 fixed2))
      (cr/->dcon
        (doall
          (for [[c1 c2] (map vector fixed1 fixed2)]
            (c-meet c1 c2 (:X c1))))
        nil))

    (and (cr/dcon? dc1)
         (not (:rest dc1))
         (cr/dcon? dc2))
    (let [{fixed1 :fixed} dc1
          {fixed2 :fixed rest :rest} dc2]
      (when-not (>= (count fixed1) (count fixed2))
        (fail! fixed1 fixed2))
      (cr/->dcon
        (let [vector' (t/inst vector c c Any Any Any Any)]
          (doall
            (for> :- c
              [[c1 c2] :- '[c c], (map vector' fixed1 (concat fixed2 (repeat rest)))]
              (c-meet c1 c2 (:X c1)))))
        nil))

    (and (cr/dcon? dc1)
         (cr/dcon? dc2)
         (not (:rest dc2)))
    (dcon-meet dc2 dc1)

    (and (cr/dcon? dc1)
         (cr/dcon? dc2))
    (let [{fixed1 :fixed rest1 :rest} dc1
          {fixed2 :fixed rest2 :rest} dc2
          [shorter longer srest lrest]
          (if (< (count fixed1) (count fixed2))
            [fixed1 fixed2 rest1 rest2]
            [fixed2 fixed1 rest2 rest1])]
      (cr/->dcon
        (let [vector' (t/inst vector c c Any Any Any Any)]
          (doall
            (for> :- c
              [[c1 c2] :- '[c c ], (map vector' longer (concat shorter (repeat srest)))]
              (c-meet c1 c2 (:X c1)))))
        (c-meet lrest srest (:X lrest))))

    (and (cr/dcon-dotted? dc1)
         (cr/dcon-dotted? dc2))
    (let [{fixed1 :fixed c1 :dc {bound1 :name} :dbound} dc1
          {fixed2 :fixed c2 :dc {bound2 :name} :dbound} dc2]
      (when-not (and (= (count fixed1) (count fixed2))
                     (= bound1 bound2))
        (fail! bound1 bound2))
      (cr/->dcon-dotted (let [vector' (t/inst vector c c Any Any Any Any)]
                          (doall 
                            (for> :- c
                              [[c1 c2] :- '[c c], (map vector' fixed1 fixed2)]
                              (c-meet c1 c2 (:X c1)))))
                        (c-meet c1 c2 bound1) bound1))

    (and (cr/dcon? dc1)
         (cr/dcon-dotted? dc2))
    (fail! dc1 dc2)

    (and (cr/dcon-dotted? dc1)
         (cr/dcon? dc2))
    (fail! dc1 dc2)

    :else (u/int-error (str "Got non-dcons" dc1 dc2))))

(t/ann dmap-meet [dmap dmap -> dmap])
(defn dmap-meet [dm1 dm2]
  {:pre [(cr/dmap? dm1)
         (cr/dmap? dm2)]
   :post [(cr/dmap? %)]}
  (cr/->dmap (merge-with dcon-meet (:map dm1) (:map dm2))))


;current seen subtype relations, for recursive types
;(Set [Type Type])
(t/ann *cs-current-seen* (t/Set '[r/TCType r/TCType]))
(def ^:dynamic *cs-current-seen* #{})

(t/def-alias NoMentions
  "A set of variables not to mention in the constraints"
  (t/Set Symbol))

(t/def-alias ConstrainVars
  "The map of variables to be constrained to their bounds"
  (t/Map Symbol Bounds))

;; V : a set of variables not to mention in the constraints
;; X : the map of type variables to be constrained to their bounds
;; Y : the map of index variables to be constrained to their bounds
;; S : a type to be the subtype of T
;; T : a type
;; produces a cset which determines a substitution that makes S a subtype of T
;; implements the V |-_X S <: T => C judgment from Pierce+Turner, extended with
;; the index variables from the TOPLAS paper
(t/ann cs-gen* [NoMentions
                ConstrainVars
                ConstrainVars
                r/AnyType
                r/AnyType
                -> cset])
(defmulti cs-gen*
  (fn [V X Y S T] 
    {:pre [((u/set-c? symbol?) V)
           (every? (u/hash-c? symbol r/Bounds?) [X Y])
           (r/AnyType? S)
           (r/AnyType? T)]}
    [(class S) (class T) (impl/current-impl)]))

; (see cs-gen*)
;cs-gen calls cs-gen*, remembering the current subtype for recursive types
; Add methods to cs-gen*, but always call cs-gen

(declare cs-gen-right-F cs-gen-left-F cs-gen-datatypes-or-records cs-gen-list
         cs-gen-filter-set cs-gen-object)

(t/ann ^:no-check cs-gen [(t/Set Symbol) 
               (t/Map Symbol Bounds)
               (t/Map Symbol Bounds)
               r/AnyType
               r/AnyType
               -> cset])
(defn cs-gen [V X Y S T]
  {:pre [((u/set-c? symbol?) V)
         (every? (u/hash-c? symbol? r/Bounds?) [X Y])
         (r/AnyType? S)
         (r/AnyType? T)]
   :post [(cr/cset? %)]}
  ;(prn "cs-gen" (prs/unparse-type S) (prs/unparse-type T))
  (if (or (*cs-current-seen* [S T]) 
          (sub/subtype? S T))
    ;already been around this loop, is a subtype
    (cr/empty-cset X Y)
    (binding [*cs-current-seen* (conj *cs-current-seen* [S T])]
      (cond
        (r/Top? T)
        (cr/empty-cset X Y)

        ;IMPORTANT: handle frees first
        (and (r/F? S)
             (contains? X (.name ^F S)))
        (cs-gen-left-F V X Y S T)

        (and (r/F? T)
             (contains? X (.name ^F T)))
        (cs-gen-right-F V X Y S T)
        
        ;values are subtypes of their classes
        (and (r/Value? S)
             (impl/checking-clojure?))
        (let [^Value S S]
          (impl/impl-case
            :clojure (if (nil? (.val S))
                       (fail! S T)
                       (cs-gen V X Y
                               (apply c/In (c/RClass-of (class (.val S)))
                                      (cond 
                                        ;keyword values are functions
                                        (keyword? (.val S)) [(c/keyword->Fn (.val S))]
                                        ;strings have a known length as a seqable
                                        (string? (.val S)) [(r/make-ExactCountRange (count (.val S)))]))
                               T))
            :cljs (cond
                    (number? (.val S)) (cs-gen V X Y (r/NumberCLJS-maker) T)
                    :else (fail! S T))))

        ;; constrain body to be below T, but don't mention the new vars
        (r/Poly? S)
        (let [nms (repeatedly (.nbound ^Poly S) gensym)
              body (c/Poly-body* nms S)]
          (cs-gen (set/union (set nms) V) X Y body T))

        (r/Name? S)
        (cs-gen V X Y (c/resolve-Name S) T)

        (r/Name? T)
        (cs-gen V X Y S (c/resolve-Name T))

        (and (r/TApp? S)
             (not (r/F? (.rator ^TApp S))))
        (cs-gen V X Y (c/resolve-TApp S) T)

        (and (r/TApp? T)
             (not (r/F? (.rator ^TApp T))))
        (cs-gen V X Y S (c/resolve-TApp T))

        ;constrain *each* element of S to be below T, and then combine the constraints
        (r/Union? S)
        (cset-meet*
          (cons (cr/empty-cset X Y)
                (mapv #(cs-gen V X Y % T) (.types ^Union S))))

        ;; find *an* element of T which can be made a supertype of S
        (r/Union? T)
        (if-let [cs (seq (filter identity (mapv #(handle-failure (cs-gen V X Y S %))
                                                (.types ^Union T))))]
          (cset-combine cs)
          (fail! S T))

        (and (r/Intersection? S)
             (r/Intersection? T))
        (cset-meet*
          (doall
            ; for each element of T, we need at least one element of S that works
            ; FIXME I don't think this is sound if there are type variables in more
            ; than one member of the intersection. eg. (I x y)
            ; The current implementation is useful for types like (I (Seqable Any) (CountRange 1))
            ; so we want to preserve the current behaviour while handling the other cases intelligently.
            (for [t* (:types T)]
              (if-let [results (doall
                                 (seq (filter identity
                                              (map #(handle-failure
                                                      (cs-gen V X Y % t*))
                                                   (:types S)))))]
                (cset-combine results)
                ; check this invariant after instantiation, and don't use this
                ; relationship to constrain any variables.
                (do
                  ;(prn "adding delayed constraint" (pr-str (map prs/unparse-type [S T])))
                  (-> (cr/empty-cset X Y)
                      (insert-delayed-constraint S T)))))))

        ;; find *an* element of S which can be made a subtype of T
        (r/Intersection? S)
        (if (some r/F? (:types S)) 
          ; same as Intersection <: Intersection case
          (do ;(prn "adding delayed constraint" (pr-str (map prs/unparse-type [S T])))
              (-> (cr/empty-cset X Y)
                  (insert-delayed-constraint S T)))
          (if-let [cs (some #(handle-failure (cs-gen V X Y % T))
                            (:types S))]
            (do ;(prn "intersection S normal case" (map prs/unparse-type [S T]))
                cs)
            (fail! S T)))

        ;constrain *every* element of T to be above S, and then meet the constraints
        ; we meet instead of cset-combine because we want all elements of T to be under
        ; S simultaneously.
        (r/Intersection? T)
        (cset-meet*
          (cons (cr/empty-cset X Y)
                (mapv #(cs-gen V X Y S %) (:types T))))

        (and (r/Extends? S)
             (r/Extends? T))
        (let [;_ (prn "Extends" (prs/unparse-type S) (prs/unparse-type T)
              ;       V X Y)
              ; FIXME handle negative information
              cs (cset-meet*
                   (doall
                     ; for each element of T, we need at least one element of S that works
                     (for [t* (:extends T)]
                       (if-let [results (doall
                                          (seq (filter identity
                                                       (map #(handle-failure
                                                               (cs-gen V X Y % t*))
                                                            (:extends S)))))]
                         (cset-meet* results)
                         (fail! S T)))))]
          cs)

        ;; find *an* element of S which can be made a subtype of T
        ;; we don't care about what S does *not* implement, so we don't
        ;; use the "without" field of Extends
        (r/Extends? S)
        (if-let [cs (some #(handle-failure (cs-gen V X Y % T))
                          (:extends S))]
          cs
          (fail! S T))

        ;constrain *every* element of T to be above S, and then meet the constraints
        ; also ensure T's negative information is reflected in S
        (r/Extends? T)
        (let [cs (cset-meet*
                   (cons (cr/empty-cset X Y)
                         (mapv #(cs-gen V X Y S %) (:extends T))))
              satisfies-without? (not-any? identity 
                                           (doall
                                             (map #(handle-failure (cs-gen V X Y % T))
                                                  (:without T))))]
          (if satisfies-without?
            cs
            (fail! S T)))


        (r/App? S)
        (cs-gen V X Y (c/resolve-App S) T)

        (r/App? T)
        (cs-gen V X Y S (c/resolve-App T))

        (and (r/DataType? S)
             (r/DataType? T)) (cs-gen-datatypes-or-records V X Y S T)

        ; handle Record as HMap
        (r/Record? S) (cs-gen V X Y (c/Record->HMap S) T)

        (and (r/HeterogeneousVector? S)
             (r/HeterogeneousVector? T))
        (let [^HeterogeneousVector S S 
              ^HeterogeneousVector T T]
          (cset-meet* (doall
                  (concat
                    [(cs-gen-list V X Y (.types S) (.types T))]
                    (map (fn [fs1 fs2]
                           (cs-gen-filter-set V X Y fs1 fs2))
                         (.fs S) (.fs T))
                    (map (fn [o1 o2]
                           (cs-gen-object V X Y o1 o2))
                         (.objects S) (.objects T))))))

        (and (r/HeterogeneousMap? S)
             (r/HeterogeneousMap? T))
        (let [Skeys (set (keys (:types S)))
              Tkeys (set (keys (:types T)))]
          ; All keys must be values
          (when-not (every? r/Value? (set/union Skeys Tkeys))
            (fail! S T))
          ; All keys on the left must appear on the right
          (when-not (empty? (set/difference Skeys Tkeys))
            (fail! S T))
          (let [nocheck-keys (set/difference Tkeys Skeys)
                STvals (vals (merge-with vector (:types S) (apply dissoc (:types T) nocheck-keys)))
                Svals (map first STvals)
                Tvals (map second STvals)]
            (cs-gen-list V X Y Svals Tvals)))
        
        (and (r/PrimitiveArray? S)
             (r/PrimitiveArray? T)
             (impl/checking-clojure?))
        (let [^PrimitiveArray S S 
              ^PrimitiveArray T T]
          (cs-gen-list 
            V X Y
            ;input contravariant
            ;output covariant
            [(.input-type T) (.output-type S)]
            [(.input-type S) (.output-type T)]))
        
        (and (r/HeterogeneousMap? S)
             (r/RClass? T)
             (impl/checking-clojure?))
        (let [^HeterogeneousMap S S]
          ; Partial HMaps do not record absence of fields, only subtype to (APersistentMap Any Any)
          (let [new-S (if (c/complete-hmap? S)
                        (impl/impl-case
                          :clojure (c/RClass-of APersistentMap [(apply c/Un (keys (.types S)))
                                                                (apply c/Un (vals (.types S)))])
                          :cljs (c/Protocol-of 'cljs.core/IMap [(apply c/Un (keys (.types S)))
                                                                (apply c/Un (vals (.types S)))]))

                        (impl/impl-case
                          :clojure (c/RClass-of APersistentMap [r/-any r/-any])
                          :cljs (c/Protocol-of 'cljs.core/IMap [r/-any r/-any])))]
            (cs-gen V X Y new-S T)))

        :else
        (cs-gen* V X Y S T)))))

;; FIXME - anything else to say about And and OrFilters?
(t/ann cs-gen-filter [NoMentions ConstrainVars ConstrainVars fr/Filter fr/Filter
                      -> cset])
(defn cs-gen-filter [V X Y s t]
  {:pre [((u/set-c? symbol?) V)
         (every? (u/hash-c? symbol? r/Bounds?) [X Y])
         (fr/Filter? s)
         (fr/Filter? t)]
   :post [(cr/cset? %)]}
  (cond
    (= s t) (cr/empty-cset X Y)
    (fr/TopFilter? t) (cr/empty-cset X Y)

    (and (fr/TypeFilter? s)
         (fr/TypeFilter? t)
         (and (= (:path s) (:path t))
              (= (:id s) (:id t))))
    (cset-meet (cs-gen V X Y (:type s) (:type t))
               (cs-gen V X Y (:type t) (:type s)))

    (and (fr/NotTypeFilter? s)
         (fr/NotTypeFilter? t)
         (and (= (:path s) (:path t))
              (= (:id s) (:id t))))
    (cset-meet (cs-gen V X Y (:type s) (:type t))
               (cs-gen V X Y (:type t) (:type s)))

    ; simple case for unifying x and y in (& (is x sym) ...) (is y sym)
    (and (fr/AndFilter? s)
         (fr/TypeFilter? t)
         (every? fo/atomic-filter? (:fs s))
         (= 1 (count fr/TypeFilter?) (:fs s)))
    (let [tf (first (filter fr/TypeFilter? (:fs s)))]
      (cs-gen-filter V X Y tf t))
    :else (fail! s t)))

;must be *latent* flow sets
(t/ann cs-gen-flow-set [NoMentions ConstrainVars ConstrainVars FlowSet FlowSet
                      -> cset])
(defn cs-gen-flow-set [V X Y s t]
  {:pre [((u/set-c? symbol?) V)
         (every? (u/hash-c? symbol? r/Bounds?) [X Y])
         (r/FlowSet? s)
         (r/FlowSet? t)]
   :post [(cr/cset? %)]}
  (cond
    (= s t) (cr/empty-cset X Y)
    :else
    (let [{n1 :normal} s
          {n2 :normal} t]
      (cs-gen-filter V X Y n1 n2))))

;must be *latent* filter sets
(t/ann cs-gen-filter-set [NoMentions
                          ConstrainVars
                          ConstrainVars
                          fr/Filter
                          fr/Filter
                          -> cset])
(defn cs-gen-filter-set [V X Y s t]
  {:pre [((u/set-c? symbol?) V)
         (every? (u/hash-c? symbol? r/Bounds?) [X Y])
         (fr/FilterSet? s)
         (fr/FilterSet? t)]
   :post [(cr/cset? %)]}
  (cond
    (= s t) (cr/empty-cset X Y)
    :else
    (let [{s+ :then s- :else} s
          {t+ :then t- :else} t]
      (cset-meet (cs-gen-filter V X Y s+ t+)
                 (cs-gen-filter V X Y s- t-)))))

(t/ann cs-gen-object [NoMentions ConstrainVars ConstrainVars
                      or/RObject or/RObject -> cset])
(defn cs-gen-object [V X Y s t]
  {:pre [((u/set-c? symbol?) V)
         (every? (u/hash-c? symbol? r/Bounds?) [X Y])
         (or/RObject? s)
         (or/RObject? t)]
   :post [(cr/cset? %)]}
  (cond
    (= s t) (cr/empty-cset X Y)
    (or/EmptyObject? t) (cr/empty-cset X Y)
    ;;FIXME do something here
    :else (fail! s t)))

(defmethod cs-gen* :default
  [V X Y S T]
#_(prn "cs-gen* default" (class S) (class T))
  #_(when (some r/Result? [S T])
    (throw (IllegalArgumentException. (u/error-msg "Result on left or right "
                                                   (pr-str S) " " (pr-str T)))))
  (when-not (sub/subtype? S T) 
    (fail! S T))
  (cr/empty-cset X Y))

(declare cs-gen-Function)

;FIXME handle variance
(defmethod cs-gen* [TApp TApp impl/any-impl]
  [V X Y ^TApp S ^TApp T]
  (when-not (= (.rator S) (.rator T)) 
    (fail! S T))
  (cset-meet*
    (mapv (fn> [s1 :- r/Type 
                t1 :- r/Type]
            (cs-gen V X Y s1 t1)) 
          (.rands S) (.rands T))))

(defmethod cs-gen* [FnIntersection FnIntersection impl/any-impl]
  [V X Y ^FnIntersection S ^FnIntersection T] 
  (cset-meet*
    (doall
      (for> :- cset
        [t-arr :- Function, (.types T)]
        ;; for each t-arr, we need to get at least s-arr that works
        (let [results (filter (t/inst identity (U false cset))
                              (doall
                                (for> :- (U false cset)
                                  [s-arr :- Function, (.types S)]
                                  (handle-failure
                                    (cs-gen-Function V X Y s-arr t-arr)))))]
          ;; ensure that something produces a constraint set
          (when (empty? results) 
            (fail! S T))
          (cset-combine results))))))

(defmethod cs-gen* [Result Result impl/any-impl]
  [V X Y S T] 
  (cset-meet* [(cs-gen V X Y (r/Result-type* S) (r/Result-type* T))
               (cs-gen-filter-set V X Y (r/Result-filter* S) (r/Result-filter* T))
               (cs-gen-object V X Y (r/Result-object* S) (r/Result-object* T))
               (cs-gen-flow-set V X Y (r/Result-flow* S) (r/Result-flow* T))]))

(defmethod cs-gen* [Value AnyValue impl/any-impl] 
  [V X Y S T] 
  (cr/empty-cset X Y))

(defmethod cs-gen* [HeterogeneousSeq RClass impl/clojure]
  [V X Y S T]
  (cs-gen V X Y 
          (c/In (impl/impl-case
                  :clojure (c/RClass-of ISeq [(apply c/Un (:types S))]) 
                  :cljs (c/Protocol-of 'cljs.core/ISeq [(apply c/Un (:types S))]))
                (r/make-ExactCountRange (count (:types S))))
          T))

(defmethod cs-gen* [HeterogeneousList RClass impl/clojure]
  [V X Y S T]
  (cs-gen V X Y 
          (c/In (impl/impl-case
                  :clojure (c/RClass-of IPersistentList [(apply c/Un (:types S))])
                  :cljs (c/Protocol-of 'cljs.core/IList [(apply c/Un (:types S))]))
                (r/make-ExactCountRange (count (:types S))))
          T))

(defmethod cs-gen* [HeterogeneousVector RClass impl/clojure]
  [V X Y S T]
  (cs-gen V X Y 
          (c/In (impl/impl-case
                  :clojure (c/RClass-of APersistentVector [(apply c/Un (:types S))]) 
                  :cljs (c/Protocol-of 'cljs.core/IVector [(apply c/Un (:types S))]))
                (r/make-ExactCountRange (count (:types S))))
          T))

(t/ann cs-gen-datatypes-or-records
       [NoMentions ConstrainVars ConstrainVars DataType DataType -> cset])
(defn cs-gen-datatypes-or-records 
  [V X Y S T]
  {:pre [(every? r/DataType? [S T])]}
  (when-not (= (:the-class S) (:the-class T)) 
    (fail! S T))
  (if (seq (:poly? S))
    (cs-gen-list V X Y (:poly? S) (:poly? T))
    (cr/empty-cset X Y)))

; constrain si and ti according to variance
(defn cs-gen-with-variance
  [V X Y variance si ti]
  {:pre [(r/variance? variance)
         (r/AnyType? si)
         (r/AnyType? ti)]
   :post [(cr/cset? %)]}
  (case variance
    (:covariant :constant) (cs-gen V X Y si ti)
    :contravariant (cs-gen V X Y ti si)
    :invariant (cset-meet (cs-gen V X Y si ti)
                          (cs-gen V X Y ti si))))

;constrain lists of types ss and ts according to variances
(defn cs-gen-list-with-variances
  [V X Y variances ss ts]
  {:pre [(every? r/variance? variances)
         (every? r/AnyType? ss)
         (every? r/AnyType? ts)
         (apply = (map count [variances ss ts]))]
   :post [(cr/cset? %)]}
  (cset-meet*
    (cons (cr/empty-cset X Y)
          (doall
            (for [[variance si ti] (map vector variances ss ts)]
              (cs-gen-with-variance V X Y variance si ti))))))

;(defmethod cs-gen* [RClass RClass impl/clojure]
;  [V X Y S T]
;  ;(prn "cs-gen* RClass RClass")
;  (let [rsupers (c/RClass-supers* S)
;        relevant-S (some #(when (r/RClass? %)
;                            (and (= (:the-class %) (:the-class T))
;                                 %))
;                         (map c/fully-resolve-type (conj rsupers S)))]
;    (prn "S" (prs/unparse-type S))
;    (prn "T" (prs/unparse-type T))
;    (prn "supers" (map (juxt prs/unparse-type class) rsupers))
;    (when relevant-S
;      (prn "relevant-S" (prs/unparse-type relevant-S)))
;    (cond
;      relevant-S
;      (cs-gen-list-with-variances V X Y
;                                  (:variances T) 
;                                  (:poly? relevant-S) 
;                                  (:poly? T)))
;      :else (fail! S T)))

(defmethod cs-gen* [RClass RClass impl/clojure]
  [V X Y S T]
  ;(prn "cs-gen* RClass RClass")
  (let [rsupers (c/RClass-supers* S)
        relevant-S (some #(when (r/RClass? %)
                            (and (= (:the-class %) (:the-class T))
                                 %))
                         (map c/fully-resolve-type (conj rsupers S)))]
  ;  (prn "S" (prs/unparse-type S))
  ;  (prn "T" (prs/unparse-type T))
;    (prn "supers" (map (juxt prs/unparse-type class) rsupers))
;    (when relevant-S
  ;    (prn "relevant-S" (prs/unparse-type relevant-S)))
    (cond
      relevant-S
      (cset-meet*
        (cons (cr/empty-cset X Y)
              (doall
                (for [[vari si ti] (map vector
                                        (:variances T)
                                        (:poly? relevant-S)
                                        (:poly? T))]
                  (case vari
                    (:covariant :constant) (cs-gen V X Y si ti)
                    :contravariant (cs-gen V X Y ti si)
                    :invariant (cset-meet (cs-gen V X Y si ti)
                                          (cs-gen V X Y ti si)))))))
      :else (fail! S T))))

(defmethod cs-gen* [Protocol Protocol impl/any-impl]
  [V X Y S T]
  (if (= (:the-var S)
         (:the-var T))
    (cset-meet*
      (cons (cr/empty-cset X Y)
            (doall
              (for [[vari si ti] (map vector
                                      (:variances T)
                                      (:poly? S)
                                      (:poly? T))]
                (case vari
                  (:covariant :constant) (cs-gen V X Y si ti)
                  :contravariant (cs-gen V X Y ti si)
                  :invariant (cset-meet (cs-gen V X Y si ti)
                                        (cs-gen V X Y ti si)))))))
    (fail! S T)))

(t/ann demote-F [NoMentions ConstrainVars ConstrainVars F r/Type -> cset])
(defn demote-F [V X Y {:keys [name] :as S} T]
  {:pre [(r/F? S)]}
  ;constrain T to be below S (but don't mention V)
  (assert (contains? X name) (str X name))
  (when (and (r/F? T)
             (denv/bound-index? (:name T))
             (not (free-ops/free-in-scope (:name T))))
    (fail! S T))
  (let [dt (prmt/demote-var T V)]
    (-> (cr/empty-cset X Y)
      (insert-constraint name (r/Bottom) dt (X name)))))

(t/ann promote-F [NoMentions ConstrainVars ConstrainVars r/Type F -> cset])
(defn promote-F [V X Y S {:keys [name] :as T}]
  {:pre [(r/F? T)]}
  ;T is an F
  ;S is any Type
  ;constrain T to be above S (but don't mention V)
  (assert (contains? X name) (str X T))
  (when (and (r/F? S)
             (denv/bound-index? (:name S))
             (not (free-ops/free-in-scope (:name S))))
    (fail! S T))
  (let [ps (prmt/promote-var S V)]
    (-> (cr/empty-cset X Y)
      (insert-constraint name ps r/-any (X name)))))

(t/ann cs-gen-left-F [NoMentions ConstrainVars ConstrainVars F r/Type -> cset])
(defn cs-gen-left-F [V X Y ^F S T]
  {:pre [(r/F? S)]}
  #_(prn "cs-gen* [F Type]" S T)
  (cond
    (contains? X (.name S))
    (demote-F V X Y S T)

    (and (r/F? T)
         (contains? X (.name ^F T)))
    (promote-F V X Y S T)

    :else (fail! S T)))

(t/ann cs-gen-right-F [NoMentions ConstrainVars ConstrainVars r/Type F -> cset])
(defn cs-gen-right-F [V X Y S T]
  {:pre [(r/F? T)]}
  ;(prn "cs-gen* [Type F]" S T X)
  (cond
    (contains? X (:name T))
    (promote-F V X Y S T)

    (and (r/F? S)
         (contains? X (:name S)))
    (demote-F V X Y S T)

    :else (fail! S T)))

(t/ann singleton-dmap [Symbol cr/DCon -> dmap])
(defn singleton-dmap [dbound dcon]
  (cr/->dmap {dbound dcon}))

(t/ann mover [cset Symbol (U nil (t/Seqable Symbol)) -> cset])
(defn mover [cset dbound vars f]
  {:pre [(cr/cset? cset)
         (symbol? dbound)
         (every? symbol? vars)]
   :post [(cr/cset? %)]}
  (cr/->cset (map
               (fn> [{cmap :fixed dmap :dmap :keys [delayed-checks]} :- cset-entry]
                 (cr/->cset-entry (apply dissoc cmap dbound vars)
                                  (dmap-meet 
                                    (singleton-dmap 
                                      dbound
                                      (f cmap dmap))
                                    (cr/->dmap (dissoc (:map dmap) dbound)))
                                  delayed-checks))
               (:maps cset))))

;; dbound : index variable
;; cset : the constraints being manipulated
;FIXME needs no-check for unreachable flow filter error
(t/ann ^:no-check move-rest-to-dmap [cset Symbol & :optional {:exact (U nil true)} -> cset])
(defn move-rest-to-dmap [cset dbound & {:keys [exact]}]
  {:pre [(cr/cset? cset)
         (symbol? dbound)
         ((some-fn nil? true?) exact)]
   :post [(cr/cset? %)]}
  (mover cset dbound nil
         (fn [cmap dmap]
           ((if exact cr/->dcon-exact cr/->dcon)
              nil
              (if-let [c (cmap dbound)]
                c
                (u/int-error (str "No constraint for bound " dbound)))))))


;; dbound : index variable
;; vars : listof[type variable] - temporary variables
;; cset : the constraints being manipulated
;; takes the constraints on vars and creates a dmap entry contstraining dbound to be |vars|
;; with the constraints that cset places on vars
(t/ann move-vars-to-dmap [cset Symbol (U nil (t/Seqable Symbol)) -> cset])
;FIXME no-check, flow error
(defn ^:no-check move-vars-to-dmap [cset dbound vars]
  {:pre [(cr/cset? cset)
         (symbol? dbound)
         (every? symbol? vars)]
   :post [(cr/cset? %)]}
  (mover cset dbound vars
         (fn [cmap dmap]
           (cr/->dcon (doall (for> :- c
                               [v :- Symbol, vars]
                               (if-let [c (cmap v)]
                                 c
                                 (u/int-error (str "No constraint for new var " v)))))
                      nil))))

;; This one's weird, because the way we set it up, the rest is already in the dmap.
;; This is because we create all the vars, then recall cgen/arr with the new vars
;; in place, and the "simple" case will then call move-rest-to-dmap.  This means
;; we need to extract that result from the dmap and merge it with the fixed vars
;; we now handled.  So I've extended the mover to give access to the dmap, which we use here.
;FIXME no-check because of unreachable flow 
(t/ann ^:no-check move-vars+rest-to-dmap 
       [cset Symbol (t/Set Symbol) & :optional {:exact (U nil true)} -> cset])
(defn move-vars+rest-to-dmap [cset dbound vars & {:keys [exact]}]
  {:pre [(cr/cset? cset)
         (symbol? dbound)
         ((u/set-c? symbol?) vars)
         ((some-fn nil? true?) exact)]
   :post [(cr/cset? %)]}
  (mover cset dbound vars
         (fn [cmap dmap]
           ((if exact cr/->dcon-exact cr/->dcon)
              (doall
                (for [v vars]
                  (if-let [c (cmap v)]
                    c
                    (u/int-error (str "No constraint for new var " v)))))
              (if-let [c ((:map dmap) dbound)]
                (cond
                  (and (cr/dcon? c)
                       (not (:fixed c))) (:rest c)
                  (and (cr/dcon-exact? c)
                       (not (:fixed c))) (:rest c)
                  :else (u/int-error (str "did not a get a rest-only dcon when moving to the dmap")))
                (u/int-error (str "No constraint for bound " dbound)))))))

;; Maps dotted vars (combined with dotted types, to ensure global uniqueness)
;; to "fresh" symbols.
;; That way, we can share the same "fresh" variables between the elements of a
;; cset if they're talking about the same dotted variable.
;; This makes it possible to reduce the size of the csets, since we can detect
;; identical elements that would otherwise differ only by these fresh vars.
;; The domain of this map is pairs (var . dotted-type).
;; The range is this map is a list of symbols generated on demand, as we need
;; more dots.
(t/ann DOTTED-VAR-STORE (t/Atom1 (t/Map '[r/Type Symbol] Symbol)))
(def ^:private DOTTED-VAR-STORE (atom {}))

;; Take (generate as needed) n symbols that correspond to variable var used in
;; the context of type t.
;FIXME no-check, trans-dots needs to be generalised
(t/ann ^:no-check var-store-take [Symbol r/Type t/Int -> (t/Seqable Symbol)])
(defn- var-store-take [var t n]
  (let [key [t n]
        res (@DOTTED-VAR-STORE key)]
    (if (>= (count res) n)
      ;; there are enough symbols already, take n
      (take n res)
      ;; we need to generate more
      (let [new (take (- n (count res))
                      (repeatedly #(gensym var)))
            all (concat res new)]
        (let [assoc' (t/inst assoc '[r/Type Symbol] Symbol Any)
              swap!' (t/inst swap! (t/Map '[r/Type Symbol] Symbol) (t/Map '[r/Type Symbol] Symbol)
                             '[r/Type Symbol] Symbol)]
          (swap!' DOTTED-VAR-STORE assoc' key all))
        all))))

(t/ann cs-gen-Function
       [NoMentions ConstrainVars ConstrainVars Function Function -> cset])
(defn cs-gen-Function
  [V X Y S T]
  {:pre [((u/set-c? symbol?) V)
         (every? (u/hash-c? symbol? r/Bounds?) [X Y])
         (r/Function? S)
         (r/Function? T)]
   :post [(cr/cset? %)]}
  ;(prn "cs-gen-Function")
  (letfn [(cg [S T] (cs-gen V X Y S T))]
    (cond
      ;easy case - no rests, drests, kws
      (and (not (:rest S))
           (not (:rest T))
           (not (:drest S))
           (not (:drest T))
           (not (:kws S))
           (not (:kws T)))
      ; contravariant
      (let [;_ (prn "easy case")
            ]
        (cset-meet* [(cs-gen-list V X Y (:dom T) (:dom S))
                     ; covariant
                     (cg (:rng S) (:rng T))]))

      ;just a rest arg, no drest, no keywords
      (and (or (:rest S)
               (:rest T))
           (not (:drest S))
           (not (:drest T))
           (not (:kws S))
           (not (:kws T)))
      (let [arg-mapping (cond
                          ;both rest args are present, so make them the same length
                          (and (:rest S) (:rest T))
                          (cs-gen-list V X Y 
                                       (cons (:rest T) (concat (:dom T) (repeat (- (count (:dom S))
                                                                                   (count (:dom T)))
                                                                                (:rest T))))
                                       (cons (:rest S) (concat (:dom S) (repeat (- (count (:dom T))
                                                                                   (count (:dom S)))
                                                                                (:rest S)))))
                          ;no rest arg on the right, so just pad left and forget the rest arg
                          (and (:rest S) (not (:rest T)))
                          (let [new-S (concat (:dom S) (repeat (- (count (:dom T))
                                                                  (count (:dom S)))
                                                               (:rest S)))]
                            ;                            (prn "infer rest arg on left")
                            ;                            (prn "left dom" (map prs/unparse-type (:dom S)))
                            ;                            (prn "right dom" (map prs/unparse-type (:dom T)))
                            ;                            (prn "new left dom" (map prs/unparse-type new-S))
                            (cs-gen-list V X Y (:dom T) new-S))
                          ;no rest arg on left, or wrong number = fail
                          :else (fail! S T))
            ret-mapping (cs-gen V X Y (:rng S) (:rng T))]
        (cset-meet* [arg-mapping ret-mapping]))

      ;; dotted on the left, nothing on the right
      (and (not (:rest S))
           (not (:rest T))
           (:drest S)
           (not (:drest T))
           (not (:kws S))
           (not (:kws T)))
      (let [{dty :pre-type dbound :name} (:drest S)]
        (when-not (Y dbound)
          (fail! S T))
        (when-not (<= (count (:dom S)) (count (:dom T)))
          (fail! S T))
        (let [vars (var-store-take dbound dty (- (count (:dom T))
                                                 (count (:dom S))))
              new-tys (doall (for [var vars]
                               (subst/substitute (r/make-F var) dbound dty)))
              new-s-arr (r/Function-maker (concat (:dom S) new-tys) (:rng S) nil nil nil)
              new-cset (cs-gen-Function V 
                                        ;move dotted lower/upper bounds to vars
                                        (merge X (zipmap vars (repeat (Y dbound)))) Y new-s-arr T)]
          (move-vars-to-dmap new-cset dbound vars)))

      ;; dotted on the right, nothing on the left
      (and (not ((some-fn :rest :drest) S))
           (:drest T))
      (let [{dty :pre-type dbound :name} (:drest T)]
        (when-not (Y dbound)
          (fail! S T))
        (when-not (<= (count (:dom T)) (count (:dom S)))
          (fail! S T))
        (let [vars (var-store-take dbound dty (- (count (:dom S)) (count (:dom T))))
              new-tys (doall
                        (for [var vars]
                          (subst/substitute (r/make-F var) dbound dty)))
              ;_ (prn "dotted on the right, nothing on the left")
              ;_ (prn "vars" vars)
              new-t-arr (r/Function-maker (concat (:dom T) new-tys) (:rng T) nil nil nil)
              ;_ (prn "S" (prs/unparse-type S))
              ;_ (prn "new-t-arr" (prs/unparse-type new-t-arr))
              new-cset (cs-gen-Function V 
                                        ;move dotted lower/upper bounds to vars
                                        (merge X (zipmap vars (repeat (Y dbound)))) Y S new-t-arr)]
          (move-vars-to-dmap new-cset dbound vars)))

      ;; * <: ...
      (and (:rest S)
           (:drest T))
      (let [{t-dty :pre-type dbound :name} (-> T :drest)]
        (when-not (Y dbound)
          (fail! S T))
        (if (<= (count (:dom S)) (count (:dom T)))
          ;; the simple case
          (let [arg-mapping (cs-gen-list V X Y (:dom T) (concat (:dom S) (repeat (- (count (:dom T)) (count (:dom S))) (:rest S))))
                darg-mapping (move-rest-to-dmap (cs-gen V (merge X {dbound (Y dbound)}) Y t-dty (:rest S)) dbound)
                ret-mapping (cg (:rng S) (:rng T))]
            (cset-meet* [arg-mapping darg-mapping ret-mapping]))
          ;; the hard case
          (let [vars (var-store-take dbound t-dty (- (count (:dom S)) (count (:dom T))))
                new-tys (doall (for [var vars]
                                 (subst/substitute (r/make-F var) dbound t-dty)))
                new-t-arr (r/Function-maker (concat (:dom T) new-tys) (:rng T) nil (r/DottedPretype-maker t-dty dbound) nil)
                new-cset (cs-gen-Function V (merge X (zipmap vars (repeat (Y dbound))) X) Y S new-t-arr)]
            (move-vars+rest-to-dmap new-cset dbound vars))))

:else 
(u/nyi-error (pr-str "NYI Function inference " (prs/unparse-type S) (prs/unparse-type T))))))

(defmethod cs-gen* [Function Function impl/any-impl]
  [V X Y S T]
  #_(prn "cs-gen* [Function Function]")
  (cs-gen-Function V X Y S T))

;; C : cset? - set of constraints found by the inference engine
;; Y : (setof symbol?) - index variables that must have entries
;; R : Type? - result type into which we will be substituting
(t/ann subst-gen [cset (t/Set Symbol) r/AnyType -> (U nil cr/SubstMap)])
(defn subst-gen [C Y R]
  {:pre [(cr/cset? C)
         ((u/set-c? symbol?) Y)
         (r/AnyType? R)]
   :post [((some-fn nil? cr/substitution-c?) %)]}
  (let [var-hash (frees/fv-variances R)
        idx-hash (frees/idx-variances R)]
    (letfn [
            ;; v : Symbol - variable for which to check variance
            ;; h : (Hash F Variance) - hash to check variance in (either var or idx hash)
            ;; variable: Symbol - variable to use instead, if v was a temp var for idx extension
            (constraint->type [{{:keys [upper-bound lower-bound]} :bnds :keys [S X T] :as v} h & {:keys [variable]}]
              {:pre [(cr/c? v)
                     (frees/variance-map? h)
                     ((some-fn nil? symbol?) variable)]}
              (when-not (sub/subtype? S T) (fail! S T))
              (when (some r/TypeFn? [upper-bound lower-bound]) (u/nyi-error "Higher kinds"))
              (let [var (h (or variable X) :constant)
                    inferred (case var
                               (:constant :covariant) S
                               :contravariant T
                               :invariant S)]
                inferred))
            ;TODO implement generalize
            ;                  (let [gS (generalize S)]
            ;                    (if (subtype? gS T)
            ;                      gS
            ;                      S))

            ;; Since we don't add entries to the empty cset for index variables (since there is no
            ;; widest constraint, due to dcon-exacts), we must add substitutions here if no constraint
            ;; was found.  If we're at this point and had no other constraints, then adding the
            ;; equivalent of the constraint (dcon null (c Bot X Top)) is okay.
            (extend-idxs [S]
              {:pre [(cr/substitution-c? S)]}
              (let [fi-R (frees/fi R)] ;free indices in R
                ;; If the index variable v is not used in the type, then
                ;; we allow it to be replaced with the empty list of types;
                ;; otherwise we error, as we do not yet know what an appropriate
                ;; lower bound is.
                (letfn [(demote-check-free [v]
                          {:pre [(symbol? v)]}
                          (if (fi-R v)
                            (u/int-error "attempted to demote dotted variable")
                            (cr/->i-subst nil)))]
                  ;; absent-entries is false if there's an error in the substitution, otherwise
                  ;; it's a list of variables that don't appear in the substitution
                  (let [absent-entries
                        (reduce (fn [no-entry v]
                                  {:pre [(symbol? v)]}
                                  (let [entry (S v)]
                                    ;; Make sure we got a subst entry for an index var
                                    ;; (i.e. a list of types for the fixed portion
                                    ;;  and a type for the starred portion)
                                    (cond
                                      (false? no-entry) no-entry
                                      (not entry) (cons v no-entry)
                                      (or (cr/i-subst? entry) 
                                          (cr/i-subst-starred? entry)
                                          (cr/i-subst-dotted? entry)) no-entry
                                      :else false)))
                                [] Y)]
                    (and absent-entries
                         (merge (into {}
                                      (for [missing absent-entries]
                                        (let [var (idx-hash missing :constant)]
                                          [missing
                                           (case var
                                             (:constant :covariant :invariant) (demote-check-free missing)
                                             :contravariant (cr/->i-subst-starred nil r/-any))])))
                                S))))))]

      (let [{cmap :fixed dmap* :dmap :keys [delayed-checks]} (-> C :maps first)
            _ (when-not (= 1 (count (:maps C))) 
                (u/int-error "More than one constraint set found"))
            dm (:map dmap*)
            subst (merge 
                    (into {}
                      (for [[k dc] dm]
                        (cond
                          (and (cr/dcon? dc) (not (:rest dc)))
                          [k (cr/->i-subst (doall
                                          (for [f (:fixed dc)]
                                            (constraint->type f idx-hash :variable k))))]
                          (and (cr/dcon? dc) (:rest dc))
                          [k (cr/->i-subst-starred (doall
                                                  (for [f (:fixed dc)]
                                                    (constraint->type f idx-hash :variable k)))
                                                (constraint->type (:rest dc) idx-hash))]
                          (cr/dcon-exact? dc)
                          [k (cr/->i-subst-starred (doall
                                                  (for [f (:fixed dc)]
                                                    (constraint->type f idx-hash :variable k)))
                                                (constraint->type (:rest dc) idx-hash))]
                          (cr/dcon-dotted? dc)
                          [k (cr/->i-subst-dotted (doall
                                                 (for [f (:fixed dc)]
                                                   (constraint->type f idx-hash :variable k)))
                                               (constraint->type (:dc dc) idx-hash :variable k)
                                               (:dbound dc))]
                          :else (u/int-error (prn-str "What is this? " dc)))))

                    (into {}
                      (for [[k v] cmap]
                        [k (cr/->t-subst (constraint->type v var-hash)
                                      (:bnds v))])))
            ;check delayed constraints and type variable bounds
            _ (let [t-substs (into {} (filter (fn [[_ v]] (cr/t-subst? v)) subst))
                    [names images] (let [s (seq t-substs)]
                                     [(map first s)
                                      (map (comp :type second) s)])]
                ;(prn delayed-checks)
                (doseq [[S T] delayed-checks]
                  (let [S* (subst/substitute-many S images names)
                        T* (subst/substitute-many T images names)]
                    ;(prn "delayed" (map prs/unparse-type [S* T*]))
                    (when-not (sub/subtype? S* T*)
                      (fail! S T))
                            #_(str "Delayed check failed"
                                 (mapv prs/unparse-type [S T]))))
                (doseq [[nme {inferred :type :keys [bnds]}] t-substs]
                  (when (some r/TypeFn? [(:upper-bound bnds) (:lower-bound bnds)]) (u/nyi-error "Higher kinds"))
                  (let [lower-bound (subst/substitute-many (:lower-bound bnds) images names)
                        upper-bound (subst/substitute-many (:upper-bound bnds) images names)]
                    (assert (sub/subtype? lower-bound upper-bound)
                            (u/error-msg "Lower-bound " (prs/unparse-type lower-bound)
                                         " is not below upper-bound " (prs/unparse-type upper-bound)))
                    (assert (and (sub/subtype? inferred upper-bound)
                                 (sub/subtype? lower-bound inferred))
                            (u/error-msg "Inferred type " (prs/unparse-type inferred)
                                       " is not between bounds " (prs/unparse-type lower-bound)
                                       " and " (prs/unparse-type upper-bound))))))]
        ;; verify that we got all the important variables
        (when-let [r (and (every? identity
                                  (for [v (frees/fv R)]
                                    (let [entry (subst v)]
                                      (and entry (cr/t-subst? entry)))))
                          (extend-idxs subst))]
          r)))))

;; V : a set of variables not to mention in the constraints
;; X : the set of type variables to be constrained mapped to their bounds
;; Y : the set of index variables to be constrained mapped to their bounds
;; S : a list of types to be the subtypes of T
;; T : a list of types
;; expected-cset : a cset representing the expected type, to meet early and
;;  keep the number of constraints in check. (empty by default)
;; produces a cset which determines a substitution that makes the Ss subtypes of the Ts
(t/ann cs-gen-list
       [NoMentions ConstrainVars ConstrainVars 
        (U nil (t/Seqable r/Type)) (U nil (t/Seqable r/Type))
        & :optional {:expected-cset (U nil cset)}
        -> cset])
(defn cs-gen-list [V X Y S T & {:keys [expected-cset] :or {expected-cset (cr/empty-cset {} {})}}]
  {:pre [((u/set-c? symbol?) V)
         (every? (u/hash-c? symbol? r/Bounds?) [X Y])
         (every? r/Type? (concat S T))
         (cr/cset? expected-cset)]
   :post [(cr/cset? %)]}
;  (prn "cs-gen-list" 
;       V X Y
;       (map prs/unparse-type S)
;       (map prs/unparse-type T))
  (when-not (= (count S) (count T))
    (fail! S T))
  (cset-meet*
    ;; We meet early to prune the csets to a reasonable size.
    ;; This weakens the inference a bit, but sometimes avoids
    ;; constraint explosion.
    (cons
      (cr/empty-cset X Y)
      (let [vector' (t/inst vector r/Type r/Type Any Any Any Any)
            map' (t/inst map '[r/Type r/Type] r/Type r/Type)]
        (doall 
          (for> :- cset
            [[s t] :- '[r/Type r/Type], (map' vector' S T)]
            (let [c (cs-gen V X Y s t)]
  ;            (prn "s" s)
  ;            (prn "t" t)
  ;            (prn "c" c)
  ;            (prn "expected cset" expected-cset)
              (cset-meet c expected-cset))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Infer

;; like infer, but dotted-var is the bound on the ...
;; and T-dotted is the repeated type
(t/ann infer-dots
  (Fn [ConstrainVars Symbol Bounds
       (U nil (t/Seqable r/Type)) (U nil (t/Seqable r/Type))
       r/Type (U nil r/AnyType) (t/Set Symbol)
       & :optional {:expected (U nil r/Type)} -> cr/SubstMap]))
(defn infer-dots [X dotted-var dotted-bnd S T T-dotted R must-vars & {:keys [expected]}]
  {:pre [((u/hash-c? symbol? r/Bounds?) X)
         (symbol? dotted-var)
         (r/Bounds? dotted-bnd)
         (every? #(every? r/Type? %) [S T])
         (r/Type? T-dotted) 
         (r/AnyType? R)
         ((u/set-c? symbol?) must-vars)
         ((some-fn nil? r/Type?) expected)]
   :post [(cr/substitution-c? %)]}
  (let [[short-S rest-S] (split-at (count T) S)
;        _ (prn "short-S" (map unparse-type short-S))
;        _ (prn "rest-S" (map unparse-type rest-S))
        expected-cset (if expected
                        (cs-gen #{} X {dotted-var dotted-bnd} R expected)
                        (cr/empty-cset {} {}))
;        _ (prn "expected-cset" expected-cset)
        cs-short (cs-gen-list #{} X {dotted-var dotted-bnd} short-S T
                              :expected-cset expected-cset)
        ;_ (prn "cs-short" cs-short)
        new-vars (var-store-take dotted-var T-dotted (count rest-S))
        new-Ts (doall
                 (for> :- r/Type
                   [v :- Symbol, new-vars]
                   (let [target (subst/substitute-dots (map r/make-F new-vars) nil dotted-var T-dotted)]
                     #_(prn "replace" v "with" dotted-var "in" (prs/unparse-type target))
                     (subst/substitute (r/make-F v) dotted-var target))))
        ;_ (prn "new-Ts" new-Ts)
        cs-dotted (cs-gen-list #{} (merge X (zipmap new-vars (repeat dotted-bnd))) {dotted-var dotted-bnd} rest-S new-Ts
                               :expected-cset expected-cset)
        ;_ (prn "cs-dotted" cs-dotted)
        cs-dotted (move-vars-to-dmap cs-dotted dotted-var new-vars)
        ;_ (prn "cs-dotted" cs-dotted)
        cs (cset-meet cs-short cs-dotted)
        ;_ (prn "cs" cs)
        ]
    (subst-gen (cset-meet cs expected-cset) #{dotted-var} R)))

(declare infer)

;; like infer, but T-var is the vararg type:
(t/ann infer-vararg
  (Fn [ConstrainVars ConstrainVars 
       (U nil (t/Seqable r/Type)) (U nil (t/Seqable r/Type))
       (U nil r/Type)
       (U nil r/AnyType) -> (U nil true false cr/SubstMap)]
      [ConstrainVars ConstrainVars 
       (U nil (t/Seqable r/Type)) (U nil (t/Seqable r/Type))
       (U nil r/Type)
       (U nil r/AnyType) (U nil TCResult) -> (U nil true false cr/SubstMap)]))
(defn infer-vararg 
  ([X Y S T T-var R] (infer-vararg X Y S T T-var R nil))
  ([X Y S T T-var R expected]
   {:pre [(every? (u/hash-c? symbol? r/Bounds?) [X Y])
          (every? r/Type? S)
          (every? r/Type? T)
          ((some-fn nil? r/Type?) T-var)
          (r/AnyType? R)
          ((some-fn nil? r/AnyType?) expected)]
    :post [(or (nil? %)
               (cr/substitution-c? %))]}
   ;(prn "infer-vararg" "X:" X)
   (let [new-T (if T-var
                 ;Pad out T
                 (concat T (repeat (- (count S) (count T)) T-var))
                 T)]
     ;    (prn "S" (map unparse-type S))
     ;    (prn "new-T" (map unparse-type new-T))
     ;    (prn "R" (unparse-type R))
     ;    (prn "expected" (class expected) (when expected (unparse-type expected)))
     (and (>= (count S) (count T))
          (infer X Y S new-T R expected)))))

;; X : variables to infer mapped to their bounds
;; Y : indices to infer mapped to their bounds
;; S : actual argument types
;; T : formal argument types
;; R : result type
;; expected : #f or the expected type
;; returns a substitution
;; if R is nil, we don't care about the substituion
;; just return a boolean result
(t/ann infer
  (Fn [ConstrainVars ConstrainVars 
       (U nil (t/Seqable r/Type)) (U nil (t/Seqable r/Type))
       (U nil r/AnyType) -> (U nil true cr/SubstMap)]
      [ConstrainVars ConstrainVars 
       (U nil (t/Seqable r/Type)) (U nil (t/Seqable r/Type))
       (U nil r/AnyType) (U nil TCResult) -> (U nil true cr/SubstMap)]))
(defn infer 
  ([X Y S T R] (infer X Y S T R nil))
  ([X Y S T R expected]
   {:pre [(every? (u/hash-c? symbol? r/Bounds?) [X Y])
          (every? r/Type? S)
          (every? r/Type? T)
          (r/AnyType? R)
          ((some-fn nil? r/AnyType?) expected)]
    :post [(or (nil? %)
               (true? %)
               (cr/substitution-c? %))]}
   ;  (prn "infer" )
   ;  (prn "X:" X) 
   ;  (prn "Y:" Y) 
   ;  (prn "S:" (map prs/unparse-type S))
   ;  (prn "T:" (map prs/unparse-type T))
   ;  (when R
   ;    (prn "R:" (class R) (prs/unparse-type R)))
   ;  (when expected
   ;    (prn "expected:" (class expected) (prs/unparse-type expected)))
   (let [expected-cset (if expected
                         (cs-gen #{} X Y R expected)
                         (cr/empty-cset {} {}))
         ;_ (prn "expected cset" expected-cset)
         cs (cs-gen-list #{} X Y S T :expected-cset expected-cset)
         cs* (cset-meet cs expected-cset)]
     ;(prn "final cs" cs*)
     (if R
       (subst-gen cs* (set (keys Y)) R)
       true))))

(comment
         (let [x (gensym)]
           (infer {x r/no-bounds} {} 
                  [(c/RClass-of clojure.lang.IPersistentCollection [(c/RClass-of Number)])]
                  [(c/RClass-of clojure.lang.Seqable [(r/make-F x)])]
                  r/-any))
  (map prs/unparse-type (c/RClass-supers* (c/RClass-of clojure.lang.IPersistentCollection [(c/RClass-of Number)])))
  )
