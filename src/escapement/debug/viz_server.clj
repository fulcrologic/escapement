(ns escapement.debug.viz-server
  "Live, browser-based statechart visualization.

   The d2 source is compiled to an SVG **once** at startup; thereafter every
   chart configuration change is pushed to the browser as an SSE frame and
   the page rewrites a single `<style>` block to recolor states and edges by
   CSS class. The SVG itself (and therefore the layout) is never regenerated,
   so transitions don't shuffle the diagram.

   Each shape carries `class=\"state-<safe-id>\"` and each transition carries
   `class=\"edge-<safe-transition-id>\"` (emitted by `escapement.debug.d2`).
   The browser targets them with `[class~=\"...\"]` attribute selectors so
   no special-character escaping is required.

   Public API:

   * `start!` — render once, bind httpkit on `127.0.0.1:0`, watch a state
     atom's `:config` key, and return `{:url :port :stop :running?}`.
   * `stop!`  — tear the server down (idempotent).

   Babashka-compatible: only depends on bb-bundled `org.httpkit.server` and
   `cheshire.core`, plus `escapement.debug.d2` for the one-shot render."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [escapement.debug.d2 :as d2]
   [org.httpkit.server :as hk]))

(defn- log-err! [msg]
  (binding [*out* *err*] (println (str "[escapement.viz-server] " msg))))

;; ---------------------------------------------------------------------------
;; State change → fired-edge derivation
;; ---------------------------------------------------------------------------

