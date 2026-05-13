You are reviewing a fulcro-spec test file for methodological correctness.
You are rigorous and specific. Every checklist item must be assessed against the actual code.

## Target

- **Source file**: `{{FILE}}`
- **Function**: `{{FUNCTION}}`
- **Test file**: `{{TEST_FILE}}`
- **Behavioral analysis**: `{{BEHAVIORS_FILE}}`

================================================================
REVIEW CHECKLIST
================================================================

For EACH item below, read the test file carefully and assess PASS, FAIL, or WARNING.
Include specific line numbers and quoted code for every finding.

----------------------------------------------------------------
SECTION A: STRUCTURAL CORRECTNESS
----------------------------------------------------------------

### A1. Namespace and requires
- [ ] Test namespace follows convention (source `foo.bar` → test `foo.bar-spec`)
- [ ] Requires `fulcro-spec.core` with at minimum: `specification`, `component`, `assertions`, `=>`
- [ ] Requires source namespace under test as `sut`
- [ ] All referenced symbols are properly required

### A2. Uses `specification` as top-level form
- [ ] Every test is wrapped in `(specification "name" ...)`
- [ ] Specification name matches or clearly relates to the function under test
- [ ] NOT using `describe`, `deftest`, `testing`, or any other test framework macros

### A3. Banned `behavior` macro is NOT used
- [ ] Scan the ENTIRE file. The string `behavior` must NOT appear as a macro call
- [ ] The string `(behavior` appearing anywhere is an automatic FAIL
- [ ] Only `assertions`, `component`, `specification` are allowed as test structure forms

### A4. Uses `component` correctly
- [ ] `component` is used ONLY when setup differs between groups
- [ ] If setup is identical, assertions are flat under `specification` — no unnecessary `component`
- [ ] Each `component` has a descriptive label that forms a readable sentence with the specification name

### A5. Multi-triple `assertions` blocks
- [ ] Assertions are grouped 10-15 per block using multi-triple syntax
- [ ] NOT using single-assertion blocks wrapped in `component` or other forms
- [ ] Each assertion triple is: `"label"` expression `=>` expected

----------------------------------------------------------------
SECTION B: COVERAGE
----------------------------------------------------------------

### B1. Every conditional branch has a test case
- [ ] Read the source function and list every `if`, `when`, `cond`, `case` branch
- [ ] For each branch, find a test that exercises it
- [ ] `cond` with 5 branches → at least 5 test cases

### B2. Edge cases covered
- [ ] nil input tested where applicable
- [ ] Empty collections tested where applicable
- [ ] Boundary values at threshold tested (e.g., exactly 100 when threshold is 100)
- [ ] Zero and negative numbers tested where applicable

### B3. Error/exception paths tested
- [ ] Exception throwing tested with `=throws=>`
- [ ] Error return values tested (if function returns errors instead of throwing)
- [ ] Validation failure paths tested

### B4. Every behavior from the analysis has a test
- [ ] Cross-reference every behavior in the behavioral analysis file
- [ ] Each behavior must have at least one assertion that would fail if that behavior changed
- [ ] No behaviors are missing from the test file

----------------------------------------------------------------
SECTION C: ASSERTION QUALITY
----------------------------------------------------------------

### C1. Positive assertion language
- [ ] Every assertion label describes what it DOES, not what it doesn't do
- [ ] No labels like "does not return nil", "doesn't fail", or "does not modify"
- [ ] Exception: conditional skips are OK when phrased as "skips X when Y"
- [ ] For showing a pure function preserves its input: label says "preserves the original value" (not "does not modify the input")
- [ ] For showing a function targets a specific area: label says "focuses only on the target area" (not "doesn't modify other fields")
- [ ] The assertion technique for targeted changes compares originals:
  ```clojure
  ;; GOOD — proves only the target area changed:
  "focuses only on the target area"
  (dissoc result :target-key) => (dissoc original :target-key)

  ;; Also prove what DID change:
  (:target-key result) => expected-new-value
  ```
