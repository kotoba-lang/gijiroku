(ns gijiroku.governor
  "PrivacyGovernor — the independent layer that earns the scribe-LLM the
  right to *propose* minutes. The LLM has no notion of consent law, tenant
  boundaries, or the no-actuation charter, so this MUST be a separate system
  (rules over the meeting/consent/transcript ground facts) able to *reject* a
  proposal and fall back to HOLD — the gijiroku analog of robotaxi's MRC /
  kekkai's tailnet hold / talent-actor's PolicyGovernor.

  The actor is **draft → commit minutes only**. It never sends an email, posts
  to a channel, or attaches a recording anywhere; distributing minutes is
  ALWAYS routed to a human (charter: draft-only, no actuation).

  HARD invariants:
    :minutes/draft
      1. consent-required — a :consent/record ground fact exists, recording
         was announced, and a legal basis is on file. Silence ≠ consent.
      2. protected-content — every cited segment tagged :sensitive is covered
         by a declared redaction (no un-redacted health/legal/financial quote
         in the summary).
      3. no-actuation — effect must be :minutes (a data record), never a send.
    :minutes/distribute
      1. consent-required (defense-in-depth — re-checked at distribute time).
      2. tenant-isolation — every recipient is a participant of the meeting
         (no cross-tenant/cross-meeting leak of minutes).
      3. no-actuation.
  SOFT:
    4. Confidence floor → escalate.
    5. :minutes/distribute is ALWAYS high-stakes — a human signs off on every
       distribution, clean or not (charter, not merely a soft gate)."
  (:require [gijiroku.consent :as consent]
            [gijiroku.store :as store]))

(def confidence-floor 0.6)

;; ───────────────────────── invariant checks ─────────────────────────

(defn- consent-violations [mtg cons]
  (cond
    (nil? mtg) [{:rule :no-meeting :detail "未登録の会議"}]
    (not (consent/consent-clean? cons))
    [{:rule :no-consent :detail "録音同意記録(:consent/record)が未取得または不十分"}]
    :else []))

(defn- redaction-violations [transcript proposal]
  (let [bad (consent/unredacted-sensitive-cites transcript (:cites proposal) (:redactions proposal))]
    (when (seq bad)
      [{:rule :unredacted-sensitive
        :detail (str "機微区分セグメントが redaction 無しで引用: " bad)}])))

(defn- tenant-violations [mtg recipients]
  (let [bad (consent/cross-tenant-recipients mtg recipients)]
    (when (seq bad)
      [{:rule :cross-tenant-recipient
        :detail (str "会議参加者に含まれない宛先への配布提案: " bad)}])))

(defn- actuation-violations [proposal]
  ;; draft→minutes-record only: the actor never sends/posts/attaches.
  (when (not= :minutes (:effect proposal))
    [{:rule :no-actuation
      :detail (str "actor は minutes record のみ書く(配布は人間実行)。effect=" (:effect proposal))}]))

(defn check
  "Censors a scribe-LLM proposal for a gijiroku op. Returns
   {:ok? :violations :confidence :hard? :escalate? :high-stakes?}.

   Hard violations force HOLD and cannot be overridden. `:minutes/distribute`
   is always high-stakes → human sign-off even when clean."
  [request proposal st]
  (let [mtg   (store/meeting st (:meeting-id request))
        cons  (store/consent-of st (:meeting-id request))
        trans (store/transcript-of st (:meeting-id request))
        hard  (case (:op request)
                :minutes/draft
                (into [] (concat (consent-violations mtg cons)
                                 (redaction-violations trans proposal)
                                 (actuation-violations proposal)))
                :minutes/distribute
                (into [] (concat (consent-violations mtg cons)
                                 (tenant-violations mtg (:recipients request))
                                 (actuation-violations proposal)))
                ;; an unrecognized :op is itself a hard violation (fail-closed:
                ;; a not-yet-wired op must never silently pass as clean) --
                ;; same invariant denrei/koyomi/tayori/kekkai/shoko/teian/
                ;; ichiran's governors already enforce for their own ops.
                [{:rule :unrecognized-op :detail (str "未対応op: " (:op request))}])
        conf    (:confidence proposal 0.0)
        low?    (< conf confidence-floor)
        stakes? (= :minutes/distribute (:op request))
        hard?   (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact [request verdict]
  {:t :privacy-hold :op (:op request) :meeting-id (:meeting-id request)
   :disposition :hold :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict) :confidence (:confidence verdict)})
