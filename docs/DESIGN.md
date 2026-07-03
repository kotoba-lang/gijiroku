# gijiroku Design — scribe-LLM as a contained intelligence node

## 1. なぜ actor 層が要るのか

会議の録音・文字起こし・要約は LLM で加速できるが、LLM に**配布・開示の最終権限**を
持たせてはいけない:

| LLM が起こしうる失敗 | 会議記録での帰結 |
|---|---|
| 幻覚（発言していない内容を要約に含める） | 誤った意思決定・名誉毀損リスク |
| 機微情報（健康・法務・財務）を要約に引用 | 目的外利用・プライバシー侵害 |
| 同意の無い会議を録音・要約 | 法令違反（二者同意州法・GDPR 等） |
| 誤った宛先への議事録配布 | 情報漏洩・テナント越境 |

そこで設計問題は「LLM で議事録を書く」ことではなく、**「LLM を信頼境界の内側に
封じ込め、同意・最小開示・テナント分離・人間承認・監査の層をどう被せるか」**に
なる。以下すべてがここから導かれる。

**SaaS（Otter.ai / Fireflies / Gong 等）に払わない論拠**: これらの価値の実体は
プラットフォーム接続・要約生成・配布ワークフローであり、いずれも OSS スタック
（Datomic/EDN + langgraph-clj StateGraph + LLM）で代替できる。actor 化で上乗せ
されるのは、SaaS が原理的に渡さない**データ主権**（自分の Datomic が SSoT）と
**不変の監査台帳**、そして**同意/開示ゲートがコードで固定される**ことである。

## 2. アクター・トポロジ

```
MeetingRecordActor (root, 1 run = 1 op)
├── ingest（観測・常時 ON・LLM 無し）
│     :meeting/register :meeting/status :consent/record
│     :recording/fetch :transcript/ingest
└── assess（scribe-LLM 封じ込め）
      ├── scribe-LLM (sealed)     proposal only（scribellm.cljc）
      ├── PrivacyGovernor         独立検閲（governor.cljc）
      ├── Committer               SSoT/台帳への書き込み（store.cljc）
      └── Recorder                監査台帳（append-only）
```

## 3. SaaS 機能との対応

| SaaS（Otter.ai/Fireflies/Gong 型）の機能 | gijiroku での実体 |
|---|---|
| Zoom/Meet/Teams 連携 | `gijiroku.platform`（Zoom/Google Meet/Teams クライアント） |
| 自動録音・文字起こし | ingest（`:recording/fetch` `:transcript/ingest`） |
| AI 要約・アクションアイテム抽出 | `:minutes/draft`（scribe-LLM proposal） |
| 議事録の共有・配布 | `:minutes/distribute`（**常に人間承認**） |
| 同意管理（録音アナウンス等） | `:consent/record`（ground fact、LLM 非経由） |
| （SaaS には無い）監査台帳 | `store` append-only ledger ← **上乗せ価値** |
| （SaaS には無い）機微情報の構造的ゲート | PrivacyGovernor redaction 不変条件 |
| （SaaS には無い）テナント分離の構造的保証 | PrivacyGovernor tenant-isolation 不変条件 |

## 4. 注入される依存（すべて swap）

- **Platform**（`gijiroku.platform/MeetingPlatform`）: `mock-platform`（既定）/
  `gijiroku.zoom` ‖ `gijiroku.google-meet` ‖ `gijiroku.teams`（実クライアント）。
- **Store**（`gijiroku.store/Store`）: `MemStore`（既定）/ `DatomicStore`
  （`langchain.db` = Datomic-API 互換 EAV。`:db-api` で実 Datomic Local /
  kotoba-server pod に差し替え）。
- **Advisor**（`gijiroku.scribellm/Advisor`）: `mock-advisor`（既定）/
  `llm-advisor`（`langchain.model` の ChatModel）。応答破損時は confidence 0 の
  noop に落ち、LLM 不調が auto-commit にならない。
- **Distributor**: 承認後のみ呼ばれる送信 port（既定 nil = 何もしない）。
  実装は各消費アプリ（cloud-itonami/cloud-manimani）側のメール/Slack/カレンダー
  連携に委ねる。

## 5. 段階導入と bot 参加方式の位置づけ

Phase 0→3 は draft/distribute のみをゲートする（ingest は常時 ON）。
`:minutes/distribute` はどの phase でも `:auto` に入らない — kekkai の
`:node/admit`、newscaster の `:episode/publish` と同じ charter。

録音取得を「公式 API pull」から「bot 参加によるライブキャプチャ」へ拡張したい
場合は、`MeetingPlatform` protocol の新規実装（`kotoba-lang/playwright` や
`browser-agent-clj` を使った headless join）を追加するだけで済む設計にしてある
（ADR-2607031100 の bot 参加検討を参照）。現時点では未実装・charter 外。

## 6. デモ（`clojure -M:dev:run`）

`src/gijiroku/sim.cljc` が代表シナリオを actor に通す（ingest → 同意有りの
自動下書き → 同意無しの HARD HOLD → 機微引用の HARD HOLD → 常に人間承認の
配布 → テナント越境配布の HARD HOLD → phase 0 の抑制）。最後に監査台帳を表示。

## 7. テスト（`clojure -M:dev:test`）

`test/gijiroku/governor_contract_test.clj` がプライバシー契約を実行可能にする
（consent-required／redaction／tenant-isolation／no-actuation の各 HARD 不変条件、
distribute は常に人間承認、phase 0 の抑制）。`store_contract_test.clj` が
MemStore ≡ DatomicStore を、`platform_test.clj` が mock 契約 + VTT パーサ +
webhook HMAC 検証を、`cacao_test.clj` が CACAO 自己発行をオフライン検証する。
