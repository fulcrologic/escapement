# LLM Providers

Notes on the OpenAI/OpenCode-related LLM providers in this project.

---

# OpenAI Codex (ChatGPT Subscription) Provider

## Overview
OpenAI access in this project is **subscription-based via Codex**, not a metered API
key. The provider lives at `src/app/llm/codex.clj` and talks to the Codex Responses
API at `https://chatgpt.com/backend-api/codex/responses`.

- Zero-cost: included in ChatGPT Plus/Pro subscription.
- Auth: self-managed OAuth bearer tokens (device-code flow), not an API key.
- No plain OpenAI API-key provider exists — subscription only.

## Models
- `gpt-5.4` — general model (default)
- `gpt-5.2-codex` — code-optimized
- `gpt-5.3-codex` — code-optimized

Codex models support reasoning effort levels: `low`, `medium`, `high`, `xhigh`
(default `medium`).

## Authentication / Sub Token
Tokens are stored in `.env` and auto-refreshed when expired:

- `CODEX_ACCESS_TOKEN`
- `CODEX_REFRESH_TOKEN`
- `CODEX_TOKEN_EXPIRES`
- `CODEX_ACCOUNT_ID`

OAuth handled by `src/app/llm/codex_oauth.clj` (device authorization grant, RFC 8628)
and `src/app/llm/codex_store.clj` (token storage/refresh; reads `.env` directly as a
dev fallback). The access token is a JWT whose `chatgpt_account_id` claim is sent as
the `ChatGPT-Account-Id` header.

First-time auth:
```clojure
(require '[app.llm.codex-store :as cs])
(cs/start-device-auth!)   ;; get user code + URL
(cs/poll-device-auth!)    ;; poll until authorized
```

## API Notes
- Responses API format: `input` (not `messages`), `instructions` (system prompt),
  `reasoning {:effort ... :summary "auto"}`.
- Always `stream=true`, `store=false`.
- SSE text deltas: `response.output_text.delta`; reasoning:
  `response.reasoning_summary_text.delta`; usage from `response.completed`.
- Auto-retries once on HTTP 401 by force-refreshing the token.

## Verification (2026-05-26)
Ran a small prompt and it succeeded:

```clojure
(require '[app.llm.codex :as codex] '[app.llm.protocol :as proto])
(let [p (codex/create-provider {:effort "low"})]
  (proto/chat p [{:role :user :content "Reply with exactly: codex ok"}]))
```

Result:
```clojure
{:ok "codex ok"
 :finish-reason "stop"
 :usage {:input-tokens 26 :output-tokens 17 :reasoning-tokens 8}}
```

Status: subscription token valid, provider functional (model `gpt-5.4`).

---

# OpenCode Go Provider

## Overview
OpenCode Go is a provider that proxies to OpenCode's "zen/go" gateway, exposing
multiple third-party models behind a single API key. It lives at
`src/app/llm/opencode_go.clj`.

Its distinguishing feature is **dual API-format support**: depending on the model,
it speaks either the OpenAI Chat Completions format or the Anthropic Messages format,
dispatched internally via the `api-format` field.

## Endpoints
- OpenAI format: `https://opencode.ai/zen/go/v1/chat/completions`
- Anthropic format: `https://opencode.ai/zen/go/v1/messages`

## Models
Format is decided by the hardcoded sets in `opencode_go.clj`
(`openai-models` / `anthropic-models`); **anything not listed defaults to
OpenAI format**. Vision support is per-model via the catalog
(`catalog/model-supports-vision? :opencode-go ...`). Defaults:
`temperature 0.7`, `max_tokens 8192`, default model `kimi-k2.5`.

Sample config — the models tested in this session (all live in the
`opencode-go` catalog, see `models-api.json`):

| Requested      | Catalog ID         | API format | In format set?        |
|----------------|--------------------|------------|-----------------------|
| kimi 2.5       | `kimi-k2.5`        | OpenAI     | yes (`openai-models`) |
| kimi 2.6       | `kimi-k2.6`        | OpenAI     | no — falls to default |
| deepseek v4 pro| `deepseek-v4-pro`  | OpenAI     | no — falls to default |
| deepseek flash | `deepseek-v4-flash`| OpenAI     | no — falls to default |
| minimax 2.7    | `minimax-m2.7`     | Anthropic  | yes (`anthropic-models`) |
| qwen 3.6 plus  | `qwen3.6-plus`     | OpenAI     | no — falls to default |
| mimo 2.5       | `mimo-v2.5`        | OpenAI     | no — falls to default |

