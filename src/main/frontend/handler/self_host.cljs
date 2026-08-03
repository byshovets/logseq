(ns frontend.handler.self-host
  "Single-user self-host mode (fork-owned).

  No Cognito/JWKS. On boot we synthesize a local session so the sync UI unlocks
  and the client talks to the local db-sync adapter (run with
  DB_SYNC_DISABLE_AUTH=true). All of this is inert unless `config/self-host?` is
  set; `frontend.handler` fires `:self-host/init` on startup when it is.

  Kept in its own namespace so the fork adds behavior via a new file + one require
  rather than editing upstream files - see docs/self-host/PLAN.md 11."
  (:require [frontend.config :as config]
            [frontend.handler.db-based.rtc-flows :as rtc-flows]
            [frontend.handler.db-based.sync :as rtc-handler]
            [frontend.handler.events :as events]
            [frontend.handler.repo :as repo-handler]
            [frontend.persist-db :as persist-db]
            [frontend.state :as state]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

(defn- local-self-host-jwt
  "A syntactically-valid, unsigned JWT with a far-future exp. The client only
   base64-decodes the payload (no signature check), and the server ignores the
   token in DB_SYNC_DISABLE_AUTH mode, so this satisfies both sides."
  []
  (let [now (js/Math.floor (/ (js/Date.now) 1000))
        exp (+ now (* 100 365 24 3600))
        b64url (fn [s] (-> (js/btoa s)
                           (.replaceAll "+" "-")
                           (.replaceAll "/" "_")
                           (.replaceAll "=" "")))
        header (b64url "{\"alg\":\"none\",\"typ\":\"JWT\"}")
        ;; sub is a UUID so the graph-member page's :block/uuid is valid (search
        ;; rejects non-UUID block ids). Must match the server's local-user-claims.
        payload (b64url (js/JSON.stringify #js {:sub "00000000-0000-0000-0000-000000000001"
                                                :email "local@localhost"
                                                "cognito:username" "local"
                                                :exp exp
                                                :iat now}))]
    (str header "." payload ".")))

(defn- <self-host-auto-open!
  "If exactly one remote graph exists and it is not yet downloaded to this browser,
   download + open it so a fresh browser lands directly in the user's data. Uses
   the actual local OPFS db list (not get-repos, which also lists remote graphs)."
  []
  (p/let [graphs (state/get-rtc-graphs)
          local-dbs (persist-db/<list-db)
          local-urls (set (map :name local-dbs))]
    (when (= 1 (count graphs))
      (let [{:keys [url GraphName GraphUUID GraphSchemaVersion graph-e2ee?]} (first graphs)]
        (when (and url (not (contains? local-urls url)))
          ;; the :rtc/download-remote-graph event downloads AND switches to it
          (state/pub-event! [:rtc/download-remote-graph GraphName GraphUUID GraphSchemaVersion graph-e2ee?]))))))

(defmethod events/handle :self-host/init [[_]]
  (when @state/*db-worker
    (let [token (local-self-host-jwt)]
      (state/set-auth-id-token token)
      (state/set-auth-access-token token)
      (state/set-auth-refresh-token token)
      (js/localStorage.setItem "id-token" token)
      (js/localStorage.setItem "access-token" token)
      (js/localStorage.setItem "refresh-token" token)
      (state/set-user-info! {:UserGroups ["team"]}))
    (-> (p/do!
         (state/pub-event! [:rtc/sync-app-state])
         (state/<invoke-db-worker :thread-api/set-db-sync-config
                                  {:enabled? true
                                   :self-host? true
                                   :ws-url (config/db-sync-ws-url)
                                   :http-base (config/db-sync-http-base)})
         (rtc-handler/<get-remote-graphs)
         (repo-handler/refresh-repos!)
         (when-let [current-repo (state/get-current-repo)]
           (when (some #(= current-repo (:url %)) (state/get-rtc-graphs))
             (rtc-flows/trigger-rtc-start current-repo)))
         (<self-host-auto-open!))
        (p/catch (fn [e] (log/error :self-host/init-failed e))))))
