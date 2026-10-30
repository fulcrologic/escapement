# §runtime/metrics — What a run used

Every run reports what it cost in tokens, money and time, so a host can show and compare runs and
versions.

## §runtime.metrics/per-run — Tokens, cost and latency per call and per run

Each AI call in a run (a conversation turn, or a one-shot `ask` made from a chart) leaves a
transcript row carrying its model, provider, input and output tokens (with cache read and cache
write tokens where the provider reports them), its latency, and its cost in US dollars computed
from the model catalog's pricing or a host's price table, with the basis it used. Where the catalog
has no cache prices, cache tokens are priced from the input price and the basis says it is an
estimate. Where there is no price for that model the cost is reported as unknown, never as zero. A
call answered from the replay cache is marked as such. The run's result (`:metrics`), and a
`:runner/metrics` transcript row, also give the totals for the run: AI calls, tokens, cost, time
spent waiting on AI, wall-clock duration and events processed, broken down by model and by AI
step. When any call's price is unknown the run's total cost is unknown too, with the priced part
and the unpriced models given beside it.
