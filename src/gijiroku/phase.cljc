(ns gijiroku.phase
  "Phase 0→3 staged rollout, gating only the ASSESS ops (draft/distribute).
  Ingesting meeting-record ground facts (register a meeting, log a status
  change, record consent, fetch a recording asset-ref, ingest a transcript)
  is always on — that is gijiroku's observe charter. The phase only decides
  how much autonomy the *drafting* decision has, and can only add caution.

    0 observe-only  — record meetings/consent/recordings/transcripts; emit NO
                      minutes drafts yet (shadow ingest).
    1 assisted      — drafting allowed, but always human sign-off to commit.
    2 assisted-draft— draft may auto-commit when clean+confident; distribute
                      stays human.
    3 supervised    — draft auto-commits when clean+confident; distribute is
                      high-stakes and ALWAYS routes to a human (charter — never
                      enters :auto at any phase, like node-admission in
                      kekkai / publish in ai-gftd-newscaster).")

(def record-ops #{:meeting/register :meeting/status :consent/record
                  :recording/fetch :transcript/ingest})
(def assess-ops #{:minutes/draft :minutes/distribute})

(def phases
  {0 {:label "observe-only"   :assess #{}        :auto #{}}
   1 {:label "assisted"       :assess assess-ops :auto #{}}
   2 {:label "assisted-draft" :assess assess-ops :auto #{:minutes/draft}}
   3 {:label "supervised"     :assess assess-ops :auto #{:minutes/draft}}})

(def default-phase 3)

(defn record-op? [op] (contains? record-ops op))

(defn gate
  "Adjust an assess op's governor disposition for the rollout phase.
  Returns {:disposition kw :reason kw|nil}. `:minutes/distribute` is never in
  :auto, so it always escalates to a human — the governor also flags it
  high-stakes independently."
  [phase {:keys [op]} disposition]
  (let [{:keys [assess auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold disposition)        {:disposition :hold :reason nil}
      (not (contains? assess op))  {:disposition :hold :reason :phase-disabled}
      (and (= :commit disposition)
           (not (contains? auto op))) {:disposition :escalate :reason :phase-approval}
      :else                        {:disposition disposition :reason nil})))

(defn verdict->disposition [v]
  (cond (:hard? v) :hold (:escalate? v) :escalate :else :commit))
