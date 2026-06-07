(ns escapement.tui.log
  "LOG pane content renderer: role-colored event-scrollback lines + a
   `{:pos :total}` scroll indicator. Pure over the TUI state map + an explicit
   interior height + the LOG pane's OWN scroll offset (separate from the
   inspector pager and the LIVE pane). Does NOT draw the box (the compositor's
   `draw-box` does that)."
  (:require
    [escapement.tui.theme :as theme]
    [escapement.tui.compositor :as cmp]
    [escapement.tui.util :as util]))

(defn- log-source-tag
  "Display tag for a scrollback entry's `:source` (invokeid string → short id;
   keyword → its name; else \"?\")."
  [source]
  (cond
    (string? source)  (util/short-invokeid source)
    (keyword? source) (name source)
    :else             "?"))

(defn- log-entry->line
  "Build one role-colored, width-fit LOG line for scrollback entry `entry`.
   Layout: `<dim ts> <role-hued tag> <status-colored glyph> <summary>`, then
   `truncate-display`-padded/clipped to `interior-w` columns. The role token is
   wrapped ONCE with `role-sgr` (no double-coloring); the timestamp is dim
   (`:timestamp`), and the glyph is tinted by status when one is inferable."
  [s theme interior-w {:keys [source glyph summary ev] :as entry}]
  (let [ts      (util/ts->hms (:ts ev))
        tag     (log-source-tag source)
        rcode   (theme/role-sgr-themed theme s source)
        ;; status glyph color: error sources get error red, otherwise leave the
        ;; glyph plain (the role hue already links the line). draw-box pads.
        gcode   (when (= source :error) (theme/status-color theme :error))
        line    (str (theme/paint theme :timestamp ts) " "
                  (theme/sgr-wrap rcode tag) " "
                  (theme/sgr-wrap gcode (str (or glyph \·))) " "
                  (str summary))]
    (cmp/truncate-display line interior-w)))

(defn log-pane-lines
  "Render the LOG pane interior: the slice of the event scrollback that fits
   `interior-h` rows, each role-colored and `truncate-display`-fit to
   `interior-w`, plus the scroll position for the pane-title indicator.

   Returns `{:lines [<string> …] :scroll {:pos N :total M}}` where:
   - `:lines`  is AT MOST `interior-h` strings (fewer when the scrollback is
               short), each exactly `interior-w` display-columns wide. Pass them
               straight as `:body-lines` to `draw-box`.
   - `:total`  is the total scrollback entry count.
   - `:pos`    is the 1-based absolute index of the LAST visible line (the
               bottom of the window), i.e. `M` when fully scrolled to the newest
               entry. `0/0` when the scrollback is empty.

   `scroll-offset` is the LOG pane's OWN offset: number of entries to scroll UP
   from the newest (0 = bottom / live tail). It is clamped to
   `[0, max(0, total - interior-h)]` so it can never scroll past either end.

   `s` is the TUI state map (`:scrollback` is a vector of entries, each a map
   with `:source :glyph :summary :ev`, tolerant of bare-string entries).

   The optional 6th arg `cursor-idx` (absolute index into the scrollback)
   reverse-videos that line when it falls in the visible window — the integrator
   can use it to show a selected line; the primary mode is plain scroll."
  ([s theme interior-w interior-h scroll-offset]
   (log-pane-lines s theme interior-w interior-h scroll-offset nil))
  ([s theme interior-w interior-h scroll-offset cursor-idx]
   (let [scrollback (vec (:scrollback s))
         total      (count scrollback)
         room       (max 0 (or interior-h 0))
         max-off    (max 0 (- total room))
         off        (-> (or scroll-offset 0) (max 0) (min max-off))
         end        (max 0 (- total off))
         start      (max 0 (- end room))
         slice      (subvec scrollback start end)
         lines      (mapv
                      (fn [i entry]
                        (let [entry (if (map? entry) entry {:summary (str entry) :ev nil})
                              abs   (+ start i)
                              ln    (log-entry->line s theme interior-w entry)]
                          (if (and cursor-idx (= abs cursor-idx))
                            ;; reverse-video the selected row; truncate-display
                            ;; already fit it to interior-w, so wrap whole.
                            (str cmp/reverse-on-s ln theme/reset-attrs-s)
                            ln)))
                      (range)
                      slice)]
     {:lines  lines
      :scroll {:pos (if (zero? total) 0 end)
               :total total}})))
