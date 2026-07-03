# gijiroku

議事録 — a **Zoom / Google Meet / Microsoft Teams meeting-record actor**: it
accesses each platform's official recording + transcript API, drafts minutes
(summary / decisions / action items), and keeps an append-only audit ledger of
every consent, draft, and distribution — but it **never distributes anything
itself**. It is a sealed-intelligence ⊣ independent-governor StateGraph, built
on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj) runtime —
the same pattern as
[`kekkai`](../kekkai) (coord-LLM ⊣ TailnetGovernor),
[`robotaxi-actor`](../robotaxi-actor) and
[`ai-gftd-itonami`](../../gftdcojp/ai-gftd-itonami) (ops-LLM ⊣ CertGovernor).
Here it is **scribe-LLM ⊣ PrivacyGovernor**.

> Charter: **(G1)** access → draft-minutes only, no distribution actuation —
> the actor proposes minutes, a human-approved Distributor port sends them;
> **(G2)** distributing minutes is **always a human call** (high-stakes,
> whatever the phase); **(G3)** consent is a ground fact, never inferred —
> silence ≠ consent, commit is blocked without a recorded `:consent/record`;
> **(G4)** raw audio/video never enters the store or git — only an
> `:asset-ref` (object storage key / platform URL) is recorded.

Common infrastructure in `kotoba-lang` (language-substrate, consumed by all
orgs) — the primary consumers today are
[`cloud-itonami`](https://github.com/gftdcojp/cloud-itonami) and
[`cloud-manimani`](https://github.com/gftdcojp/cloud-manimani).

## The core contract

```
Zoom / Google Meet / Teams (Platform port)
        │  fetch-recording / fetch-transcript / verify-webhook
        ▼
meeting facts (meeting/consent/recording/transcript)
        │  ingest = durable ground facts (observe; always on)
        ▼
   ┌───────────┐  proposal: summary/    ┌──────────────────┐
   │ scribe-LLM │  decisions/action-    │ PrivacyGovernor   │  (independent system)
   │ (sealed)   │  items/cites/redact  │  consent · tenant  │
   └───────────┘ ─────────────────────▶│  · redaction · no- └────────┬─────────┘
                            commit ◀───────actuation────────┼──▶ hold (no consent /
                                │                            │      unredacted sensitive
                          minutes/draft                 escalate    cite / cross-tenant
                          auto-commits when                │        recipient / bad
                          clean + confident            request-approval  effect;
                          (phase 2/3)                  (ALWAYS human    human cannot
                                                        for distribute)  override)
```

## Files

| | |
|---|---|
| `src/gijiroku/model.cljc` | canonical data model — meeting/consent/recording/transcript/minutes |
| `src/gijiroku/platform.cljc` | `MeetingPlatform` port + `mock-platform` (default) |
| `src/gijiroku/zoom.clj` | Zoom Cloud Recording API client (S2S OAuth) + webhook HMAC verify |
| `src/gijiroku/google_meet.clj` | Google Workspace Meet REST API v2 client |
| `src/gijiroku/teams.clj` | Microsoft Graph (onlineMeetings recordings/transcripts) client |
| `src/gijiroku/consent.cljc` | pure consent/tenant/redaction evaluation (no I/O) |
| `src/gijiroku/scribellm.cljc` | scribe-LLM — sealed intelligence, proposal only |
| `src/gijiroku/governor.cljc` | **PrivacyGovernor** — independent censor over proposals |
| `src/gijiroku/phase.cljc` | **Phase 0→3** — observe-only → assisted → supervised (distribute always human) |
| `src/gijiroku/store.cljc` | `Store` protocol — `MemStore` ‖ `DatomicStore` |
| `src/gijiroku/operation.cljc` | **MeetingRecordActor** — langgraph-clj StateGraph; ingest vs assess flows |
| `src/gijiroku/cacao.clj` | agent-side **CACAO self-mint** (JVM Ed25519 + did:key + CBOR) |
| `src/gijiroku/kotoba.clj` | wire `DatomicStore` to a kotoba-server pod (kotobase.net XRPC) |
| `src/gijiroku/sim.cljc` | demo driver |
| `test/gijiroku/*_test.clj` | privacy contract · store parity (Mem≡Datomic) · platform (mock + VTT/HMAC) · CACAO |

## Recording acquisition: official APIs, not a meeting bot

Two approaches were weighed (ADR-2607031100): pulling recordings/transcripts
from each platform's **official cloud API** after the host enables native
recording, vs. a **headless-browser bot** that joins as a participant and
records/transcribes itself. This actor takes the official-API path — more
ToS-compliant, far less implementation surface, and it does not depend on any
one platform's UI staying stable. `MeetingPlatform` is a protocol precisely so
a bot-join implementation (e.g. built on `kotoba-lang/playwright` or
`browser-agent-clj`) could be added later without touching the actor core —
that is charter-adjacent, not charter, and is not implemented here.

## PrivacyGovernor — independent censor

The scribe-LLM has no notion of consent law, tenant boundaries, or the
no-actuation charter, so a **separate system** evaluates every proposal:

```clojure
(governor/check request proposal store)
;; => {:ok? bool :violations [..] :confidence c :hard? bool :escalate? bool :high-stakes? bool}
```

HARD (unoverridable, even by a human sign-off):

1. **consent-required** — `:consent/record` must exist, recording announced,
   legal basis on file. No record at all is NOT treated as consent.
2. **protected-content redaction** — every cited segment tagged `:sensitive`
   (health/legal/financial…) must be covered by a declared redaction.
3. **tenant-isolation** (`:minutes/distribute`) — every recipient must be a
   participant of the meeting; no cross-tenant/cross-meeting leak.
4. **no-actuation** — a proposal's `:effect` must be `:minutes` (a data
   record); the actor never sends/posts/attaches anything itself.

SOFT: confidence floor → escalate. `:minutes/distribute` is **always
high-stakes** — a human signs off on every distribution, clean or not.

## Phase 0→3

`gijiroku.phase`: 0 observe-only (ingest facts, no drafting) → 1 assisted
(draft allowed, distribute always human) → 2 assisted-draft (clean drafts
auto-commit) → 3 supervised (same, **distribute never enters `:auto` at any
phase** — the charter rule, not a phase knob).

## Consuming from cloud-itonami / cloud-manimani

```clojure
;; deps.edn
io.github.kotoba-lang/gijiroku {:local/root "../../kotoba-lang/gijiroku"}
```

```clojure
(require '[gijiroku.store :as store] '[gijiroku.operation :as op])
(def st (store/seed-db))                 ; or store/datomic-store for a real backend
(def actor (op/build st))
(g/run* actor {:request {:op :minutes/draft :meeting-id "m-123"}
              :context {:phase 3}} {:thread-id "m-123"})
```

Or read/write the actor's own `kotobase.net` graph over XRPC without a
local checkout:

```clojure
(require '[gijiroku.kotoba :as k] '[gijiroku.cacao :as cacao])
(def me    (cacao/load-or-create-identity! ".gijiroku/identity.edn"))
(def store (k/kotoba-store {:url "https://kotobase.net" :identity me
                            :json-write json/write-str
                            :json-read #(json/read-str % :key-fn keyword)}))
```

Product-specific UI, notifications, and calendar integration are each app's
own responsibility — gijiroku provides the common "access · record ·
transcribe · draft minutes" core only.

## 本番バックエンド（injection）

`DatomicStore` は `langchain.db` の `:db-api` マップ越しにのみ喋る。
`langchain.db/api`（in-process）と `langchain.kotoba-db/kotoba-api`
（kotoba-server XRPC）は同じマップを実装するので、同じ record が backend を
選ばず動く（`store_contract_test` で保証）。同様に Platform port も
`mock-platform` と実クライアント（Zoom/Meet/Teams）が同一契約に従う。

不正・破損 LLM 応答は confidence 0 / noop に落ち、**PrivacyGovernor が必ず
hold/escalate** する（LLM の幻覚や取りこぼしが「配布」に直結する経路は構造的に
無い）。

## Status

設計実装まで完了。runnable（`clojure -M:dev:run`）+ 契約テスト（privacy contract
· store parity · platform mock/VTT/HMAC · CACAO offline）、lint clean 想定。
Zoom/Google Meet/Teams の実クライアントは正しい API 形状で実装済みだが、各社の
OAuth app 登録・admin consent が前提のため **live 結合は未検証**（kekkai の
kotobase.net 522 と同種の「オフライン契約は保証、live は creds 到着後」の
既知状況）。

残り: 各社 OAuth app 登録後の live 結合、実 LLM（一般 API key）、
kotobase.net origin 復帰時の live 結合、cloud-itonami/cloud-manimani 側の
実際の consumer 配線（deps.edn 追加 + UI）、Distributor port の実装
（メール/Slack 等、承認後のみ呼ばれる）。