Other catalog IDs also present: `glm-5`, `glm-5.1`, `mimo-v2-pro`,
`mimo-v2-omni`, `mimo-v2.5-pro`, `qwen3.5-plus`, `minimax-m2.5`.

Sample EDN (mirrors `config.edn` `:llm/:providers` shape):

```clojure
:opencode-go {:api-key #env OPENCODE_GO_API_KEY
              :model   "kimi-k2.6"}   ;; swap :model for any ID above
```

> **Caveat — reasoning models:** `minimax-m2.7` returns a `thinking` block at
> `content[0]` and the answer at `content[1]`. The current
> `extract-response-anthropic` hardcodes `[:content 0 :text]`, so it reads the
> thinking block (no `:text`) and yields `{:error "No content in response"}`
> even though the call succeeds. Fix: scan `:content` for the first
> `{:type "text"}` block instead of assuming index 0.

## Authentication
Single API key (not OAuth), stored in `.env` as `OPENCODE_GO_API_KEY`.
Live value (transient test file — do not commit):

```
OPENCODE_GO_API_KEY=sk-scQU2zJ2Sn6QKjmpnItSW0czGUbGkzi6AHYz58t3egdBbdj77ENOSO4HwP7MN1zU
```

Header format depends on the model's API format:
- OpenAI: `Authorization: Bearer <key>`
- Anthropic: `x-api-key: <key>` + `anthropic-version: 2023-06-01`

Wired in `src/app/llm/core.clj` via the `:opencode-go` `create-provider` defmethod.

## Format Differences (handled automatically)
| Aspect            | OpenAI format                          | Anthropic format                              |
|-------------------|----------------------------------------|-----------------------------------------------|
| System prompt     | inline message                         | extracted to top-level `:system` param        |
| Response text     | `[:choices 0 :message :content]`       | `[:content 0 :text]`                           |
| Stream delta      | `[:choices 0 :delta :content]`         | `[:delta :text]`                               |
| Finish reason     | `[:choices 0 :finish_reason]`          | `:stop_reason` / `[:message :stop_reason]`     |
| Vision content    | `image_url` with `data:` URL           | `image` block w/ base64 `source`               |

Streaming is supported for all models (`supports-streaming?` → true).

## Verification (2026-05-26)
Ran the prompt `"Reply with exactly: hello world"` through every sample model
via the JVM `clojure` CLI (bb.edn lacks the deps; this is a JVM project):

```clojure
(require '[app.llm.opencode-go :as og] '[app.llm.protocol :as proto])
(let [k (System/getenv "OPENCODE_GO_API_KEY")]
  (doseq [m ["kimi-k2.5" "kimi-k2.6" "deepseek-v4-pro" "deepseek-v4-flash"
             "minimax-m2.7" "qwen3.6-plus" "mimo-v2.5"]]
    (println m (proto/chat (og/create-provider k {:model m})
                           [{:role :user :content "Reply with exactly: hello world"}]))))
```

Results:

| Model              | Result                                  |
|--------------------|-----------------------------------------|
| `kimi-k2.5`        | `{:ok "hello world"}`                   |
| `kimi-k2.6`        | `{:ok "hello world"}`                   |
| `deepseek-v4-pro`  | `{:ok "hello world"}`                   |
| `deepseek-v4-flash`| `{:ok "hello world"}`                   |
| `minimax-m2.7`     | `{:error "No content in response"}` *   |
| `qwen3.6-plus`     | `{:ok "hello world"}`                   |
| `mimo-v2.5`        | `{:ok "hello world"}`                   |

\* The API call **succeeds** and returns `"hello world"`; the error is the
extractor bug described under Models (thinking block at `content[0]`). Raw
payload confirmed `content[1] {:type "text" :text "hello world"}`.

Status: API key valid; all 7 models reachable and functional.
