(ns logseq.db-sync.worker.auth
  (:require [clojure.string :as string]
            [logseq.common.authorization :as authorization]
            [promesa.core :as p]))

(defn- bearer-token [auth-header]
  (when (and (string? auth-header) (string/starts-with? auth-header "Bearer "))
    (subs auth-header 7)))

(defn token-from-request [request]
  (or (bearer-token (.get (.-headers request) "authorization"))
      (let [url (js/URL. (.-url request))]
        (.get (.-searchParams url) "token"))))

(defn- decode-jwt-part [part]
  (let [pad (if (pos? (mod (count part) 4))
              (apply str (repeat (- 4 (mod (count part) 4)) "="))
              "")
        base64 (-> (str part pad)
                   (string/replace "-" "+")
                   (string/replace "_" "/"))
        raw (js/atob base64)]
    (js/JSON.parse raw)))

(defn unsafe-jwt-claims [token]
  (try
    (when (string? token)
      (let [parts (string/split token #"\.")]
        (when (= 3 (count parts))
          (decode-jwt-part (nth parts 1)))))
    (catch :default _
      nil)))

(def ^:private recoverable-auth-errors
  #{"invalid" "iss not found" "aud not found" "exp" "kid"})

(def ^:private truthy-env-values
  #{"1" "true" "yes" "on"})

(defn- recoverable-auth-error?
  [error]
  (when error
    (let [message (or (ex-message error) (some-> error .-message))]
      (contains? recoverable-auth-errors message))))

(defn- env-flag-enabled?
  [env k]
  (let [v (some-> env (aget k))]
    (cond
      (true? v) true
      (false? v) false
      (string? v) (contains? truthy-env-values (string/lower-case v))
      :else false)))

(defn- allow-unverified-jwt-claims?
  [env]
  (env-flag-enabled? env "DB_SYNC_ALLOW_UNVERIFIED_JWT_CLAIMS"))

(defn- auth-disabled?
  "Single-user self-host mode: accept every request as one fixed local user."
  [env]
  (env-flag-enabled? env "DB_SYNC_DISABLE_AUTH"))

;; sub must be a UUID: the client turns the owner's user-id into a graph-member
;; page's :block/uuid, and the search indexer rejects non-UUID block ids.
(def ^:private local-user-claims
  #js {"sub" "00000000-0000-0000-0000-000000000001"
       "email" "local@localhost"
       "email_verified" true
       "cognito:username" "local"
       "preferred_username" "local"
       "name" "Local"})

(defn- expired-token?
  [token]
  (when-let [claims (unsafe-jwt-claims token)]
    (let [exp (aget claims "exp")
          now-s (js/Math.floor (/ (.now js/Date) 1000))]
      (and (number? exp)
           (<= exp now-s)))))

(defn auth-claims [request env]
  (if (auth-disabled? env)
    (p/resolved local-user-claims)
   (let [token (token-from-request request)]
    (if (string? token)
      (if (expired-token? token)
        (p/resolved nil)
        (-> (authorization/verify-jwt token env)
            (p/catch (fn [error]
                       (cond
                         (recoverable-auth-error? error)
                         nil

                         (allow-unverified-jwt-claims? env)
                         (unsafe-jwt-claims token)

                         :else
                         (p/rejected error))))))
      (p/resolved nil)))))