- [ ] No lazy negative labels that can be trivially satisfied by a no-op implementation

### C2. Every assertion is falsifiable
- [ ] For each assertion: if the described behavior changed, would this assertion fail?
- [ ] No trivial assertions that always pass (e.g., `(count x) => 2` without verifying what the 2 items ARE)
- [ ] No assertions that only prove structural properties without proving content

Examples:
```clojure
;; BAD — proves count only:
"has two operations"
(count ops) => 2

;; GOOD — proves what they are:
"has the foo and bar operations"
(set ops) => #{foo-op bar-op}
```

### C3. Labels are specific and descriptive
- [ ] No vague labels like "works correctly" or "returns the right thing"
- [ ] Each label, combined with specification/component name, forms a complete sentence
- [ ] When a test fails, the label alone tells you WHAT failed

### C4. No missing assertion labels
- [ ] Every assertion triple has a string label before the expression
- [ ] No bare assertions without labels: `(f x) => y` without a preceding string

### C5. Label-Assertion alignment
- [ ] For EVERY assertion triple: read the label, then read the expression — do they match?
- [ ] The label must describe exactly what the expression proves, no more, no less
- [ ] FAIL if the label claims a specific behavior but the expression only proves a trivially true property
  ```clojure
  ;; FAIL — label claims specific validation, expression proves only count:
  "validates email format"
  (count (:errors result)) => 1

  ;; FAIL — label claims computation, expression proves only existence:
  "calculates total with tax"
  (contains? result :total) => true
  ```
- [ ] FAIL if the label describes a behavior the expression cannot detect
- [ ] Each label must be uniquely falsifiable: changing the described behavior MUST break the assertion

### C6. Tautological / impossible-to-fail assertions
- [ ] No assertions that compare an immutable value to itself or its obvious equivalent
- [ ] Apply the "no-op test": would this assertion still pass if the function body were `(fn [& _] input)`?
  - If yes, the assertion is tautological — it proves nothing about the function
- [ ] No assertions that test Clojure's language guarantees rather than the function's behavior:
  ```clojure
  ;; FAIL — tests Clojure's associativity guarantee, not the function:
  (associative? (my-function m)) => true
  (map? (my-function m)) => true   ;; when the function always returns a map
  ```
- [ ] No assertions that would pass for ANY return value of the correct type:
  ```clojure
  ;; FAIL — any non-nil value passes:
  (some? (calculate-total order)) => true
  ;; GOOD — proves the specific value:
  (calculate-total order) => 150.0
  ```
- [ ] No assertions showing an immutable data structure "wasn't changed" — this is a Clojure guarantee:
  ```clojure
  ;; FAIL — Clojure maps are always immutable, this can never fail:
  (= original (assoc original :k :v)) => false  ;; tautological

  ;; GOOD — prove the function preserves areas outside its concern:
  "preserves fields outside the target area"
  (dissoc result :affected-key) => (dissoc original :affected-key)
  ```

----------------------------------------------------------------
SECTION D: MOCKING
----------------------------------------------------------------

### D1. Only mocks `>defn` functions
- [ ] Every mocked function is verified to be a `>defn` function
- [ ] No attempts to mock protocol methods directly
- [ ] No attempts to mock Java interop / static methods
- [ ] No attempts to mock resolvers or mutations directly

### D2. Uses `provided!` with description strings
- [ ] `provided!` is used (preferred over `when-mocking!`) where applicable
- [ ] Each `provided!` has a descriptive string: `(provided! "description" ...)`
- [ ] Description explains what conditions are being forced

### D3. Does NOT mock pure functions
- [ ] No pure functions are mocked
- [ ] Pure helpers like `calculate-total`, `format-name` are called directly
- [ ] Only side effects, non-deterministic functions, and external deps are mocked

### D4. Mock return values are realistic
- [ ] Mock return values match the function's output schema
- [ ] No mock returning `nil` when the function should return a map
- [ ] No mock returning empty data when realistic data would exercise more code paths

