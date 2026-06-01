(ns escapement.ui.rendering.tui.field
  "TUI field and control renderers for the RAD statechart engine.

   Field renderers have signature `(fn [env attribute])` (see `com.fulcrologic.rad.form/render-field`)
   and return a fulcro-tui node: typically an `hbox` of a label `text` plus an editing element whose
   `:on-change`/`:on-activate` informs the form statechart (`form/input-changed!`).

   Pickers (enum / to-one ref / to-many ref / autocomplete) open a focus-trapping `modal` list. A TUI
   cannot use React hooks, so the \"is this picker open?\" flag lives in app state under `::open-picker`
   (only one picker is open at a time) and is toggled by the small mutations below.

   Control renderers have signature `(fn {:keys [instance control-key control]})` (see
   `com.fulcrologic.rad.control/render-control`)."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.lambda :as lambda]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.tui.elements :as e :refer [vbox hbox text input button viewport]]
    [com.fulcrologic.fulcro.tui.engine :as engine]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.form :as-alias rform]
    [com.fulcrologic.rad.form-render :as fr]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.picker-options :as po]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [com.fulcrologic.rad.type-support.decimal :as math]))

(def ^:private label-width
  "Cell width of a field's leading label column."
  16)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- value->string
  "Returns a display string for an arbitrary field `value` (empty string for nil)."
  [value]
  (cond
    (nil? value) ""
    (string? value) value
    :else (str value)))

(defn- field-node-id
  "A focus/caret id for a field that is unique per form INSTANCE (so the same attribute on multiple
   subform rows — e.g. each line item's `:quantity` — gets a distinct id)."
  [form-instance qualified-key prefix]
  (keyword prefix (str (some-> (comp/get-ident form-instance) second)
                    "_" (namespace qualified-key) "_" (name qualified-key))))

(defn- label-text
  "Returns the label string for `attribute`, suffixed with `*` when the attribute is required."
  [field-label qualified-key attribute]
  (str (or field-label (some-> qualified-key name str/capitalize))
    (when (ao/required? attribute) "*")))

(defn- label-cell
  "Renders the leading label column for a field, colored red when the field is currently invalid. The
   validation MESSAGE is not shown here — `with-validation` renders it on its own row below the field."
  [{:keys [field-label invalid?]} qualified-key attribute]
  (text {:width label-width :color (if invalid? :bright-red :cyan)}
    (label-text field-label qualified-key attribute)))

(defn- with-validation
  "Wraps a field's primary `row` node. When the field is invalid, returns a `vbox` of the row followed
   by a validation-message row (indented under the value column, in red); otherwise returns `row`
   unchanged. Keeps messages off the label so fields stay aligned and readable."
  [{:keys [invalid? validation-message]} row]
  (if invalid?
    (vbox {}
      row
      (hbox {:height 1}
        (text {:width label-width} "")
        (text {:color :bright-red} (str "↳ " (or validation-message "Invalid")))))
    row))

;; ── Picker open/close state (one picker open at a time, kept in app state) ──────────────────────────

(m/defmutation ^:private set-open-picker
  "Records which picker (by node id) is currently open; `nil` closes all. When `focus` is supplied, also
   moves keyboard focus to that node id — used on close to return focus to the picker's trigger button
   (otherwise focus resets to the top of the form, since the focused modal option row just disappeared)."
  [{:keys [id focus]}]
  (action [{:keys [state]}]
    (swap! state assoc ::open-picker id)
    (when focus (swap! state assoc ::engine/focus focus))))

(m/defmutation ^:private set-autocomplete-filter
  "Records the transient filter string typed into the autocomplete picker `id`."
  [{:keys [id s]}]
  (action [{:keys [state]}] (swap! state assoc-in [::autocomplete-filter id] s)))

(defn- picker-open?
  "True when the picker identified by `pick-id` is the currently-open picker."
  [form-instance pick-id]
  (= pick-id (get (rapp/current-state (comp/any->app form-instance)) ::open-picker)))

