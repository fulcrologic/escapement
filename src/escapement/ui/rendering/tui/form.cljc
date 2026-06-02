(ns escapement.ui.rendering.tui.form
  "TUI form renderers for the RAD statechart engine (fulcro-rad-statecharts 0.1.5).

   Form *structural* rendering in 0.1.5 is multimethod-based: a rendering plugin registers
   `defmethod`s on `com.fulcrologic.rad.statechart.form/render-element` (dispatch `[element style]`)
   — the controls-map `element->style->layout` is no longer consulted. This ns registers those
   defmethods (mirroring f-r-s's headless form renderer) but emits fulcro-tui element maps.

   Field rendering still flows through `form/render-field` → the installed `fr/render-field :default`
   → the `type->style->control` controls (this plugin's field renderers), so scalar / pick-one fields
   are unchanged. Requiring this ns installs the defmethods (the plugin requires it for side effects)."
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.tui.elements :as e :refer [vbox hbox box text button line viewport]]
    [com.fulcrologic.fulcro.tui.engine :as engine]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.form-render :as fr]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.statechart.form :as scform]))

(defn- action-buttons
  "Save / Undo / Cancel buttons for the (master) form, wired to the statechart form ops."
  [{::form/keys [form-instance] :as env}]
  (let [read-only? (?! (fo/read-only? (comp/component-options form-instance)) form-instance)]
    (hbox {:height 1}
      (when-not read-only?
        (button {:id :form/save :color :green :highlight (e/focused? :form/save)
                 :shortcut [:alt "s"]
                 :on-activate (fn [] (scform/save! env))} " Save "))
      (when-not read-only?
        (button {:id :form/undo :highlight (e/focused? :form/undo)
                 :shortcut [:alt "u"]
                 :on-activate (fn [] (scform/undo-all! env))} " Undo "))
      (button {:id :form/cancel :highlight (e/focused? :form/cancel)
               :shortcut [:alt "c"]
               :on-activate (fn [] (scform/cancel! env))} " Cancel "))))

