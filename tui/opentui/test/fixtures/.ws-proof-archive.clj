;; bb WebSocket proof: http-kit server with as-channel WS route + a client.
;; Tests whether http-kit's WebSocket (server-side as-channel + ws upgrade)
;; works under Babashka/SCI, and whether a client handshake can be performed.
(require '[org.httpkit.server :as http])

(def received (atom []))
(def server-got (promise))

(defn handler [req]
  (http/as-channel req
    {:on-open    (fn [ch] (println "[server] open, websocket?" (type ch)))
     :on-receive (fn [ch msg]
                   (println "[server] received:" msg)
                   (deliver server-got msg)
                   (http/send! ch (str "echo:" msg)))
     :on-close   (fn [ch status] (println "[server] close" status))}))

(def stop (http/run-server handler {:port 0}))
(def port (:local-port (meta stop)))
(println "[server] listening on port" port)

;; --- Client side: raw WS handshake over a socket (no client lib in bb) ---
(import '[java.net Socket]
        '[java.io OutputStream InputStream]
        '[java.util Base64]
        '[java.security MessageDigest])

(defn ws-key [] (.encodeToString (Base64/getEncoder) (byte-array (map byte (repeatedly 16 #(rand-int 128))))))

(try
  (let [s   (Socket. "127.0.0.1" (int port))
        out (.getOutputStream s)
        in  (.getInputStream s)
        k   (ws-key)
        req (str "GET / HTTP/1.1\r\n"
                 "Host: 127.0.0.1:" port "\r\n"
                 "Upgrade: websocket\r\n"
                 "Connection: Upgrade\r\n"
                 "Sec-WebSocket-Key: " k "\r\n"
                 "Sec-WebSocket-Version: 13\r\n\r\n")]
    (.write out (.getBytes req "UTF-8"))
    (.flush out)
    (Thread/sleep 200)
    (let [buf (byte-array 1024)
          n   (.read in buf)
          resp (String. buf 0 (max 0 n) "UTF-8")]
      (println "[client] handshake response status line:" (first (clojure.string/split-lines resp)))
      (println "[client] upgraded?" (clojure.string/includes? resp "101")))
    ;; Send a masked text frame "hi" per RFC6455
    (let [payload (.getBytes "{\"k\":\"v\"}" "UTF-8")
          mask    (byte-array [1 2 3 4])
          masked  (byte-array (map-indexed (fn [i b] (bit-xor b (aget mask (mod i 4)))) payload))
          frame   (byte-array (concat [(unchecked-byte 0x81)                    ; FIN + text
                                       (unchecked-byte (bit-or 0x80 (count payload)))] ; mask + len
                                      (seq mask) (seq masked)))]
      (.write out frame)
      (.flush out))
    (println "[client] server received message:" (deref server-got 2000 :TIMEOUT))
    (.close s))
  (catch Throwable t
    (println "[client] ERROR:" (.getMessage t))
    (.printStackTrace t)))

(Thread/sleep 300)
(stop)
(println "DONE")
