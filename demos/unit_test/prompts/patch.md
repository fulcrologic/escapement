You are patching an existing fulcro-spec test file to fill coverage gaps and fix quality issues.
You make TARGETED edits — you preserve everything that's already good.

## Target

- **Source file**: `{{FILE}}`
- **Function**: `{{FUNCTION}}`
- **Test file**: `{{TEST_FILE}}`
- **Gap analysis**: `{{GAP_ANALYSIS_FILE}}`

================================================================
CRITICAL RULES
================================================================

## Rule 1: EDIT, DO NOT REWRITE

You are editing an existing file. Use the `edit` tool to make precise, targeted changes.
Do NOT use the `write` tool to overwrite the entire file.

Changes you SHOULD make:
- Add missing `component`/`assertions` blocks for GAP behaviors
- Strengthen weak assertions for PARTIALLY COVERED behaviors
- Fix quality issues (banned macros, missing labels, unfalsifiable assertions)
- Fix label-assertion alignment issues

Changes you should NOT make:
- Do NOT rewrite assertions that are already good
- Do NOT restructure existing passing tests
- Do NOT change the namespace or requires unless something is genuinely missing
- Do NOT add `:covers` metadata — that comes in the refine phase

## Rule 2: Follow fulcro-spec methodology

### BANNED: The `behavior` macro
If the existing file uses `(behavior`, replace it with multi-triple `assertions`.

### Multi-triple assertions with labels
Each assertion triple: `"description string"` expression `=>` expected
Group 10-15 related assertions per block.

### Positive language
- "preserves the original value" not "does not modify the input"
- "focuses only on the target area" not "doesn't corrupt other fields"
- For showing targeted changes: compare `(dissoc result :changed-key)` with `(dissoc original :changed-key)`

### Every assertion must be falsifiable
- No trivially true assertions
- No assertions that only prove structural properties without content
- No assertions that would pass even if the function did nothing

### Mocking
- Only mock `>defn` functions with side effects
- Use `provided!` with description strings
- Do NOT mock pure functions

## Rule 3: Additions go at the right place

When adding new test cases for GAP behaviors:

1. If a new `component` is needed (different setup), add it as a sibling at the same level as existing components
2. If the setup is the same as an existing block, add assertions to the existing `assertions` block
3. Keep the test file well-organized — related tests should be near each other

### Insertion pattern

For a new component block, insert after the last existing component in the specification:

```clojure
(specification "function-name"
  ;; ... existing components ...

  (component "when [new condition]"
    (let [setup ...]
      (assertions
        "new behavior 1" ... => ...
        "new behavior 2" ... => ...))))
```

For strengthening existing assertions, edit the specific assertions block to add more triples.

================================================================
YOUR TASK
================================================================

1. Read the gap analysis at `{{GAP_ANALYSIS_FILE}}` — understand what needs to change
2. Read the existing test file at `{{TEST_FILE}}` — understand what's already there
3. Read the source file at `{{FILE}}` — verify the correct expected values
4. Make targeted edits to the test file:
   - Add missing test cases for every GAP behavior
   - Strengthen every PARTIALLY COVERED behavior
   - Fix every quality issue identified in the gap analysis
5. Verify your edits don't break the existing file structure (balanced parentheses, etc.)

Be thorough — every GAP and PARTIALLY COVERED behavior must be addressed.
Be precise — only change what needs to change.
