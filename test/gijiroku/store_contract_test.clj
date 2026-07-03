(ns gijiroku.store-contract-test
  "Store contract against both backends — proving MemStore ≡ DatomicStore makes
  'swap the SSoT for Datomic / kotoba-server' a config change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [gijiroku.store :as store]))

(defn- backends [] [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "週次すり合わせ" (:title (store/meeting s "m-weekly"))))
      (is (= ["m-perf" "m-weekly"] (mapv :id (store/all-meetings s))))
      (is (true? (:recording-announced? (store/consent-of s "m-weekly"))))
      (is (nil? (store/consent-of s "m-perf")) "no consent recorded yet")
      (is (= "b2://gijiroku/m-weekly.mp4" (:asset-ref (store/recording-of s "m-weekly")))
          "recording is an asset-ref, never raw bytes")
      (is (= 2 (count (:segments (store/transcript-of s "m-weekly")))))
      (is (= #{:health} (:sensitive (second (:segments (store/transcript-of s "m-perf"))))))
      (is (nil? (store/minutes-of s "m-weekly")) "no minutes committed yet")
      (is (nil? (store/meeting s "m-missing"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (store/record-datom! s {:kind :consent :id "m-perf"
                              :value {:meeting-id "m-perf" :recording-announced? true
                                      :legal-basis "contract"}})
      (is (true? (:recording-announced? (store/consent-of s "m-perf"))))
      (store/record-datom! s {:kind :minutes :id "m-weekly"
                              :value {:summary "s" :decisions [] :action-items []
                                      :cites ["s1"] :redactions [] :by :auto}})
      (is (= "s" (:summary (store/minutes-of s "m-weekly"))))
      (store/append-ledger! s {:op :a :disposition :record})
      (store/append-ledger! s {:op :b :disposition :commit})
      (is (= [:record :commit] (mapv :disposition (store/ledger s)))))))

(deftest datomic-empty-store-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/meeting s "nope")))
    (is (= [] (store/all-meetings s)))
    (store/record-datom! s {:kind :meeting :id "x"
                            :value {:id "x" :platform :mock :title "t" :tenant "cloud-itonami"
                                    :participants [] :status :scheduled}})
    (is (= "t" (:title (store/meeting s "x"))))))