### D5. Not over-mocking
- [ ] Count the mocks. More than 3-4 suggests the function is too coupled
- [ ] If over-mocking, add a WARNING comment suggesting the function needs refactoring
- [ ] No mocking trivial functions like getters

----------------------------------------------------------------
SECTION E: ANTI-PATTERNS
----------------------------------------------------------------

### E1. No testing of implementation details
- [ ] Tests verify observable outcomes, not HOW the function works
- [ ] No assertions on internal helper function call counts (unless that IS the behavior being tested)

### E2. No brittle whole-map assertions
- [ ] Not asserting on entire maps when only specific fields matter
- [ ] No `(result => entire-expected-map-with-20-fields)` style assertions

### E3. No assertions without labels
- [ ] No bare `(expression => expected)` without a preceding description string

### E4. Not missing `:covers` placeholder note
- [ ] The file should note that `:covers` will be added after verification
- [ ] OR: `:covers` metadata is already present (if this is a re-review after sealing)

### E5. No redundancy or dead bindings  *(common LLM-author smells)*
- [ ] No `let` bindings that are never referenced in the body
- [ ] No assertion that is **subsumed** by a sibling whole-collection equality
      immediately above or below it. Example: if line N asserts
      `(:children result) => [a b c]`, then a follow-on
      `(last (:children result)) => c` proves nothing new — drop it.
- [ ] No assertion that re-proves a field already covered by a `=>` against the
      whole map. Whole-map equality dominates per-field equality; keep one or
      the other, not both.

### E6. Falsifiable equality on non-empty-mapping fast path
- [ ] For functions with an "empty argument → identity" fast path (e.g.
      `(if (empty? m) data (do-walk m data))`), the identity claim must be
      proved with **`identical?`**, not `=`. A plain `=> data-structure`
      check is satisfied by *any* rebuild that returns an equal value, so it
      cannot distinguish the fast path from the walk path and is therefore
      tautological.
- [ ] Conversely, on the NON-empty mapping path, do NOT assert `identical?` —
      the walk legitimately rebuilds the structure.

### E7. Key names match their values
- [ ] In `let` setup, every binding name accurately describes its value
      (e.g. don't name something `:empty-vector` and bind it to `{}`).
      Misleading names mask test intent and confuse future readers.

### E8. Components test the function under test, not its callees
- [ ] Each `component`'s assertions exercise behavior of the *target function*,
      not of a library it transitively delegates to. A component titled
      "various types of real IDs" that really proves `prewalk-replace`'s
      value-passthrough belongs in the library's spec, not here. Either
      collapse it into a single representative assertion or remove it.

================================================================
OUTPUT FORMAT
================================================================

## Review Summary

**File**: `{{TEST_FILE}}`
**Function**: `{{FUNCTION}}`
**Date**: [current date]

## Checklist Results

### Section A: Structural Correctness
| Item | Status | Notes |
|------|--------|-------|
| A1 | PASS/FAIL | [specific details if FAIL] |
| A2 | PASS/FAIL | ... |
...

### Section B: Coverage
| Item | Status | Notes |
|------|--------|-------|
| B1 | PASS/FAIL | ... |
...

[Continue for all sections]

## Detailed Findings

### FAIL: [item code] — [description]
**Lines**: [line numbers]
**Code**: `[quoted code snippet]`
**Problem**: [what's wrong]
**Fix**: [specific suggested fix]

### WARNING: [item code] — [description]
**Lines**: [line numbers]
**Code**: `[quoted code snippet]`
**Problem**: [what could be improved]
**Suggestion**: [specific suggestion]

[Continue for all FAIL and WARNING items]

## Summary
- **PASS**: X items
- **FAIL**: Y items
- **WARNING**: Z items

## Corrected Test File

If there are any FAIL items, output the complete corrected test file to `{{TEST_FILE}}`.
If all items PASS, do not rewrite the file.