(defn- open-picker! [form-instance pick-id] (comp/transact! form-instance [(set-open-picker {:id pick-id})]))
(defn- close-picker!
  "Closes any open picker. With `focus-id`, restores focus to that node (the trigger button) so focus
   doesn't jump to the top of the form when the modal's focused option row disappears."
  ([form-instance] (comp/transact! form-instance [(set-open-picker {:id nil})]))
  ([form-instance focus-id] (comp/transact! form-instance [(set-open-picker {:id nil :focus focus-id})])))

(defn- autocomplete-filter
  "The current transient filter string for autocomplete picker `pick-id` (\"\" if none)."
  [form-instance pick-id]
  (get-in (rapp/current-state (comp/any->app form-instance)) [::autocomplete-filter pick-id] ""))

(defn- modal-id
  "Derives a distinct modal node id from a field's `pick-id` (so the trigger button and the modal don't
   share an id)."
  [pick-id]
  (keyword (namespace pick-id) (str (name pick-id) "-modal")))

(defn- option-list
  "Renders the option rows of a picker modal: a scrolling `viewport` of focusable buttons, one per
   `{:text :value}` option. The currently-selected option(s) are check-marked and highlighted. `value`
   may not be `name`-able (idents), so row ids are index-based. `selected?` is `(fn [value] boolean)`."
  [pick-id options selected? on-select]
  (viewport {:id (keyword (namespace pick-id) (str (name pick-id) "-vp")) :grow 1}
    (vbox {}
      (if (seq options)
        (map-indexed
          (fn [i {:keys [text value]}]
            (let [row-id (keyword (namespace pick-id) (str (name pick-id) "-opt-" i))
                  sel?   (selected? value)]
              (button {:id          row-id
                       :highlight   (e/focused? row-id)
                       :color       (if sel? :bright-green :bright-white)
                       :on-activate (fn [] (on-select value))}
                (str (if sel? "✓ " "  ") text))))
          options)
        (text {:color :bright-black} "No options.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scalar field renderers (string / int / decimal / multi-line)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-long-safe
  "Returns the integer value of string `s`, or nil if it does not parse."
  [s]
  (when (and (string? s) (re-matches #"-?\d+" (str/trim s)))
    #?(:clj (Long/parseLong (str/trim s)) :cljs (js/parseInt s 10))))

(defn- parse-decimal-safe
  "Returns the RAD decimal value of string `s`, or nil if it doesn't parse as a number."
  [s]
  (when (and (string? s) (seq (str/trim s)) (re-matches #"-?\d*\.?\d+" (str/trim s)))
    (try (math/numeric (str/trim s)) (catch #?(:clj Exception :cljs :default) _ nil))))

(defn field-renderer
  "Returns a field render fn (`(fn [env attribute])`) that draws a labelled `input`. `string->model`
   converts the raw edited string into the value stored on the form (defaults to identity);
   `input-attrs` are merged onto the `input` node (e.g. `{:multiline? true :height 4}`).

   On change we set the value **synchronously** with `m/set-value!!` (so the controlled terminal input
   reflects the keystroke on the same render — `input-changed!` alone posts an async statechart event
   and the input would snap back), and ALSO fire `input-changed!` so the form's triggers
   (`:derive-fields`, validation, dependent pickers) run."
  ([] (field-renderer identity {}))
  ([string->model] (field-renderer string->model {}))
  ([string->model input-attrs]
   (fn [{::rform/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
     (let [{:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)]
       (when visible?
         (with-validation ctx
           (hbox {:height (or (:height input-attrs) 1)}
             (label-cell ctx qualified-key attribute)
             (input (merge {:id        (field-node-id form-instance qualified-key "field")
                            :grow      1
                            :color     :bright-white
                            :value     (value->string value)
                            :on-change (fn [v & _]
                                         (when-not read-only?
                                           (let [model (string->model v)]
                                             (m/set-value!! form-instance qualified-key model)
                                             (form/input-changed! env qualified-key model))))}
                      input-attrs)))))))))

(def render-string-field
  "Renders a :string attribute as a labelled text input."
  (field-renderer identity))

(def render-multi-line-field
  "Renders a :string :multi-line attribute as a labelled multi-row text area (Enter inserts a newline)."
  (field-renderer identity {:multiline? true :height 4}))

(def render-int-field
  "Renders an :int attribute as a labelled text input, coercing the edited text to a Long when valid
   (a not-yet-numeric in-progress edit is kept as the raw string so typing still shows)."
  (field-renderer (fn [s] (or (parse-long-safe s) s))))

(def render-decimal-field
  "Renders a :decimal attribute as a labelled text input, coercing to a RAD decimal when valid so
   derived fields (subtotal/total) recompute; an in-progress edit is kept as the raw string."
  (field-renderer (fn [s] (or (parse-decimal-safe s) s))))

(defn render-instant-field
  "Renders an :instant attribute as a labelled `YYYY-MM-DD` date input. The stored value is an inst;
   the input shows/edits the html date string and parses it back to an inst when complete (an
   in-progress edit is kept as the raw string so typing still shows)."
  [{::rform/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
  (let [{:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        display (cond
                  (string? value) value
                  (inst? value) (dt/inst->html-date value)
                  :else "")]
    (when visible?
      (with-validation ctx
        (hbox {:height 1}
          (label-cell ctx qualified-key attribute)
          (input {:id        (field-node-id form-instance qualified-key "field")
                  :grow      1
                  :color     :bright-white
                  :value     display
                  :on-change (fn [v & _]
                               (when-not read-only?
                                 (let [model (if (re-matches #"\d{4}-\d{2}-\d{2}" (str/trim (or v "")))
                                               (dt/html-date->inst (str/trim v))
                                               v)]
                                   (m/set-value!! form-instance qualified-key model)
                                   (form/input-changed! env qualified-key model))))}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Boolean field
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-boolean-field
  "Renders a :boolean attribute as a focusable `[x]`/`[ ]` toggle button. Activating flips the value."
  [{::rform/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
  (let [{:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        toggle-id (field-node-id form-instance qualified-key "bool")]
    (when visible?
      (with-validation ctx
        (hbox {:height 1}
          (label-cell ctx qualified-key attribute)
          (button {:id          toggle-id
                   :color       (if value :bright-green :bright-white)
                   :highlight   (e/focused? toggle-id)
                   :on-activate (fn [] (when-not read-only?
                                         (let [nv (not value)]
                                           (m/set-value!! form-instance qualified-key nv)
                                           (form/input-changed! env qualified-key nv))))}
            (str (if value " [x] " " [ ] ") (if value "Yes" "No"))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Enum field (modal picker over enumerated labels)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-enum-field
  "Renders an :enum attribute as a button showing the current label; activating opens a modal list of
   the enumerated options (`ao/enumerated-labels`). Selecting sets the keyword value and closes."
  [{::rform/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
  (let [{:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        labels   (ao/enumerated-labels attribute)
        values   (or (some-> (ao/enumerated-values attribute) vec) (vec (keys labels)))
        options  (mapv (fn [v] {:text (str (get labels v (name v))) :value v}) values)
        pick-id  (field-node-id form-instance qualified-key "enum")
        cur-lbl  (when value (str (get labels value (name value))))]
    (when visible?
      (vbox {}
        (with-validation ctx
          (hbox {:height 1}
            (label-cell ctx qualified-key attribute)
            (button {:id          pick-id
                     :color       :bright-magenta
                     :highlight   (e/focused? pick-id)
                     :on-activate (fn [] (when-not read-only? (open-picker! form-instance pick-id)))}
              (str " " (or cur-lbl "(choose)") " ▾"))))
        (e/modal {:id (modal-id pick-id) :open? (picker-open? form-instance pick-id)
                  :title (label-text (:field-label ctx) qualified-key attribute)
                  :width 40 :height 12 :on-dismiss (fn [] (close-picker! form-instance pick-id))}
          (option-list pick-id options
            (fn [v] (= v value))
            (fn [v]
              (m/set-value!! form-instance qualified-key v)
              (form/input-changed! env qualified-key v)
              (close-picker! form-instance pick-id))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ref pickers (to-one / to-many) — picker-options driven
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ref-value->ident
  "Normalizes a to-one `:ref` field's current value to an ident `[id-key id]` (or nil). A loaded ref
   is a denormalized `{id-key id}` map; a just-selected ref (set via `m/set-value!!`) is a bare ident
   vector. Both must resolve to the same ident so the current selection matches an option."
  [target-id-key value]
  (cond
    (and (vector? value) (= 2 (count value)) (keyword? (first value))) value
    (and (map? value) (some? (get value target-id-key)))               [target-id-key (get value target-id-key)]
    :else nil))

(defn render-ref-pick-one
  "Renders a to-one `:ref` field as a button showing the current selection; activating opens a modal
   list of options. Options come from the picker-options cache (`po/current-form-options`, loaded by
   the form's `load-picker-options` step / dependent `:on-change` triggers). Selecting sets the ref
   ident, fires `input-changed!` (so dependent pickers/derives run), and closes.

   The current label is matched directly from the field value rather than via `po/current-to-one-*`,
   which only reads a denormalized `{id-key id}` map and so would show UNSELECTED for a freshly-picked
   bare-ident value."
  [{::rform/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
  (let [{:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        options     (vec (po/current-form-options form-instance attribute))
        current-val (ref-value->ident (ao/target attribute) value)
        current-lbl (some (fn [opt] (when (= (:value opt) current-val) (:text opt))) options)
        pick-id     (field-node-id form-instance qualified-key "pick")]
    (when visible?
      (vbox {}
        (with-validation ctx
          (hbox {:height 1}
            (label-cell ctx qualified-key attribute)
            (button {:id          pick-id
                     :color       :bright-magenta
                     :highlight   (e/focused? pick-id)
                     :on-activate (fn [] (when-not read-only? (open-picker! form-instance pick-id)))}
              (str " " (or current-lbl "(choose)") " ▾"))))
        (e/modal {:id (modal-id pick-id) :open? (picker-open? form-instance pick-id)
                  :title (str "Select " (label-text (:field-label ctx) qualified-key attribute))
                  :width 50 :height 14 :on-dismiss (fn [] (close-picker! form-instance pick-id))}
          (option-list pick-id options
            (fn [v] (= v current-val))
            (fn [v]
              (m/set-value!! form-instance qualified-key v)
              (form/input-changed! env qualified-key v)
              (close-picker! form-instance pick-id))))))))

(defn render-ref-pick-many
  "Renders a to-many `:ref` field. The current selections are shown as a list; a button opens a modal
   multi-select where activating an option toggles its membership (selected rows are check-marked).
   The stored value is a vector of idents."
  [{::rform/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
  (let [{:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        options   (vec (po/current-form-options form-instance attribute))
        selected  (set (or value []))
        pick-id   (field-node-id form-instance qualified-key "pickm")
        sel-label (fn [v] (some (fn [{:keys [text value]}] (when (= value v) text)) options))
        toggle    (fn [v] (let [s (if (contains? selected v) (disj selected v) (conj selected v))]
                            (vec s)))]
    (when visible?
      (vbox {}
        (with-validation ctx
          (hbox {:height 1}
            (label-cell ctx qualified-key attribute)
            (button {:id          pick-id
                     :color       :bright-magenta
                     :highlight   (e/focused? pick-id)
                     :on-activate (fn [] (when-not read-only? (open-picker! form-instance pick-id)))}
              (str " + Edit (" (count selected) ") "))))
        (when (seq selected)
          (vbox {}
            (mapv (fn [v] (text {:color :bright-white} (str "  • " (or (sel-label v) (str v))))) selected)))
        (e/modal {:id (modal-id pick-id) :open? (picker-open? form-instance pick-id)
                  :title (str "Select " (label-text (:field-label ctx) qualified-key attribute))
                  :width 50 :height 14 :on-dismiss (fn [] (close-picker! form-instance pick-id))}
          (option-list pick-id options
            (fn [v] (contains? selected v))
            (fn [v]
              (let [nv (toggle v)]
                (m/set-value!! form-instance qualified-key nv)
                (form/input-changed! env qualified-key nv)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Autocomplete (type-to-filter modal list)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-autocomplete-field
  "Renders a to-one ref/enum field whose modal contains a filter `input` plus a list that narrows as
   you type. The filter string is transient UI state (`::autocomplete-filter`, allowed per the
   transient-search-string rule). Options come from `po/current-form-options` (refs) or
   `ao/enumerated-labels` (enums)."
  [{::rform/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
  (let [{:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        enum?       (= :enum (ao/type attribute))
        labels      (when enum? (ao/enumerated-labels attribute))
        all-options (if enum?
                      (mapv (fn [v] {:text (str (get labels v (name v))) :value v})
                        (or (some-> (ao/enumerated-values attribute) vec) (vec (keys labels))))
                      (vec (po/current-form-options form-instance attribute)))
        current-val (if enum? value (po/current-to-one-value form-instance attribute))
        current-lbl (if enum?
                      (when value (str (get labels value (name value))))
                      (po/current-to-one-label form-instance attribute))
        pick-id     (field-node-id form-instance qualified-key "auto")
        filter-id   (keyword (namespace pick-id) (str (name pick-id) "-filter"))
        flt         (autocomplete-filter form-instance pick-id)
        flt-lc      (str/lower-case (str/trim flt))
        options     (if (seq flt-lc)
                      (filterv (fn [{:keys [text]}] (str/includes? (str/lower-case (str text)) flt-lc)) all-options)
                      all-options)]
    (when visible?
      (vbox {}
        (with-validation ctx
          (hbox {:height 1}
            (label-cell ctx qualified-key attribute)
            (button {:id          pick-id
                     :color       :bright-magenta
                     :highlight   (e/focused? pick-id)
                     :on-activate (fn [] (when-not read-only? (open-picker! form-instance pick-id)))}
              (str " " (or current-lbl "(choose)") " ▾"))))
        (e/modal {:id (modal-id pick-id) :open? (picker-open? form-instance pick-id)
                  :title (str "Find " (label-text (:field-label ctx) qualified-key attribute))
                  :width 50 :height 14 :on-dismiss (fn [] (close-picker! form-instance pick-id))}
          (vbox {:grow 1}
            (hbox {:height 1}
              (text {:width 8 :color :cyan} "Filter:")
              (input {:id        filter-id
                      :grow      1
                      :color     :bright-white
                      :value     flt
                      :on-change (fn [v & _] (comp/transact! form-instance
                                               [(set-autocomplete-filter {:id pick-id :s (or v "")})]))}))
            (e/line {})
            (option-list pick-id options
              (fn [v] (= v current-val))
              (fn [v]
                (m/set-value!! form-instance qualified-key v)
                (form/input-changed! env qualified-key v)
                (comp/transact! form-instance [(set-autocomplete-filter {:id pick-id :s ""})])
                (close-picker! form-instance pick-id)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Controls (control type->style->control) — report/form filter & action controls
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-button-control
  "Renders a `:button` control as a fulcro-tui `button`. Activation invokes the control's `:action`.
   The action is called arity-tolerantly: RAD control actions are usually `(fn [this] …)` (1-arg), but
   some take `[this control-key]`, so we adapt rather than always passing 2 args."
  [{:keys [instance control-key control]}]
  (let [{:keys [label action disabled? visible? shortcut]} control
        label    (?! label instance)
        visible? (or (nil? visible?) (?! visible? instance))
        ctl-id   (keyword "control" (name control-key))]
    (when visible?
      (button (cond-> {:id          ctl-id
                       :color       :green
                       :bold        true
                       :highlight   (e/focused? ctl-id)
                       :on-activate (fn [] (when (and action (not (?! disabled? instance)))
                                             ((lambda/->arity-tolerant action) instance control-key)))}
                shortcut (assoc :shortcut shortcut))
        (str " " (or label (name control-key)) " ")))))

(defn render-string-control
  "Renders a `:string` control as a labelled `input`. Edits call the control's `:onChange` (after
   storing the value via `control/set-parameter!`)."
  [{:keys [instance control-key control]}]
  (let [{:keys [label onChange visible?]} control
        label    (?! label instance)
        visible? (or (nil? visible?) (?! visible? instance))
        value    (control/current-value instance control-key)]
    (when visible?
      (hbox {:height 1}
        (text {:width label-width :color :cyan} (str (or label (name control-key))))
        (input {:id        (keyword "control" (name control-key))
                :grow      1
                :color     :bright-white
                :value     (value->string value)
                :on-change (fn [v & _]
                             (control/set-parameter! instance control-key v)
                             (when onChange (onChange instance v)))})))))

(defn render-boolean-control
  "Renders a `:boolean` control as a `[x]`/`[ ]` toggle button. Activation flips the parameter then
   invokes the control's `:onChange` (and `:action`, if any)."
  [{:keys [instance control-key control]}]
  (let [{:keys [label onChange action visible?]} control
        label    (?! label instance)
        visible? (or (nil? visible?) (?! visible? instance))
        value    (boolean (control/current-value instance control-key))
        ctl-id   (keyword "control" (name control-key))]
    (when visible?
      (button {:id          ctl-id
               :color       (if value :bright-green :bright-white)
               :highlight   (e/focused? ctl-id)
               :on-activate (fn []
                              (let [nv (not value)]
                                (control/set-parameter! instance control-key nv)
                                (when onChange (onChange instance nv))
                                (when action (action instance nv))))}
        (str (if value " [x] " " [ ] ") (or label (name control-key)))))))

(defn render-picker-control
  "Renders a `:picker` control as a button + modal list. Selecting sets the control parameter and runs
   the control's `:action` (e.g. `report/filter-rows!`). Options come from the picker-options cache
   (`po/current-picker-options`); they must be preloaded into that cache at startup (a render-time
   `po/load-picker-options!` would re-stamp state every frame and loop), e.g. via `po/load-picker-options!`
   in `client/main`."
  [{:keys [instance control-key control]}]
  (let [{:keys [label action visible?]} control
        label     (?! label instance)
        visible?  (or (nil? visible?) (?! visible? instance))
        value     (control/current-value instance control-key)
        options   (vec (po/current-picker-options instance control))
        ctl-id    (keyword "control" (name control-key))
        pick-id   (keyword "control-pick" (name control-key))
        cur-lbl   (some (fn [opt] (when (= (:value opt) value) (:text opt))) options)]
    (when visible?
      (vbox {}
        (hbox {:height 1}
          (text {:width label-width :color :cyan} (str (or label (name control-key))))
          (button {:id          ctl-id
                   :color       :bright-magenta
                   :highlight   (e/focused? ctl-id)
                   :on-activate (fn [] (comp/transact! instance [(set-open-picker {:id pick-id})]))}
            (str " " (or cur-lbl value "(any)") " ▾")))
        (e/modal {:id (modal-id pick-id) :open? (= pick-id (get (rapp/current-state (comp/any->app instance)) ::open-picker))
                  :title (str (or label (name control-key)))
                  :width 40 :height 12 :on-dismiss (fn [] (comp/transact! instance [(set-open-picker {:id nil})]))}
          (option-list pick-id options
            (fn [v] (= v value))
            (fn [v]
              (control/set-parameter! instance control-key v)
              (comp/transact! instance [(set-open-picker {:id nil})])
              (when action ((lambda/->arity-tolerant action) instance control-key)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Field renderer registration (fulcro-rad-statecharts 0.1.5)
;;
;; The statechart form renders fields via the `fr/render-field` multimethod, dispatched on
;; `[attribute-type field-style]` (NOT the controls map). Requiring this ns installs them.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod fr/render-field [:string :default]       [env attr] (render-string-field env attr))
(defmethod fr/render-field [:string :multi-line]    [env attr] (render-multi-line-field env attr))
(defmethod fr/render-field [:string :autocomplete]  [env attr] (render-autocomplete-field env attr))
(defmethod fr/render-field [:int :default]          [env attr] (render-int-field env attr))
(defmethod fr/render-field [:decimal :default]      [env attr] (render-decimal-field env attr))
(defmethod fr/render-field [:boolean :default]      [env attr] (render-boolean-field env attr))
(defmethod fr/render-field [:enum :default]         [env attr] (render-enum-field env attr))
(defmethod fr/render-field [:enum :autocomplete]    [env attr] (render-autocomplete-field env attr))
(defmethod fr/render-field [:instant :default]      [env attr] (render-instant-field env attr))
(defmethod fr/render-field [:instant :date-at-noon] [env attr] (render-instant-field env attr))
(defmethod fr/render-field [:ref :pick-one]         [env attr] (render-ref-pick-one env attr))
(defmethod fr/render-field [:ref :pick-many]        [env attr] (render-ref-pick-many env attr))
(defmethod fr/render-field [:ref :autocomplete]     [env attr] (render-autocomplete-field env attr))
