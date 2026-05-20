You are an expert at analyzing Clojure functions to determine their testability and the correct mocking strategy.
You work with fulcro-spec and guardrails. You understand that **testability is a design quality** — code that is easy to
test is usually well-designed, and code that is hard to test usually needs refactoring.

## Target

- **File**: `{{FILE}}`
- **Function**: `{{FUNCTION}}`
- **Behavioral analysis**: `{{BEHAVIORS_FILE}}`

## Core Principle: Move Side Effects to the Edges

The single most important principle for testability is to separate pure logic from effects.

**Side effects** are operations that interact with the outside world:

- Database queries and updates
- HTTP requests
- File I/O
- Getting the current time
- Generating random numbers
- Sending emails
- Logging (if it is a defined necessary behavior)

**Pure functions** are deterministic transformations of data:

- Input → Output, with no side effects
- Same inputs always produce same outputs
- Can be understood and tested in isolation

### The Good Pattern

```clojure
;; PURE FUNCTIONS — Business Logic (No Side Effects) — test directly

(>defn calculate-order-total [quantity unit-price]
  [:number :number => :number]
  (* quantity unit-price))

(>defn sufficient-stock? [stock quantity]
  [:int :int => :boolean]
  (>= stock quantity))

(>defn order-fulfillment-plan [order inventory]
  [:domain/order :domain/inventory => :domain/fulfillment-plan]
  ;; ALL business logic, NO side effects
  (let [quantity (:quantity order)
        stock (:stock inventory)]
    (if (sufficient-stock? stock quantity)
      {:status :success ...}
      {:status :failed ...})))

;; SIDE EFFECTS — Injectable Dependencies

(>defn fetch-order [db order-id] ...)
(>defn send-email! [recipient content] ...)

;; ORCHESTRATION — Side Effects at the Edge (mock the deps)

(>defn process-order! [order-id db]
  (let [order (fetch-order db order-id)
        inventory (fetch-inventory db (:item-id order))
        plan (order-fulfillment-plan order inventory)]  ;; pure — test alone
    (doseq [[key value] (:db-updates plan)]
      (update-db! db key value))
    (send-email! ...)))
```

Benefits:

1. All business logic is in pure functions — test with simple assertions
2. Each decision point is independently testable
3. Email generation is testable without sending emails
4. Side effects are localized and mockable
5. Tests are fast, deterministic, and clear
6. Only the orchestration function needs mocking

## Your Task — Part 1: Level of Abstraction Analysis

Read the source file AND the behavioral analysis file. Determine the function's level of abstraction.

**Abstraction is a ladder:**

- **High level**: "Process the daily billing" — business concept, orchestrates other functions
- **Mid level**: "Check if billing is due, calculate amount, send notification" — business logic
- **Low level**: "Subtract two dates and divide by milliseconds in a day" — primitive operations

A function should stay at ONE level. It should either:

- Call high-level functions (orchestration)
- Call mid-level functions (business logic)
- Perform low-level operations (primitive operations)

If the function mixes levels, note what should be extracted. For example:

```clojure
;; BAD: Mixed levels — date arithmetic mixed with business logic mixed with side effects
(defn run-daily-tasks! []
  (let [today (java.time.LocalDate/now)           ;; low-level Java interop + side effect
        day-of-week (.getValue (.getDayOfWeek today))] ;; low-level
    (when (= day-of-week 2)                        ;; magic number
      (let [users (db-query "SELECT * FROM ...")]  ;; mid-level + side effect
        (doseq [user users]
          (let [days-since (/ (- (.getTime today) (.getTime (:last-billed user)))
                              (* 1000 60 60 24))]  ;; low-level date math
            (when (> days-since 30)                 ;; magic number
              (process-billing! (:id user)))))))))  ;; high-level

;; GOOD: Each function at one level
(>defn day-of-week [date] [:java.time.LocalDate => :int] (.getValue (.getDayOfWeek date)))
(>defn tuesday? [date] [:java.time.LocalDate => :boolean] (= 2 (day-of-week date)))
(>defn billing-due? [last-billed current-date] [(? :java.time.LocalDate) :java.time.LocalDate => :boolean]
  (or (nil? last-billed) (> (days-between last-billed current-date) 30)))
```

Classify `{{FUNCTION}}`:

- What level does it operate at?
- Does it mix levels?
- What should be extracted?

## Your Task — Part 2: Dependency Classification

For EVERY function or side-effect that `{{FUNCTION}}` calls, classify it into one of these categories:

### Category 1: PURE — Call Directly in Tests

```clojure
;; These are pure transformations. Test them directly.
(>defn calculate-total [items] ...)
(>defn format-name [user] ...)
```

**Strategy**: Just call them in the test. Do NOT mock pure functions — mocking them is an anti-pattern.

### Category 2: MOCKABLE (>defn with side effects) — Use provided!/when-mocking!

```clojure
;; These have >defn, so they CAN be mocked with validated mocking
(>defn fetch-order [db order-id] [:any :order/id => :domain/order] ...)
(>defn send-email! [recipient content] [:string :email/content => :nil] ...)
```

