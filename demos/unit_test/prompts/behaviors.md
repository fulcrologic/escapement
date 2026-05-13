You are an expert at analyzing Clojure functions to identify every testable behavior.
You work with fulcro-spec and guardrails. Your goal is COMPLETE behavioral coverage.

## Target

- **File**: `{{FILE}}`
- **Function**: `{{FUNCTION}}`

## Philosophy

A "behavior" is any decision point in your code that causes different outcomes. The goal is to test *every behavior*, not necessarily every line. You must be exhaustive. Missing a behavior means missing a test case, which means a potential bug goes undetected.

## What Counts as a Behavior

A function has one behavior for each distinct outcome path. Enumerate all of these:

1. **Conditional branches**: `if`, `when`, `cond`, `case`, `when-let`, `if-let`, `if-some`
   - Each branch of a `cond` is a separate behavior
   - The `else` / `:else` branch is a behavior
   - `when` has two behaviors: the condition-is-true path and the condition-is-false path (does nothing / returns nil)

2. **Boolean operators**: `and`, `or`, `not` (when they affect control flow or outcomes)
   - `(and a b)` that guards an action: two behaviors (a is false → nil, a and b are true → action)
   - Short-circuit evaluation creates distinct behaviors

3. **Exception handling**: `try`/`catch`/`finally`, `throw`, `ex-info`
   - Normal execution path (no exception)
   - Each caught exception type
   - `finally` block behaviors (if they affect state)

4. **Collection operations**: `filter`, `remove`, `map` predicates that vary by input
   - "What happens when the predicate matches nothing?"
   - "What happens when it matches everything?"
   - "What happens with a single element?"

5. **Nil handling**: any code that branches on nil vs present values
   - Passing nil where a map is expected
   - Missing keys in maps
   - Optional parameters that default

6. **Edge cases**:
   - Empty collections (empty vector, empty map, empty string)
   - Zero and negative numbers
   - Boundary values (exactly at a threshold, one above, one below)
   - Very large inputs (if relevant)

7. **Error validation**: input validation that returns errors or throws
   - Each validation rule is a behavior
   - The "all validations pass" path is a behavior
   - Validation order matters (first error wins) — each first-error case is a behavior

## How to Identify Behaviors — Worked Example

Given this function:

```clojure
(>defn calculate-discount
  [user amount]
  [(? :discount/user) :number => :number]
  (cond
    (nil? user) 0                                    ;; BEHAVIOR 1
    (< amount 100) 0                                 ;; BEHAVIOR 2
    (:premium? user) (* amount 0.20)                 ;; BEHAVIOR 3
    (>= (:loyalty-years user 0) 5) (* amount 0.15)   ;; BEHAVIOR 4
    :else (* amount 0.10)))                          ;; BEHAVIOR 5
```

This function has **5 behaviors**. Each `cond` branch is one behavior. The edge cases add more:
- Behavior 2 has a boundary: what about exactly 100? (covered by behavior 3+)
- Behavior 4 has a boundary: exactly 5 years vs 4 years
- What if `user` exists but has no `:loyalty-years` key? (default 0, falls to behavior 5)

## Your Task

1. Read the source file at `{{FILE}}`
2. Locate the function `{{FUNCTION}}`
3. Read the function body carefully, line by line
4. Read any functions it calls that are defined in the same file (you need to understand what they do to classify dependencies)
5. Read any guardrails schemas (`>def` declarations) that define the input/output types

Then enumerate EVERY behavior. For each behavior provide:

### Behavior N: [concise description]
- **Condition**: What input/condition triggers this behavior
- **Expected outcome**: What the function returns or does (be specific about the exact value or shape)
- **Edge cases**: Boundary values, nil, empty, etc. that are relevant to THIS behavior
- **Dependencies**: Does this behavior depend on a side-effect? Is it pure?
- **Mockable deps**: Does it call any `>defn` functions? (these can be mocked with `when-mocking!`/`provided!`)
- **Non-mockable deps**: Does it call Java interop, static methods, protocol methods? (these need wrappers)

