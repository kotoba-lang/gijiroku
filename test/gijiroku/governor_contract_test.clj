(ns gijiroku.governor-contract-test
  "The privacy contract as executable tests — gijiroku's analog of robotaxi's
  safety_contract_test / kekkai's zero-trust contract. Invariant: the actor
  never commits minutes the PrivacyGovernor would reject, never auto-
  distributes, never actuates beyond a data record, and always records
  observations."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [gijiroku.store :as store]
            [gijiroku.scribellm :as scribellm]
            [gijiroku.operation :as op]
            [gijiroku.governor :as gov]))

(defn- fresh [] (let [s (store/seed-db)] [s (op/build s)]))
(defn- ctx [phase] {:phase phase})

(defn- run [actor tid req phase]
  (g/run* actor {:request req :context (ctx phase)} {:thread-id tid}))

(deftest ingest-always-records
  (testing "observe path records a ground fact regardless of phase"
    (let [[s actor] (fresh)
          res (run actor "i" {:op :meeting/register :meeting-id "m-adhoc"
                              :value {:id "m-adhoc" :platform :mock :external-id "m-adhoc"
                                      :title "臨時" :tenant "cloud-manimani" :participants []
                                      :status :scheduled}} 0)]
      (is (= :record (get-in res [:state :disposition])))
      (is (= "臨時" (:title (store/meeting s "m-adhoc")))))))

(deftest draft-auto-commits-when-clean
  (testing "consent on file + no sensitive segments → not high-stakes → auto"
    (let [[s actor] (fresh)
          res (run actor "d" {:op :minutes/draft :meeting-id "m-weekly"} 3)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (some? (:summary (store/minutes-of s "m-weekly")))))))

(deftest draft-without-consent-is-held-and-unoverridable
  (testing "m-perf has no :consent/record ground fact yet"
    (let [[s actor] (fresh)
          res (run actor "d" {:op :minutes/draft :meeting-id "m-perf"} 3)
          basis (-> (store/ledger s) last :basis)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-consent} basis))
      (is (nil? (store/minutes-of s "m-perf")) "never drafted without consent"))))

(deftest unredacted-sensitive-cite-is-held
  (testing "a careless advisor cites the health-tagged segment without redacting it"
    (let [[s _] (fresh)
          _ (store/record-datom! s {:kind :consent :id "m-perf"
                                    :value {:meeting-id "m-perf" :recording-announced? true
                                            :legal-basis "contract"}})
          careless (reify scribellm/Advisor
                     (-advise [_ transcript]
                       {:summary (apply str (map :text (:segments transcript)))
                        :decisions [] :action-items []
                        :cites (mapv :seg-id (:segments transcript))
                        :redactions [] :effect :minutes :confidence 0.95}))
          a2 (op/build s {:advisor careless})
          res (g/run* a2 {:request {:op :minutes/draft :meeting-id "m-perf"} :context (ctx 3)}
                      {:thread-id "cl"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unredacted-sensitive} (-> (store/ledger s) last :basis)))
      (is (nil? (store/minutes-of s "m-perf")) "no human sign-off overrides a hard violation"))))

(deftest no-actuation-invariant
  (testing "a proposal that tries to actuate beyond a data record is held"
    (let [[s _] (fresh)
          bad (reify scribellm/Advisor
                (-advise [_ _] {:summary "x" :decisions [] :action-items []
                                :cites [] :redactions [] :effect :send-email :confidence 0.9}))
          a2 (op/build s {:advisor bad})
          res (g/run* a2 {:request {:op :minutes/draft :meeting-id "m-weekly"} :context (ctx 3)}
                      {:thread-id "na"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-actuation} (-> (store/ledger s) last :basis))))))

(deftest phase0-disables-assessments
  (let [[s actor] (fresh)
        res (run actor "p0" {:op :minutes/draft :meeting-id "m-weekly"} 0)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) last :phase-reason)))))

(deftest distribute-always-requires-signoff
  (testing "distribute never auto-commits, even at phase 3 with clean recipients"
    (let [[_ actor] (fresh)
          r1 (run actor "x" {:op :minutes/distribute :meeting-id "m-weekly"
                             :recipients ["p-jun" "p-guest"]} 3)]
      (is (= :interrupted (:status r1)))
      (let [r2 (g/run* actor {:approval {:status :approved :by "jun"}}
                       {:thread-id "x" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))))))

(deftest distribute-to-non-participant-is-held-and-unoverridable
  (testing "a recipient outside the meeting roster is a tenant-isolation violation"
    (let [[s actor] (fresh)
          res (run actor "xb" {:op :minutes/distribute :meeting-id "m-weekly"
                               :recipients ["p-jun" "p-outsider"]} 3)]
      (is (= :hold (get-in res [:state :disposition])) "hard violation, no interrupt")
      (is (some #{:cross-tenant-recipient} (-> (store/ledger s) last :basis))))))

(deftest reject-signoff-holds
  (testing "a rejected distribution records a hold, not a send"
    (let [[_ actor] (fresh)
          _  (run actor "r" {:op :minutes/distribute :meeting-id "m-weekly" :recipients ["p-jun"]} 3)
          r2 (g/run* actor {:approval {:status :rejected :by "jun"}}
                     {:thread-id "r" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition]))))))

(deftest governor-check-fails-closed-on-an-unrecognized-op
  (testing "gov/check itself (not just the wrapping phase/gate) must reject an
            unrecognized/typo'd/not-yet-wired :op as a hard violation -- a
            confident, otherwise-clean proposal for a bogus op must never come
            back :ok? true, since gov/check is documented as the independent
            censor that decides commit/hold and any future direct caller
            (a new UI surface, a refactor, code outside operation.cljc) must
            not be able to slip an unhandled op past every zero-trust check --
            same invariant kekkai/denrei/koyomi/tayori/shoko/teian/ichiran's
            governors already enforce for their own ops"
    (let [[s _] (fresh)
          verdict (gov/check {:op :minutes/bogus :meeting-id "m-weekly"}
                              {:effect :minutes :confidence 0.99} s)]
      (is (false? (:ok? verdict)))
      (is (true? (:hard? verdict)))
      (is (some #{:unrecognized-op} (mapv :rule (:violations verdict)))))))
