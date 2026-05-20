You are reviewing an existing fulcro-spec test file to identify coverage gaps and quality issues.
You cross-reference every behavior from the behavioral analysis against existing assertions.
You are rigorous, specific, and exhaustive.

## Target

- **Source file**: `{{FILE}}`
- **Function**: `{{FUNCTION}}`
- **Existing test file**: `{{TEST_FILE}}`
- **Behavioral analysis**: `{{BEHAVIORS_FILE}}`
- **Mock strategy**: `{{MOCK_STRATEGY_FILE}}`

================================================================
YOUR TASK
================================================================

Read ALL of these files:

1. The source file at `{{FILE}}` — understand the actual implementation
2. The existing test file at `{{TEST_FILE}}` — what's already tested
3. The behavioral analysis at `{{BEHAVIORS_FILE}}` — every behavior that should be tested
4. The mock strategy at `{{MOCK_STRATEGY_FILE}}` — how dependencies should be handled

Then perform a thorough gap analysis and quality review.

================================================================
PART 1: BEHAVIOR COVERAGE GAP ANALYSIS
================================================================

For EVERY behavior listed in the behavioral analysis, find the matching assertions in the existing test file.

Classify each behavior:

### COVERED — The behavior has at least one assertion that would fail if the behavior changed

- Note the line numbers of the covering assertion(s)
- Verify the assertion is falsifiable (see quality checks below)
- A behavior is ONLY truly covered if the assertion proves the specific outcome described

### PARTIALLY COVERED — The behavior has an assertion, but it's weak or incomplete

- Note WHAT is covered and WHAT is missing
- Example: label says "calculates discount for premium users" but assertion only checks `(number? result) => true`
- Example: behavior has edge cases (nil, boundary) but only the happy path is tested
- Example: label claims one thing but the assertion proves something different

### GAP — No assertion exists for this behavior

- This is the most important finding
- Note which behavior is missing and what the assertion should prove

================================================================
PART 2: QUALITY REVIEW
================================================================

Also review the existing test file for these quality issues:

### Q1. Banned `behavior` macro

- The string `(behavior` appearing anywhere is a quality issue

### Q2. Label-Assertion Alignment

- For each assertion triple, read the label and the expression SEPARATELY
- Does the expression actually prove what the label claims?
- FAIL if label says "validates email format" but assertion is `(count result) => 1`
- FAIL if label says "calculates total with tax" but assertion is `(contains? result :total) => true`
- FAIL if label describes a complex behavior but assertion only proves a trivially true property

### Q3. Tautological / Impossible-to-Fail Assertions

- No assertions that compare an immutable value to itself (e.g., testing that a Clojure map wasn't mutated)
- No assertions that would pass regardless of the function's behavior
- No assertions that test language/runtime guarantees rather than the function's behavior
- Key test: "Would this assertion still pass if the function body were replaced with `(fn [& _] input)`?"
- If yes, the assertion is tautological

### Q4. Positive Assertion Language

- Every assertion label should describe what the code DOES, not what it doesn't do
- BAD: "does not modify other fields" — GOOD: "preserves all fields outside the target area"
- BAD: "doesn't fail on nil" — GOOD: "returns the default value for nil input"
- For showing that a function focuses on a specific area of a data structure:
    - BAD: "doesn't corrupt other fields"
    - GOOD: "focuses only on the target area" with assertion:
      `(= (dissoc result :target-key) (dissoc original :target-key)) => true`
- For showing a pure function returns an unchanged value:
    - BAD: "does not modify the input"
    - GOOD: "preserves the original value" with assertion: `result => original`

### Q5. Falsifiable Assertions

- Every assertion must be capable of failing
- No `(count x) => 2` without also proving what the 2 items ARE
- No `(contains? m :k) => true` without proving the value at :k
- No `(some? (f x)) => true` — too weak to prove anything meaningful

### Q6. Missing Assertion Labels

- Every assertion triple must have a string label before the expression

================================================================
OUTPUT FORMAT
================================================================

Write a markdown file with this EXACT structure:

```markdown
# Gap Analysis: {{FUNCTION}}

**Existing test file**: `{{TEST_FILE}}`
**Source file**: `{{FILE}}`

## Coverage Summary

| Status | Count |
|--------|-------|
| COVERED | X |
| PARTIALLY COVERED | Y |
| GAP | Z |

## Behavior Coverage

### Behavior 1: [description from analysis]
- **Status**: COVERED / PARTIALLY COVERED / GAP
- **Covering assertions**: Lines XX-YY in test file (if covered)
- **What's proved**: [specifically what the existing assertion proves]
- **What's missing**: [if partially covered or gap, what needs to be added]

### Behavior 2: [description]
- **Status**: ...
...

## Quality Issues

### Issue 1: [short title]
- **Location**: Line XX in `{{TEST_FILE}}`
- **Code**: `[quoted code]`
- **Problem**: [what's wrong]
- **Suggestion**: [specific fix]

### Issue 2: ...
...

## Recommended Additions

### For GAP behaviors
For each GAP, describe the exact test that should be added:
- What `component` label to use (if new setup needed)
- The assertion label and expression
- What mocking is needed

### For PARTIALLY COVERED behaviors
For each partial coverage, describe what to strengthen:
- Which existing assertion to modify
- What additional assertions to add

### For quality issues
For each quality issue, describe the specific edit needed.
```