(defn- subform-block
  "Renders one subform `ref-key` (declared in `fo/subforms`) as a bordered block: each child rendered
   via its own form factory (recursing through `render-element`), with Add/Delete controls for to-many."
  [{::form/keys [form-instance master-form] :as _env} ref-key subform-opts]
  (let [Sub         (fo/ui subform-opts)
        props       (comp/props form-instance)
        data        (get props ref-key)
        can-add?    (?! (fo/can-add? subform-opts) form-instance ref-key)
        can-delete? (fo/can-delete? subform-opts)
        add-id      (keyword "add" (str (namespace ref-key) "_" (name ref-key)))
        ;; The items live in their own container (id `items-id`) that hosts the group-nav `:on-key`
        ;; (Alt-j/Alt-k) and excludes the trailing "+ Add" button.
        items-id    (keyword "items" (str (namespace ref-key) "_" (name ref-key)))
        computed    {:com.fulcrologic.rad.form/master-form     master-form
                     :com.fulcrologic.rad.form/parent          form-instance
                     :com.fulcrologic.rad.form/parent-relation ref-key}
        ;; Alt-j / Alt-k jump item-to-item within this subform's focus group, skipping inner fields.
        group-nav   (fn [ev]
                      (let [app  (comp/any->app form-instance)
                            tree (engine/current-node-tree app)]
                        (case (engine/key-chord ev)
                          [:alt "j"] (do (engine/focus-next-in-group! app tree ref-key) :handled)
                          [:alt "k"] (do (engine/focus-prev-in-group! app tree ref-key) :handled)
                          nil)))]
    (when data
      (vbox {:border? true :color :bright-black :padding 1}
        (text {:bold true :color :yellow} (name ref-key))
        (if (vector? data)
          (let [factory (comp/computed-factory Sub {:keyfn #(comp/get-ident Sub %)})]
            (vbox {}
              (vbox {:id items-id :on-key group-nav}
                (mapv (fn [child]
                        (let [del-id (keyword "del" (str (name ref-key) "_" (hash (comp/get-ident Sub child))))]
                          (vbox {:focus-group ref-key}
                            (factory child computed)
                            (when (?! can-delete? form-instance child)
                              (button {:id del-id :color :red :highlight (e/focused? del-id)
                                       :on-activate (fn [] (scform/delete-child! form-instance ref-key
                                                             (comp/get-ident Sub child)))}
                                " - Delete ")))))
                  data))
              (when can-add?
                (button {:id add-id :color :green :highlight (e/focused? add-id)
                         :on-activate (fn [] (scform/add-child! form-instance ref-key Sub))}
                  " + Add "))))
          ((comp/computed-factory Sub) data computed))))))

(defn- subforms
  "Renders every subform declared on the form (`fo/subforms`)."
  [{::form/keys [form-instance] :as env}]
  (let [subs (fo/subforms (comp/component-options form-instance))]
    (when (seq subs)
      (vbox {} (mapv (fn [[ref-key opts]] (subform-block env ref-key opts)) subs)))))

(defn- scalar-fields
  "Renders the non-subform fields (scalars + pick-one refs) using `fo/layout` when present, else the
   declared attribute order. Subform/component-ref attributes are skipped here (rendered by `subforms`)."
  [{::form/keys [form-instance] :as env}]
  (let [{::form/keys [attributes layout] :as options} (comp/component-options form-instance)
        k->attr   (into {} (map (fn [a] [(ao/qualified-key a) a])) attributes)
        subform?  (fn [a] (some? (fo/subform-options options a)))
        render-a  (fn [a] (when (and a (not (ao/identity? a)) (not (subform? a)))
                            (scform/render-field env a)))]
    ;; Render in `fo/layout` order when given (each row's fields stacked — vertical reads best in a
    ;; width-limited terminal, and pick-one/enum fields render their own modal subtree), else attr order.
    (let [ks (if (vector? layout) (into [] (mapcat identity) layout) (mapv ao/qualified-key attributes))]
      (vbox {} (mapv (fn [k] (render-a (k->attr k))) ks)))))

;; ── render-element defmethods (the 0.1.5 form structural-rendering contract) ──────────────────────

;; Entry bridge: the form's `render-layout` dispatches through `fr/render-form`; its `:default`
;; hands off to our `render-element :form-container`. Without this the form falls through to
;; fulcro-rad's UISM controls-map lookup ("No renderer was installed for … :form-container").
(defmethod fr/render-form :default [renv _id-attr]
  (scform/render-element renv :form-container))

(defmethod scform/render-element [:form-container :default]
  [{::form/keys [form-instance master-form] :as env} _element]
  (let [options (comp/component-options form-instance)
        nested? (not= master-form form-instance)
        title   (?! (fo/title options) form-instance (comp/props form-instance))]
    (if nested?
      ;; A nested subform: just its body, no title/action-buttons (the master owns those). It scrolls
      ;; as part of the master form's body viewport, so it needs no viewport of its own.
      (box {:border? true :color :bright-black :padding 1}
        (scform/render-element env :form-body-container))
      ;; `:grow 1` lets the master form fill the vertical space the root frame hands it. The title and
      ;; the control section stay pinned at the top; only the body — wrapped in a growing viewport —
      ;; scrolls, so a tall form (many fields / line items) never falls off the screen, and the
      ;; Save/Undo/Cancel controls remain visible while scrolling. Tabbing between fields autoscrolls
      ;; the viewport to keep the focused field visible.
      (vbox {:border? true :color :cyan :padding 1 :grow 1}
        (text {:bold true :color :bright-cyan} (str (or title "Form")))
        (line {})
        (scform/render-element env :form-controls)
        (line {})
        (viewport {:id :form-body :grow 1}
          (scform/render-element env :form-body-container))))))

;; The pinned control section at the top of the (master) form. It currently holds the
;; Save/Undo/Cancel action buttons, but is a distinct element so additional always-visible
;; controls (status, validation summary, custom buttons) can be added here without touching
;; the scrolling body.
(defmethod scform/render-element [:form-controls :default]
  [env _element]
  (action-buttons env))

(defmethod scform/render-element [:form-body-container :default]
  [env _element]
  (vbox {}
    (scalar-fields env)
    (subforms env)))

(defmethod scform/render-element [:ref-container :default]
  [env _element]
  (subforms env))
