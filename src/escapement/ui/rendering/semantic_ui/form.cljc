(ns escapement.ui.rendering.semantic-ui.form
  "Semantic-UI form STRUCTURAL renderers for the RAD statechart engine (fulcro-rad-statecharts 0.1.5,
   RAD 1.6.24).

   Form structural rendering in the statechart stack is multimethod-based: a plugin registers
   `defmethod`s on `com.fulcrologic.rad.statechart.form/render-element` (dispatch `[element style]`)
   plus the `fr/render-form :default` bridge — the controls-map `element->style->layout` is NOT
   consulted (same contract the in-repo TUI plugin and the headless form renderer use). This ns clones
   `com.fulcrologic.rad.rendering.headless.form`'s defmethod arities and emits Semantic-UI-classed DOM
   (`ui form`, `ui segment`, `ui buttons`). Individual *fields* still flow through
   `form/render-field` → the `fr/render-field` defmethods in `escapement.ui.rendering.semantic-ui.field`.

   (The escapement explorer is reports-only today; these exist to satisfy the 1.6.24 form contract and
   style any future detail form.)"
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.form-render :as fr]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.statechart.form :as form]
    [taoensso.timbre :as log]))

(defn- render-action-buttons
  "Renders Save / Undo / Cancel as a Semantic-UI `.ui.buttons` group wired to the statechart form ops."
  [{:com.fulcrologic.rad.form/keys [form-instance master-form] :as env}]
  (let [master-props (comp/props master-form)
        remote-busy? (seq (::app/active-remotes master-props))
        form-opts    (comp/component-options form-instance)
        can-save?    (not (?! (fo/read-only? form-opts) form-instance))]
    (dom/div {:className "ui buttons"}
      (when can-save?
        (dom/button {:className (str "ui primary button" (when remote-busy? " loading disabled"))
                     :disabled  (boolean remote-busy?)
                     :onClick   (fn [_] (form/save! env))}
          "Save"))
      (when can-save?
        (dom/button {:className "ui button" :onClick (fn [_] (form/undo-all! env))} "Undo"))
      (dom/button {:className "ui button" :onClick (fn [_] (form/cancel! env))} "Cancel"))))

(defn- render-form-fields-by-layout
  "Renders fields per `fo/layout` (rows become Semantic-UI `.fields`) or all non-identity attributes
   in declared order."
  [{:com.fulcrologic.rad.form/keys [form-instance] :as env}]
  (let [options    (comp/component-options form-instance)
        attributes (fo/attributes options)
        layout     (fo/layout options)]
    (apply comp/fragment
      (if layout
        (into []
          (map-indexed (fn [ridx row]
                         (dom/div {:key (str "row-" ridx) :className "fields"}
                           (apply comp/fragment
                             (mapv (fn [field-key]
                                     (if-let [attr (some #(when (= (ao/qualified-key %) field-key) %) attributes)]
                                       (form/render-field env attr)
                                       (do (log/warn "Layout references unknown attribute" field-key) nil)))
                               row)))))
          layout)
        (mapv (fn [attr]
                (when-not (ao/identity? attr)
                  (form/render-field env attr)))
          attributes)))))

(defn- render-subforms
  "Renders any subforms declared on this form, each in a Semantic-UI segment with Add/Delete buttons
   for to-many relations."
  [{:com.fulcrologic.rad.form/keys [form-instance] :as env}]
  (let [options  (comp/component-options form-instance)
        subforms (fo/subforms options)]
    (when (seq subforms)
      (apply comp/fragment
        (mapv (fn [[ref-key subform-opts]]
                (let [subform-class (fo/ui subform-opts)
                      props         (comp/props form-instance)
                      subform-data  (get props ref-key)]
                  (when subform-data
                    (dom/div {:key (str ref-key) :className "ui segment"}
                      (if (vector? subform-data)
                        (let [factory     (comp/computed-factory subform-class {:keyfn #(comp/get-ident subform-class %)})
                              can-add?    (?! (fo/can-add? subform-opts) form-instance ref-key)
                              can-delete? (fo/can-delete? subform-opts)]
                          (dom/div nil
                            (when can-add?
                              (dom/button {:className "ui tiny green button"
                                           :onClick   (fn [_]
                                                        (form/add-child! form-instance ref-key subform-class
                                                          (when (= can-add? :prepend) {:order :prepend})))}
                                "Add"))
                            (apply comp/fragment
                              (into []
                                (map-indexed
                                  (fn [cidx child-props]
                                    (dom/div {:key (str ref-key "-" cidx) :className "ui segment"}
                                      (factory child-props
                                        {:com.fulcrologic.rad.form/master-form     (:com.fulcrologic.rad.form/master-form env)
                                         :com.fulcrologic.rad.form/parent          form-instance
                                         :com.fulcrologic.rad.form/parent-relation ref-key})
                                      (when (?! can-delete? form-instance child-props)
                                        (dom/button {:className "ui tiny red button"
                                                     :onClick   (fn [_]
                                                                  (form/delete-child! form-instance ref-key
                                                                    (comp/get-ident subform-class child-props)))}
                                          "Delete"))))
                                  subform-data)))))
                        (let [factory (comp/computed-factory subform-class)]
                          (factory subform-data
                            {:com.fulcrologic.rad.form/master-form     (:com.fulcrologic.rad.form/master-form env)
                             :com.fulcrologic.rad.form/parent          form-instance
                             :com.fulcrologic.rad.form/parent-relation ref-key})))))))
          subforms)))))

;; -- render-element: structural elements --------------------------------------

(defmethod form/render-element [:form-container :default]
  [{:com.fulcrologic.rad.form/keys [form-instance master-form] :as env} _element]
  (let [props        (comp/props form-instance)
        master-props (when master-form (comp/props master-form))
        busy?        (seq (::app/active-remotes (or master-props props)))
        title        (some-> form-instance comp/component-options fo/title)]
    (dom/div {:className "ui form segment"}
      (when busy? (dom/div {:className "ui active inline loader"}))
      (when title (dom/h2 {:className "ui header"} (?! title form-instance props)))
      (fr/render-header env (comp/component-options form-instance fo/id))
      (form/render-element env :form-body-container)
      (fr/render-footer env (comp/component-options form-instance fo/id)))))

(defmethod form/render-element [:form-body-container :default] [env _element]
  (dom/div {:className "ui form"}
    (render-form-fields-by-layout env)
    (render-subforms env)))

(defmethod form/render-element [:ref-container :default] [env _element]
  (dom/div {:className "ui segment"}
    (render-subforms env)))

;; -- render-header/footer/form ------------------------------------------------

(defmethod fr/render-header :default [{:com.fulcrologic.rad.form/keys [form-instance] :as env} attr]
  (when (ao/identity? attr)
    (render-action-buttons env)))

(defmethod fr/render-footer :default [_env _attr] nil)

(defmethod fr/render-form :default [renv _id-attr]
  (form/render-element renv :form-container))

(defmethod fr/render-fields :default [env _id-attr]
  (dom/div {:className "ui form"}
    (render-form-fields-by-layout env)))
