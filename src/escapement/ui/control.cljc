(ns escapement.ui.control
  "Client-side wrappers for the Escapement live debug control plane.

   The server exposes four Pathom control mutations whose wire symbols are
   `escapement.control/{pause,step,continue,arm-pause-on-next-external}` (see
   `escapement.ui.resolvers`). Fulcro sends the EXACT symbol written in a transaction over the
   `:remote`, so we cannot use plain `escapement.ui.control/*` `defmutation`s (those would transmit
   `escapement.ui.control/pause`, which the server does not know). Instead we:

     * `m/declare-mutation` a local alias var for each server symbol (so callers get a tidy
       `(control/pause {})` data-literal that serializes to the right wire symbol), and
     * provide `transact-*!` helpers that fire the remote mutation and then reload the shared live
       snapshot via `refresh-live!`.

   The live snapshot (paused?, step budget, active configuration, pending events) is loaded into one
   shared singleton entity, `live-ident` = `[:component/id ::live]`, which BOTH the Debugger panel
   and the ChartView query — so a step in either screen refreshes the other.

   CLJC + pure Fulcro data flow (no React hooks). The `refresh-live!`/`transact-*!` helpers are
   host-neutral."
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as m]))

;; ---------------------------------------------------------------------------
;; Shared live snapshot location + query
;; ---------------------------------------------------------------------------

(def live-ident
  "Singleton ident holding the live debug snapshot shared by the Debugger and ChartView."
  [:component/id ::live])

;; ---------------------------------------------------------------------------
;; Remote control mutation aliases. `m/declare-mutation` binds a var to an
;; explicit fully-qualified symbol; invoking the var as a function returns the
;; mutation data-literal carrying THAT symbol, so the wire symbol matches the
;; server `::pc/sym` regardless of this ns's name.
;; ---------------------------------------------------------------------------

(m/declare-mutation pause 'escapement.control/pause)
(m/declare-mutation step 'escapement.control/step)
(m/declare-mutation continue 'escapement.control/continue)
(m/declare-mutation arm-pause-on-next-external 'escapement.control/arm-pause-on-next-external)

;; ---------------------------------------------------------------------------
;; Live-state refresh
;; ---------------------------------------------------------------------------

(defn refresh-live!
  "Reload the live debug snapshot into `live-ident` from `:remote`. Each datum is loaded as its
   server root key and targeted under `live-ident` so they normalize into one entity that multiple
   screens read. `app-or-component` is anything `df/load!` accepts. Tolerates `not-found` (no live
   run) — those load keys simply remain absent."
  [app-or-component]
  (df/load! app-or-component :session/paused? nil
    {:target (conj live-ident :session/paused?)})
  (df/load! app-or-component :session/step-budget nil
    {:target (conj live-ident :session/step-budget)})
  (df/load! app-or-component :session/live-configuration nil
    {:target (conj live-ident :session/live-configuration)})
  (df/load! app-or-component :session/pending-events nil
    {:target (conj live-ident :session/pending-events)}))

;; ---------------------------------------------------------------------------
;; Control actions: fire the remote mutation, then refresh the snapshot.
;;
;; The control mutations return `{:debug/paused? :debug/step-budget}` but those
;; live under the controller, not our `live-ident`; rather than thread the
;; mutation join, we simply reload the full snapshot after each action so the
;; configuration + pending events also update. We refresh in a follow-on tx via
;; the mutation's completion: `comp/transact!` returns synchronously, so we
;; schedule the reload immediately (the load queue serializes after the
;; mutation send).
;; ---------------------------------------------------------------------------

(defn- control!
  "Transact remote control `mutation-expr` from `component`, then reload the shared live snapshot."
  [component mutation-expr]
  (comp/transact! component [mutation-expr])
  (refresh-live! component))

(defn pause! "Pause the live run, then refresh the shared snapshot." [component]
  (control! component (pause {})))

(defn step! "Single-step the live run by one event, then refresh the shared snapshot." [component]
  (control! component (step {})))

(defn continue! "Resume the live run, then refresh the shared snapshot." [component]
  (control! component (continue {})))

(defn arm! "Arm pause-on-next-external, then refresh the shared snapshot." [component]
  (control! component (arm-pause-on-next-external {})))
