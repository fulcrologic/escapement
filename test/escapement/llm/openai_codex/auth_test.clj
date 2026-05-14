(ns escapement.llm.openai-codex.auth-test
  (:require
   [clojure.string :as str]
   [escapement.llm.openai-codex.auth :as auth]
   [fulcro-spec.core :refer [assertions component specification =>]])
  (:import
   (java.util Base64)
   (java.security MessageDigest)))

;;; ---------------------------------------------------------------------------
;;; Helpers

(defn- b64url-decode
  "Decodes a URL-safe base64 (no padding) string to bytes."
  [^String s]
  (let [padded (let [r (mod (count s) 4)]
                 (if (zero? r) s (str s (subs "====" 0 (- 4 r)))))]
    (.decode (Base64/getUrlDecoder) ^String padded)))

(defn- sha256-bytes
  [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

;;; ---------------------------------------------------------------------------
;;; Tests

(specification "generate-pkce"
               (let [{:keys [verifier challenge]} (auth/generate-pkce)]
                 (assertions
                  "verifier matches URL-safe base64 pattern (no padding)"
                  (boolean (re-matches #"[A-Za-z0-9_\-]+" verifier)) => true
                  "verifier is at least 43 chars"
                  (>= (count verifier) 43) => true
                  "challenge is base64url of SHA-256 of verifier"
                  challenge => (let [digest  (sha256-bytes verifier)
                                     encoded (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) digest)]
                                 encoded)
                  "challenge differs from verifier"
                  (= verifier challenge) => false)))

(specification "build-authorize-url"
               (let [pkce  {:verifier "test-verifier" :challenge "test-challenge"}
                     state "my-random-state"
                     url   (auth/build-authorize-url pkce state)]
                 (assertions
                  "starts with the authorize endpoint"
                  (str/starts-with? url auth/AUTHORIZE-URL) => true
                  "contains response_type=code"
                  (str/includes? url "response_type=code") => true
                  "contains the correct client_id"
                  (str/includes? url (str "client_id=" auth/CLIENT-ID)) => true
                  "contains code_challenge_method=S256"
                  (str/includes? url "code_challenge_method=S256") => true
                  "contains the provided challenge"
                  (str/includes? url "code_challenge=test-challenge") => true
                  "contains the provided state"
                  (str/includes? url (str "state=" state)) => true
                  "contains originator param"
                  (str/includes? url "originator=codex_cli_rs") => true
                  "contains codex_cli_simplified_flow"
                  (str/includes? url "codex_cli_simplified_flow=true") => true)))

(specification "decode-jwt"
               (component "with a well-formed JWT containing the openai auth claim"
                          (let [claim-key "https://api.openai.com/auth"
                                payload   {:sub "user-123"
                                           (keyword claim-key) {:chatgpt_account_id "acct-abc"}}
                                json-str  (str "{\"sub\":\"user-123\","
                                               "\"" claim-key "\":{\"chatgpt_account_id\":\"acct-abc\"}}")
                                b64-payload (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                                             (.getBytes json-str "UTF-8"))
                                token     (str "header." b64-payload ".sig")]
                            (let [decoded (auth/decode-jwt token)]
                              (assertions
                               "returns a map"
                               (map? decoded) => true
                               "includes the subject claim"
                               (:sub decoded) => "user-123"
                               "includes the nested openai auth claim"
                               (get-in decoded [(keyword claim-key) :chatgpt_account_id]) => "acct-abc"))))

               (component "with a malformed token"
                          (assertions
                           "returns nil for a string with no dots"
                           (auth/decode-jwt "notajwt") => nil
                           "returns nil for garbage input"
                           (auth/decode-jwt "a.bad!base64.sig") => nil)))

(specification "extract-account-id"
               (component "happy path with valid JWT"
                          (let [claim-key  "https://api.openai.com/auth"
                                payload-json (str "{\"" claim-key "\":{\"chatgpt_account_id\":\"acct-999\"}}")
                                b64-payload  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                                              (.getBytes payload-json "UTF-8"))
                                token        (str "header." b64-payload ".sig")]
                            (assertions
                             "returns the account id"
                             (auth/extract-account-id token) => "acct-999")))

               (component "with missing claim"
                          (let [b64-payload (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                                             (.getBytes "{\"sub\":\"user\"}" "UTF-8"))
                                token       (str "h." b64-payload ".s")]
                            (assertions
                             "throws ex-info"
                             (try (auth/extract-account-id token) nil
                                  (catch clojure.lang.ExceptionInfo e
                                    (:token-prefix (ex-data e)))) => (subs token 0 (min 20 (count token)))))))

(specification "load-auth!"
               (assertions
                "returns nil when AUTH-FILE does not exist"
                (with-redefs [auth/AUTH-FILE "/tmp/definitely-does-not-exist-escapement-test.json"]
                  (auth/load-auth!)) => nil))

(specification "save-auth! + load-auth! roundtrip"
               (let [tmp-file (str "/tmp/escapement-auth-test-" (System/currentTimeMillis) ".json")
                     auth-map {:access-token  "tok-abc"
                               :refresh-token "ref-xyz"
                               :expires-at    9999999999000
                               :account-id    "acct-test"}]
                 (with-redefs [auth/AUTH-FILE tmp-file]
                   (auth/save-auth! auth-map)
                   (let [loaded (auth/load-auth!)]
                     (try
                       (assertions
                        "loaded map has the same access-token"
                        (:access-token loaded) => "tok-abc"
                        "loaded map has the same refresh-token"
                        (:refresh-token loaded) => "ref-xyz"
                        "loaded map has the same expires-at"
                        (:expires-at loaded) => 9999999999000
                        "loaded map has the same account-id"
                        (:account-id loaded) => "acct-test")
                       (finally
                         (try (.delete (java.io.File. tmp-file)) (catch Throwable _ nil))))))))