## Dependency Classification

As you analyze behaviors, also classify every function that `{{FUNCTION}}` calls:

| Category | Examples | Can be mocked? |
|----------|----------|----------------|
| **Pure function** | `calculate-total`, `format-name` | No — call directly in tests |
| **`>defn` with side effects** | `db-query`, `send-email!` | Yes — use `provided!` or `when-mocking!` |
| **Java interop / static methods** | `Thread/sleep`, `LocalDate/now`, `System/getenv` | No — must wrap in `>defn` first |
| **Protocol methods** | `(-save-item store id data)` | No — must create `>defn` public wrapper |
| **Macro constructs** | `defresolver`, `defmutation` | No — must extract to `*-impl` function |
| **Fulcro mutations** | `defmutation` action body | No — must extract to `*` suffix helper |

Common things that need wrapping (look for these patterns):
- `Thread/sleep` → wrap as `(>defn sleep-ms [ms] [:int => :nil] (Thread/sleep ms))`
- `java.time.LocalDate/now` → wrap as `(>defn now-local-date [] [=> :java.time.LocalDate] ...)`
- `System/getProperty` → wrap if used for environment-dependent logic
- `java.util.Date.` constructor → wrap as `(>defn now [] [=> :java.time.Instant] ...)`

Note: Check if the project already has wrappers! Common namespaces:
- `com.fulcrologic.rad.type-support.date-time` — has `now`, `now-ms`, time-zone helpers
- `cljc.java-time.local-date` and related — Java Time wrappers
- Project-specific utility namespaces

## Critical Rules

- Cover EVERY branch, not just the happy path
- Include nil/empty/boundary cases as separate behaviors
- Identify behaviors that depend on side effects vs pure logic
- Be exhaustive: a function with 5 `cond` branches has at least 5 behaviors, plus edge cases
- Don't just list "it works" — describe exactly WHAT it returns for WHAT input
- If a function calls other functions to do its work, trace through the logic — the composed behavior is what matters
- Pay attention to default values: `(get m :k default)` and `(:key m default)` create nil-safe behaviors
- Watch for implicit nil punning: `(when x ...)` returns nil when x is falsy

## Output Format

Write a markdown file with this structure:

```markdown
# Behavioral Analysis: {{FUNCTION}}

## Function Summary
[Brief description of what the function does — one or two sentences]

## Function Signature
[The >defn signature if present, or inferred types]
[Include the full >defn line if available]

## Source Context
[What namespace is this in? What does the namespace do overall?]
[What functions does this one call? What calls this one?]

## Dependencies

### `dependency-fn-1`
- **Type**: PURE / MOCKABLE (>defn) / NEEDS WRAPPER / NEEDS IMPL EXTRACTION
- **Why**: [explanation]
- **Current mockability**: [can we mock it today or do we need to create something first?]

### `dependency-fn-2`
...

## Behaviors

### Behavior 1: [description of what happens]
- **Condition**: [exact condition that triggers this]
- **Expected outcome**: [specific return value or effect]
- **Edge cases**: [boundary values, nil, etc.]
- **Side effects**: none / [list them]
- **Mocking needed**: none / [what to mock]

### Behavior 2: [description]
...

[Continue for EVERY behavior]

## Edge Cases Summary

| Edge case | Relates to behavior | Expected result |
|-----------|-------------------|-----------------|
| nil user | Behavior 1 | returns 0 |
| empty collection | Behavior 3 | returns empty vector |
| boundary value exactly 100 | Behavior 2/3 boundary | behavior 3 applies (100 is not < 100) |

## Mocking Requirements

### Functions that can be mocked today
[List of >defn functions that can be mocked with provided!/when-mocking!]

### Functions that need wrapping before testing
[List of Java interop / static methods / protocol methods that need >defn wrappers]

### Functions that need impl extraction before testing
[List of resolvers/mutations that need *-impl or * suffix extraction]
```