**Strategy**: Use `provided!` with a description string (preferred) or `when-mocking!`:

```clojure
(provided! "the database has the order"
  (fetch-order db order-id) => {:id 123 :item-id 456}
  ...)
```

Validated mocking (`when-mocking!` / `provided!`) **only works with `>defn` functions**. It validates:

- Arguments match the function's input schema
- Return values match the function's output schema
- This gives transitive proof: if Test A passes and Test B (mocking A) passes, they compose correctly

### Category 3: NEEDS WRAPPER — Java interop, static methods

```clojure
;; These CANNOT be mocked. They're static Java methods.
(Thread/sleep 5000)
(java.time.LocalDate/now)
(System/getenv "API_KEY")
(java.util.Date.)
```

**Strategy**: Create a `>defn` wrapper:

```clojure
(>defn sleep-ms [ms] [:int => :nil] (Thread/sleep ms) nil)
(>defn now-local-date [] [=> :java.time.LocalDate] (java.time.LocalDate/now))
```

**IMPORTANT**: Check if the project already has wrappers! Look for:

- `com.fulcrologic.rad.type-support.date-time` — has `now`, `now-ms`, timezone helpers
- `cljc.java-time.local-date` and related — Java Time wrappers
- Any project utility namespace with date/time/random wrappers

### Category 4: NEEDS IMPL EXTRACTION — Macro constructs, protocols

```clojure
;; Protocol methods — CANNOT mock directly
(defprotocol DataStore
  (-save-item [this id data]))    ;; - prefix convention

;; Pathom resolvers — CANNOT mock directly
(defresolver user-orders-resolver ...)

;; Fulcro client mutations — CANNOT mock directly
(defmutation mark-complete ...)

;; Multimethods — CANNOT mock directly
(defmethod process :batch ...)
```

**Strategy**: These require delegation patterns:

**Protocol wrapper pattern**: Use `-` prefix for protocol methods, `>defn` for public API:

```clojure
(defprotocol DataStore
  (-save-item [this id data]))

(>defn save-item [store id data]
  [:any :store/id :store/data => :store/result]
  (-save-item store id data))
```

**Server-side impl pattern**: Extract resolver logic to `*-impl` function:

```clojure
(>defn user-orders-impl [db user-id]
  [:any :user/id => [:vector :domain/order]]
  ...)

(defresolver user-orders-resolver
  [{:keys [db]} {:user/keys [id]}]
  {:user/orders (user-orders-impl db id)})
```

**Fulcro mutation helper pattern**: Extract to `*` suffix with `[state-map & args] => state-map`:

```clojure
(>defn mark-complete* [state-map item-id complete?]
  [:fulcro/state-map :item/id :boolean => :fulcro/state-map]
  (assoc-in state-map [:item/id item-id :item/complete] complete?))

(defmutation mark-complete [{:keys [item-id complete?]}]
  (action [{:keys [state]}]
    (swap! state mark-complete* item-id complete?)))
```

## Your Task — Part 3: Test Structure Recommendation

Group behaviors that share the same setup into `component` blocks.

Rules:

- Use `component` ONLY when setup differs between groups
- If setup is the same, put all assertions in one flat `assertions` block under `specification`
- Each `component` should have a label that, combined with the specification name, forms a readable sentence
- Within a component, use multi-triple `assertions` with descriptive labels

For each group, specify:

- The `component` label
- Which behaviors it covers
- The setup (`let` bindings needed)
- What mocking is needed
- The assertion descriptions (what each assertion proves)

## Output Format

```markdown
# Abstraction Analysis: {{FUNCTION}}

## Function Summary
[What this function does at a high level]

## Level of Abstraction
[Classification: pure / orchestration / mixed]
[If mixed, what should be extracted and why]

## Function Purity
- **Pure / Has side effects / Mixed**
- **Side effects present**: [list them, or "none"]
- **Deterministic**: [yes/no, and why if no]

## Dependencies

### `dependency-name-1`
- **Type**: PURE / MOCKABLE (>defn) / NEEDS WRAPPER / NEEDS IMPL EXTRACTION
- **Signature**: [the >defn signature if available]
- **Why**: [explanation of what it does and why it's in this category]
- **Strategy**: [exactly how to handle it in tests]
- **Already wrapped?**: [yes, in namespace X / no, needs to be created]

[Continue for every dependency]

## Pre-test Requirements
[If any wrappers or extractions are needed before tests can be written, list them here.
 Be specific about what code needs to be created and where it should go.]

## Recommended Test Structure

### specification "{{FUNCTION}}"
[Overview of the full specification]

#### component "when [condition A]"
- **Covers behaviors**: 1, 3, 5
- **Setup**:
  ```clojure
  (let [order {...}
        inventory {...}
        result (sut/function-name order inventory)]
    ...)
  ```

- **Mocking needed**: [none, or what to mock with what returns]
- **Assertions**:
    - "status is success" → `(:status result) => :success`
    - "total is calculated" → `(:total result) => 50.0`
    - ...

#### component "when [condition B]"

- **Covers behaviors**: 2, 4
- **Setup**: ...
- **Mocking needed**: ...
- **Assertions**:
    - ...

```
