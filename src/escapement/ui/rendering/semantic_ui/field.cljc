(ns escapement.ui.rendering.semantic-ui.field
  "Semantic-UI field + control renderers for the RAD statechart engine (fulcro-rad-statecharts 0.1.5,
   RAD 1.6.24).

   These mirror the 1.6.24 *headless* renderers (`com.fulcrologic.rad.rendering.headless.field` /
   `.controls`) — copying their `fr/render-field [type style]` and
   `statechart.control/render-control [type style instance control-key]` defmethod arities exactly —
   but emit Semantic-UI-classed DOM instead of bare HTML, so the browser SPA renders STYLED.

   Why not reuse `fulcro-rad-semantic-ui`'s field/control `defn`s verbatim? Those were written for
   RAD 1.6.18's *legacy* (UISM) form/report/control state machines: they require
   `com.fulcrologic.rad.form` / `…rad.control` / `…rad.report` and call helpers
   (`form/field-style-config`, `control/component-controls`, `report/control-renderer`, …) that the
   statechart stack replaces with the `com.fulcrologic.rad.statechart.*` variants. Bridging the field
   *bodies* to the statechart contract (the 1.6.18→1.6.24 adaptation) means we re-implement the small
   field markup against `statechart.form/field-context` (exactly as the in-repo TUI plugin does) while
   reusing Semantic-UI's CSS class vocabulary (`ui input`, `ui field`, `ui checkbox`, `ui selection
   dropdown`). The escapement explorer is reports-only today, so these field defmethods exist to
   satisfy the contract and style any future detail FORM; report cells render via
   `formatted-column-value`, not `render-field`."
  (:require
    [clojure.string :as str]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form :as-alias rform]
    [com.fulcrologic.rad.form-render :as fr]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.statechart.control :as control]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.rad.type-support.decimal :as math]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Field helpers (Semantic-UI "form field" markup)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- field-id
  "A stable DOM id for `qualified-key`."
  [qualified-key]
  (str (namespace qualified-key) "--" (name qualified-key)))

(defn- field-wrapper
  "Wraps `input-element` in a Semantic-UI `.field` (red `.error` when invalid) with a `<label>` and an
   optional validation message. `ctx` is the `statechart.form/field-context` map."
  [{:keys [field-label invalid? validation-message omit-label?]} qualified-key required? input-element]
  (dom/div {:className (str "field" (when invalid? " error"))
            :key       (str qualified-key)}
    (when-not omit-label?
      (dom/label {:htmlFor (field-id qualified-key)}
        field-label
        (when required? (dom/span {:className "ui red text"} " *"))))
    input-element
    (when invalid?
      (dom/div {:className "ui pointing red basic label"}
        (or validation-message "Invalid value")))))

(defn render-string-field
  "Renders a :string attribute as a Semantic-UI `.ui.input` text field."
  [{::rform/keys [form-instance] :as env} attribute]
  (let [qualified-key (ao/qualified-key attribute)
        {:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        required?     (ao/required? attribute)]
    (when visible?
      (field-wrapper ctx qualified-key required?
        (dom/div {:className (str "ui input" (when read-only? " disabled"))}
          (dom/input {:id       (field-id qualified-key)
                      :name     (str qualified-key)
                      :type     "text"
                      :readOnly (boolean read-only?)
                      :value    (or (str value) "")
                      :onChange (fn [evt]
                                  (when-not read-only?
                                    (form/input-changed! env qualified-key (evt/target-value evt))))}))))))

(defn render-multi-line-field
  "Renders a :string :multi-line attribute as a Semantic-UI `<textarea>` inside `.ui.form`-styled markup."
  [{::rform/keys [form-instance] :as env} attribute]
  (let [qualified-key (ao/qualified-key attribute)
        {:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        required?     (ao/required? attribute)]
    (when visible?
      (field-wrapper ctx qualified-key required?
        (dom/textarea {:id       (field-id qualified-key)
                       :name     (str qualified-key)
                       :readOnly (boolean read-only?)
                       :rows     4
                       :value    (or (str value) "")
                       :onChange (fn [evt]
                                   (when-not read-only?
                                     (form/input-changed! env qualified-key (evt/target-value evt))))})))))

(defn render-number-field
  "Renders an :int/:long attribute as a Semantic-UI numeric input, coercing the edited text to a Long."
  [{::rform/keys [form-instance] :as env} attribute]
  (let [qualified-key (ao/qualified-key attribute)
        {:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        required?     (ao/required? attribute)]
    (when visible?
      (field-wrapper ctx qualified-key required?
        (dom/div {:className (str "ui input" (when read-only? " disabled"))}
          (dom/input {:id       (field-id qualified-key)
                      :name     (str qualified-key)
                      :type     "number"
                      :readOnly (boolean read-only?)
                      :value    (or (str value) "")
                      :onChange (fn [evt]
                                  (when-not read-only?
                                    #?(:cljs (m/set-integer! form-instance qualified-key :event evt)
                                       :clj  (m/set-integer!! form-instance qualified-key :value evt))))}))))))

(defn render-decimal-field
  "Renders a :decimal attribute as a Semantic-UI numeric (step='any') input, coercing to a RAD decimal."
  [{::rform/keys [form-instance] :as env} attribute]
  (let [qualified-key (ao/qualified-key attribute)
        {:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        required?     (ao/required? attribute)]
    (when visible?
      (field-wrapper ctx qualified-key required?
        (dom/div {:className (str "ui input" (when read-only? " disabled"))}
          (dom/input {:id       (field-id qualified-key)
                      :name     (str qualified-key)
                      :type     "number"
                      :step     "any"
                      :readOnly (boolean read-only?)
                      :value    (math/numeric->str value)
                      :onChange (fn [evt]
                                  (when-not read-only?
                                    (m/set-value!! form-instance qualified-key
                                      (math/numeric (evt/target-value evt)))))}))))))

(defn render-instant-field
  "Renders an :instant attribute as a Semantic-UI date input (stores the html date string; RAD's
   instant coercion runs in the form layer)."
  [{::rform/keys [form-instance] :as env} attribute]
  (let [qualified-key (ao/qualified-key attribute)
        {:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        required?     (ao/required? attribute)]
    (when visible?
      (field-wrapper ctx qualified-key required?
        (dom/div {:className (str "ui input" (when read-only? " disabled"))}
          (dom/input {:id       (field-id qualified-key)
                      :name     (str qualified-key)
                      :type     "date"
                      :readOnly (boolean read-only?)
                      :value    (str (or value ""))
                      :onChange (fn [evt]
                                  (when-not read-only?
                                    #?(:cljs (m/set-string! form-instance qualified-key :event evt)
                                       :clj  (m/set-string!! form-instance qualified-key :value evt))))}))))))

(defn render-boolean-field
  "Renders a :boolean attribute as a Semantic-UI `.ui.checkbox`."
  [{::rform/keys [form-instance] :as env} attribute]
  (let [qualified-key (ao/qualified-key attribute)
        {:keys [value visible? read-only? field-label omit-label?] :as ctx} (form/field-context env attribute)]
    (when visible?
      (dom/div {:className "field" :key (str qualified-key)}
        (dom/div {:className (str "ui checkbox" (when read-only? " disabled"))}
          (dom/input {:id       (field-id qualified-key)
                      :name     (str qualified-key)
                      :type     "checkbox"
                      :checked  (boolean value)
                      :disabled (boolean read-only?)
                      :onChange (fn [_]
                                  (when-not read-only?
                                    (m/set-value!! form-instance qualified-key (not value))))})
          (when-not omit-label? (dom/label {:htmlFor (field-id qualified-key)} field-label)))))))

(defn render-enum-field
  "Renders an :enum attribute as a Semantic-UI styled `<select>` (the native select carries the
   `ui selection dropdown` look via `ui form` defaults; a JS dropdown would need the wrapper)."
  [{::rform/keys [form-instance] :as env} attribute]
  (let [qualified-key (ao/qualified-key attribute)
        {:keys [value visible? read-only?] :as ctx} (form/field-context env attribute)
        required?     (ao/required? attribute)
        labels        (ao/enumerated-labels attribute)
        values        (or (some-> (ao/enumerated-values attribute) vec) (vec (keys labels)))]
    (when visible?
      (field-wrapper ctx qualified-key required?
        (dom/select {:id        (field-id qualified-key)
                     :name      (str qualified-key)
                     :className "ui selection dropdown"
                     :disabled  (boolean read-only?)
                     :value     (str value)
                     :onChange  (fn [evt]
                                  (let [raw (evt/target-value evt)
                                        s   (cond-> raw (and (string? raw) (str/starts-with? raw ":")) (subs 1))
                                        kw  (when (seq s) (keyword s))]
                                    (m/set-value!! form-instance qualified-key kw)))}
          (dom/option {:value ""} "")
          (mapv (fn [v] (dom/option {:key (str v) :value (str v)}
                          (or (get labels v) (name v))))
            values))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Field defmethods (the fr/render-field [type style] contract, RAD 1.6.24)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod fr/render-field [:string :default]    [env attr] (render-string-field env attr))
(defmethod fr/render-field [:string :multi-line] [env attr] (render-multi-line-field env attr))
(defmethod fr/render-field [:int :default]       [env attr] (render-number-field env attr))
(defmethod fr/render-field [:long :default]      [env attr] (render-number-field env attr))
(defmethod fr/render-field [:decimal :default]   [env attr] (render-decimal-field env attr))
(defmethod fr/render-field [:boolean :default]   [env attr] (render-boolean-field env attr))
(defmethod fr/render-field [:instant :default]   [env attr] (render-instant-field env attr))
(defmethod fr/render-field [:enum :default]      [env attr] (render-enum-field env attr))
(defmethod fr/render-field [:keyword :default]   [env attr] (render-enum-field env attr))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Control defmethods (statechart.control/render-control [type style instance control-key])
;;
;; Arity copied EXACTLY from com.fulcrologic.rad.rendering.headless.controls (the 1.6.24 contract):
;; a 4-arg defmethod, NOT the fulcro-rad-semantic-ui `comp/factory` `{:keys [instance control-key]}`
;; shape (that one binds the legacy `com.fulcrologic.rad.control` UISM helpers, which the statechart
;; stack does not drive). Bodies emit Semantic-UI button / field markup.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod control/render-control [:button :default] [_control-type _style instance control-key]
  (let [controls (control/component-controls instance)
        {:keys [label action disabled? visible? class icon] :or {visible? true}} (get controls control-key)]
    (when (?! visible? instance)
      (dom/button {:className (or (?! class instance) "ui tiny primary button")
                   :data-rad-control (str control-key)
                   :key      (str control-key)
                   :disabled (boolean (?! disabled? instance))
                   :onClick  (fn [_] (when action (action instance)))}
        (when icon (dom/i {:className (str icon " icon")}))
        (?! label instance)))))

(defmethod control/render-control [:string :default] [_control-type _style instance control-key]
  (let [controls (control/component-controls instance)
        {:keys [label onChange placeholder visible?] :or {visible? true}} (get controls control-key)
        value    (or (control/current-value instance control-key) "")]
    (when (?! visible? instance)
      (dom/div {:className "field" :key (str control-key)}
        (when label (dom/label {:htmlFor (str "control-" (name control-key))} (?! label instance)))
        (dom/div {:className "ui input"}
          (dom/input {:id          (str "control-" (name control-key))
                      :type        "text"
                      :placeholder (str (?! placeholder))
                      :value       (str value)
                      :onChange    (fn [evt]
                                     (let [v (evt/target-value evt)]
                                       (control/set-parameter! instance control-key v)
                                       (when onChange (onChange instance v))))}))))))

(defmethod control/render-control [:boolean :default] [_control-type _style instance control-key]
  (let [controls (control/component-controls instance)
        {:keys [label onChange visible?] :or {visible? true}} (get controls control-key)
        value    (boolean (control/current-value instance control-key))]
    (when (?! visible? instance)
      (dom/div {:className "field" :key (str control-key)}
        (dom/div {:className "ui checkbox"}
          (dom/input {:id       (str "control-" (name control-key))
                      :type     "checkbox"
                      :checked  value
                      :onChange (fn [_]
                                  (let [nv (not value)]
                                    (control/set-parameter! instance control-key nv)
                                    (when onChange (onChange instance nv))))})
          (when label (dom/label {:htmlFor (str "control-" (name control-key))} (?! label instance))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Controls-map entry points (for `all-controls` parity) — signature
;; `(fn {:keys [instance control-key control]})`. The statechart path renders via the
;; `control/render-control` defmethods above; these exist so the legacy
;; `…control/type->style->control` map has Semantic-UI leaves.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-button-control
  "Controls-map `:button` entry."
  [{:keys [instance control-key]}]
  (control/render-control :button :default instance control-key))

(defn render-string-control
  "Controls-map `:string` entry."
  [{:keys [instance control-key]}]
  (control/render-control :string :default instance control-key))

(defn render-boolean-control
  "Controls-map `:boolean` entry."
  [{:keys [instance control-key]}]
  (control/render-control :boolean :default instance control-key))