(defn- as-set [x]
  (cond
    (nil? x)    #{}
    (set? x)    x
    (coll? x)   (set x)
    :else       #{x}))

(defn- transition-targets [t]
  (let [tgt (:target t)]
    (cond
      (nil? tgt)         []
      (sequential? tgt)  (vec tgt)
      :else              [tgt])))

(defn- fired-edge-classes
  "Heuristic: given the chart and a (before, after) configuration pair, return
   the CSS class names of transitions whose source is in `before` and at least
   one target is in `after`. These are the edges to flash as 'just fired'.

   The heuristic can over-report on parallel regions but never misses an edge
   that actually fired, which is the side we care about visually."
  [chart before after]
  (let [ebi    (::sc/elements-by-id chart)
        before (as-set before)
        after  (as-set after)]
    (vec
     (keep (fn [el]
             (when (and (= :transition (:node-type el))
                        (contains? before (:parent el))
                        (some after (transition-targets el)))
               (str "edge-" (d2/safe-id (:id el)))))
           (vals ebi)))))

(defn- state-classes
  "Returns CSS class names for the active state ids in `config`."
  [config]
  (vec (map #(str "state-" (d2/safe-id %)) (as-set config))))

;; ---------------------------------------------------------------------------
;; One-shot d2 → SVG render
;; ---------------------------------------------------------------------------

(defn- run-d2->svg-bytes!
  "Shells out to `d2`, returns either `{:svg <string>}` or `{:error msg}`.
   Writes both `chart.d2` and `chart.svg` to `session-dir` as a side-effect
   so the artifacts are available on disk too."
  [chart session-dir cfg]
  (try
    (let [dir      (.getAbsoluteFile (io/file (str session-dir)))
          _        (.mkdirs dir)
          d2-file  (.getAbsoluteFile (io/file dir "chart.d2"))
          svg-file (.getAbsoluteFile (io/file dir "chart.svg"))
          d2-cfg   (:d2 cfg)
          command  (or (:command d2-cfg) "d2")
          layout   (or (:layout d2-cfg) "elk")
          src      (d2/chart->d2 chart nil)
          _        (spit d2-file src)
          argv     [command (str "--layout=" layout) (str d2-file) (str svg-file)]
          proc     (-> (ProcessBuilder. ^java.util.List (mapv str argv))
                       (.redirectErrorStream true)
                       (.start))
          out      (slurp (.getInputStream proc))
          exit     (.waitFor proc)]
      (cond
        (not (zero? exit))
        {:error (str "d2 exited " exit ": " (apply str (take 500 out)))}

        (not (.exists svg-file))
        {:error (str "d2 reported success but " (str svg-file) " is missing")}

        :else
        {:svg     (slurp svg-file)
         :d2-path (str d2-file)
         :svg-path (str svg-file)}))
    (catch Throwable t
      {:error (str (.getName (class t)) ": " (.getMessage t))})))

(defn- strip-xml-prolog
  "HTML5 doesn't accept `<?xml ?>` prologs. Strip it (and any leading
   doctype) so the SVG can be inlined directly into the page."
  [svg]
  (-> svg
      (str/replace #"(?s)\A\s*<\?xml[^?]*\?>\s*" "")
      (str/replace #"(?s)\A\s*<!DOCTYPE[^>]*>\s*" "")))

;; ---------------------------------------------------------------------------
;; HTML page
;; ---------------------------------------------------------------------------

(def ^:private page-template
  "<!doctype html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<title>Escapement chart — __TITLE__</title>
<style>
  html, body { margin: 0; padding: 0; height: 100%; background: #fafafa;
               font: 13px -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
  header { padding: 6px 12px; border-bottom: 1px solid #ddd; background: #fff;
           display: flex; gap: 12px; align-items: center; }
  header h1 { font-size: 14px; margin: 0; font-weight: 600; }
  header .meta { color: #888; }
  header .status { margin-left: auto; font-variant-numeric: tabular-nums; color: #555; }
  .zoom-controls { display: flex; gap: 4px; align-items: center; margin-left: 16px; }
  .zoom-controls button { font: inherit; font-size: 12px; padding: 2px 8px; cursor: pointer;
                          border: 1px solid #ccc; background: #fff; border-radius: 3px; min-width: 28px; }
  .zoom-controls button:hover { background: #f0f0f0; }
  .zoom-controls .pct { min-width: 52px; text-align: center; font-variant-numeric: tabular-nums; }
  #chart { width: 100%; height: calc(100% - 36px); overflow: auto;
           padding: 12px; box-sizing: border-box; }
  /* Scaling is driven by JS (sets svg.style.width/height in pixels),
     so don't let CSS clamp the natural size. */
  #chart-wrap { display: inline-block; }
  #chart-wrap svg { display: block; max-width: none; }
</style>
<style id=\"live-style\"></style>
</head>
<body>
<header>
  <h1>Escapement</h1>
  <span class=\"meta\">__TITLE__</span>
  <span class=\"zoom-controls\">
    <button id=\"zoom-out\" title=\"Zoom out (⌘−)\">−</button>
    <button id=\"zoom-pct\" class=\"pct\" title=\"Reset to 100% (⌘0)\">100%</button>
    <button id=\"zoom-in\" title=\"Zoom in (⌘+)\">+</button>
    <button id=\"zoom-fit\" title=\"Fit width\">Fit</button>
  </span>
  <span class=\"status\" id=\"status\">connecting…</span>
</header>
<div id=\"chart\"><div id=\"chart-wrap\">__SVG__</div></div>
<script>
(function () {
  var live   = document.getElementById('live-style');
  var status = document.getElementById('status');
  var chart  = document.getElementById('chart');
  var wrap   = document.getElementById('chart-wrap');
  var svg    = wrap.querySelector('svg');
  var pctBtn = document.getElementById('zoom-pct');

  // --- Zoom -------------------------------------------------------------
  // d2 emits the SVG with explicit width/height attributes; capture those
  // as the natural size and drive every zoom level off it.
  function num(v, fallback) { var n = parseFloat(v); return isFinite(n) ? n : fallback; }
  var rect  = svg.getBoundingClientRect();
  var natW  = num(svg.getAttribute('width'),  rect.width);
  var natH  = num(svg.getAttribute('height'), rect.height);
  // Remove the SVG-level width/height attrs so our inline styles win cleanly.
  svg.removeAttribute('width');
  svg.removeAttribute('height');
  var zoom = 1;
  function apply() {
    svg.style.width  = (natW * zoom) + 'px';
    svg.style.height = (natH * zoom) + 'px';
    pctBtn.textContent = Math.round(zoom * 100) + '%';
  }
  function setZoom(z) { zoom = Math.max(0.1, Math.min(8, z)); apply(); }
  document.getElementById('zoom-in').onclick  = function () { setZoom(zoom * 1.2); };
  document.getElementById('zoom-out').onclick = function () { setZoom(zoom / 1.2); };
  pctBtn.onclick = function () { setZoom(1); };
  document.getElementById('zoom-fit').onclick = function () {
    var availW = chart.clientWidth - 24; // matches #chart padding
    if (natW > 0) setZoom(availW / natW);
  };
  // ⌘+ / ⌘− / ⌘0  → zoom (also Ctrl on non-Mac).
  window.addEventListener('keydown', function (e) {
    if (!(e.metaKey || e.ctrlKey)) return;
    var k = e.key;
    if (k === '=' || k === '+')      { e.preventDefault(); setZoom(zoom * 1.2); }
    else if (k === '-' || k === '_') { e.preventDefault(); setZoom(zoom / 1.2); }
    else if (k === '0')              { e.preventDefault(); setZoom(1); }
  });
  // ⌘-wheel zooms around the cursor; without modifier, the page scrolls
  // (#chart's overflow handles panning naturally).
  chart.addEventListener('wheel', function (e) {
    if (!(e.metaKey || e.ctrlKey)) return;
    e.preventDefault();
    var prev   = zoom;
    var factor = e.deltaY < 0 ? 1.1 : (1 / 1.1);
    setZoom(zoom * factor);
    // Keep the point under the cursor roughly in place after the scale change.
    var ratio = zoom / prev;
    var cr    = chart.getBoundingClientRect();
    var x     = e.clientX - cr.left + chart.scrollLeft;
    var y     = e.clientY - cr.top  + chart.scrollTop;
    chart.scrollLeft = x * ratio - (e.clientX - cr.left);
    chart.scrollTop  = y * ratio - (e.clientY - cr.top);
  }, { passive: false });
  apply();

  // --- Live SSE updates -------------------------------------------------
  function render(active, lastEdges) {
    var rules = [];
    (active || []).forEach(function (cls) {
      rules.push('[class~=\"' + cls + '\"] > .shape > rect,' +
                 ' [class~=\"' + cls + '\"] > .shape > circle,' +
                 ' [class~=\"' + cls + '\"] > .shape > ellipse,' +
                 ' [class~=\"' + cls + '\"] > .shape > polygon,' +
                 ' [class~=\"' + cls + '\"] > .shape > path' +
                 ' { fill: #ffe680 !important; stroke-width: 3 !important; }');
    });
    (lastEdges || []).forEach(function (cls) {
      rules.push('[class~=\"' + cls + '\"] path,' +
                 ' [class~=\"' + cls + '\"] polygon' +
                 ' { stroke: #c0392b !important; stroke-width: 3 !important;' +
                 '   fill: #c0392b !important; }');
    });
    live.textContent = rules.join('\\n');
  }

  var es = new EventSource('/events');
  es.onopen    = function () { status.textContent = 'live'; };
  es.onerror   = function () { status.textContent = 'disconnected'; };
  es.onmessage = function (e) {
    try {
      var msg = JSON.parse(e.data);
      render(msg.active, msg.lastEdges);
      if (msg.ts) status.textContent = 'live · ' + new Date(msg.ts).toLocaleTimeString();
    } catch (err) { /* ignore */ }
  };
})();
</script>
</body>
</html>
")

(defn- render-page [title svg]
  (-> page-template
      (str/replace "__TITLE__" (or title ""))
      (str/replace "__SVG__"   (strip-xml-prolog svg))))

;; ---------------------------------------------------------------------------
;; SSE plumbing
;; ---------------------------------------------------------------------------

(defn- sse-frame [data]
  (str "data: " (json/generate-string data) "\n\n"))

(defn- snapshot
  "Build the current SSE payload from the latest snapshot atom."
  [chart cur prev]
  {:active    (state-classes cur)
   :lastEdges (if prev (fired-edge-classes chart prev cur) [])
   :ts        (System/currentTimeMillis)})

(defn- broadcast! [channels payload]
  (let [frame (sse-frame payload)]
    (doseq [ch @channels]
      (try (hk/send! ch frame false)
           (catch Throwable _ nil)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn start!
  "Render the chart to SVG once, start an httpkit server on `127.0.0.1:0`,
   and watch `state-atom`'s `:config` key. On every config change, push an
   SSE frame to every connected browser.

   `state-atom` is the TUI state atom (the one carrying `:config`). When
   not provided, the server still serves the static SVG but never updates.

   Returns one of:

   * `{:url \"http://127.0.0.1:NNNN/\" :port NNNN :stop (fn []) :running? true}`
   * `{:error \"...\"}` — d2 invocation failed; nothing was started."
  [{:keys [chart state-atom session-dir config title]}]
  (let [{:keys [svg error d2-path svg-path]}
        (run-d2->svg-bytes! chart session-dir config)]
    (if error
      {:error error}
      (let [page      (render-page (or title "chart") svg)
            channels  (atom #{})
            last-cfg  (atom (some-> state-atom deref :config))
            chart-ref chart
            handler
            (fn [req]
              (case (:uri req)
                "/"
                {:status  200
                 :headers {"Content-Type" "text/html; charset=utf-8"
                           "Cache-Control" "no-cache"}
                 :body    page}

                "/events"
                (hk/as-channel
                 req
                 {:on-open
                  (fn [ch]
                    (swap! channels conj ch)
                    (let [cur (or @last-cfg
                                  (some-> state-atom deref :config))
                          payload (snapshot chart-ref cur nil)]
                      (hk/send! ch
                                {:status  200
                                 :headers {"Content-Type"      "text/event-stream"
                                           "Cache-Control"     "no-cache"
                                           "X-Accel-Buffering" "no"
                                           "Connection"        "keep-alive"}
                                 :body    (sse-frame payload)}
                                false)))
                  :on-close
                  (fn [ch _status] (swap! channels disj ch))})

                {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))
            server (hk/run-server handler
                                  {:ip                   "127.0.0.1"
                                   :port                 0
                                   :legacy-return-value? false})
            port   (hk/server-port server)
            watch-key (keyword "escapement.viz-server" (str "w-" port))
            _ (when state-atom
                (add-watch
                 state-atom watch-key
                 (fn [_k _r old new]
                   (let [old-cfg (:config old)
                         new-cfg (:config new)]
                     (when (not= old-cfg new-cfg)
                       (reset! last-cfg new-cfg)
                       (broadcast! channels (snapshot chart-ref new-cfg old-cfg)))))))
            stopped? (atom false)
            stop-fn
            (fn []
              (when (compare-and-set! stopped? false true)
                (when state-atom (remove-watch state-atom watch-key))
                (doseq [ch @channels]
                  (try (hk/close ch) (catch Throwable _ nil)))
                (reset! channels #{})
                (try (hk/server-stop! server) (catch Throwable _ nil))))]
        {:url      (str "http://127.0.0.1:" port "/")
         :port     port
         :d2-path  d2-path
         :svg-path svg-path
         :stop     stop-fn
         :running? true}))))

(defn stop!
  "Idempotent shutdown."
  [{:keys [stop] :as _server}]
  (when stop (stop)))
