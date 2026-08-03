(ns frontend.handler.self-host
  "Single-user self-host mode (fork-owned).

  No Cognito/JWKS. On boot we synthesize a local session so the sync UI unlocks
  and the client talks to the local db-sync adapter (run with
  DB_SYNC_DISABLE_AUTH=true). All of this is inert unless `config/self-host?` is
  set; `frontend.handler` fires `:self-host/init` on startup when it is.

  Kept in its own namespace so the fork adds behavior via a new file + one require
  rather than editing upstream files - see docs/self-host/PLAN.md 11."
  (:require [frontend.common.missionary :as c.m]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.flows :as flows]
            [frontend.handler.db-based.rtc-flows :as rtc-flows]
            [frontend.handler.db-based.sync :as rtc-handler]
            [frontend.handler.events :as events]
            [frontend.handler.repo :as repo-handler]
            [frontend.persist-db :as persist-db]
            [frontend.state :as state]
            [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [missionary.core :as m]
            [promesa.core :as p]))

;; ---------------------------------------------------------------------------
;; OPFS capability gate
;; ---------------------------------------------------------------------------

(defn- opfs-supported?
  "DB graphs live in sqlite-wasm over OPFS; there is no fallback storage.
   `createSyncAccessHandle` is worker-only so the main thread can only probe
   `navigator.storage.getDirectory` - every browser shipping it also ships the
   sync access handles the SAHPool VFS needs."
  []
  (boolean (and (exists? js/navigator)
                (.-storage js/navigator)
                (.-getDirectory ^js (.-storage js/navigator)))))

(defn- show-opfs-error-page!
  []
  (let [el (js/document.createElement "div")]
    (set! (.-id el) "self-host-opfs-error")
    (set! (.. el -style -cssText)
          (str "position:fixed;inset:0;z-index:99999;display:flex;align-items:center;"
               "justify-content:center;background:#1a1a1a;color:#eee;"
               "font-family:system-ui,sans-serif;text-align:center;padding:2rem;"))
    (set! (.-innerHTML el)
          (str "<div style=\"max-width:34rem\">"
               "<h1 style=\"font-size:1.4rem;margin-bottom:1rem\">This browser can't run Logseq self-host</h1>"
               "<p style=\"line-height:1.6\">Your notes are stored locally in an "
               "<b>Origin Private File System</b> (OPFS) sqlite database, and this browser "
               "does not support OPFS. Please use a recent version of Chrome, Edge, "
               "Firefox, or Safari, outside of private browsing.</p>"
               "</div>"))
    (js/document.body.appendChild el)))

;; ---------------------------------------------------------------------------
;; Local no-auth session
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Auto-open (fresh browser -> land in the user's data)
;; ---------------------------------------------------------------------------

(defn- fresh-browser?
  "True when this browser holds no user graph yet - boot auto-creates a local
   Demo graph before :self-host/init runs, so Demo-or-nil means fresh."
  [current-repo]
  (or (nil? current-repo) (config/demo-graph? current-repo)))

(defn- <self-host-auto-open!
  "On a fresh browser, download + open the most recently updated remote graph so
   a new device lands directly in the user's data. A browser that already holds a
   user graph keeps its last-used graph. Uses the actual local OPFS db list (not
   get-repos, which also lists remote graphs)."
  []
  (p/let [graphs (state/get-rtc-graphs)
          local-dbs (persist-db/<list-db)
          local-urls (set (map :name local-dbs))]
    (when (and (seq graphs) (fresh-browser? (state/get-current-repo)))
      (let [{:keys [url GraphName GraphUUID GraphSchemaVersion graph-e2ee?]}
            (apply max-key #(or (:updated-at %) 0) graphs)]
        (when (and url (not (contains? local-urls url)))
          ;; the :rtc/download-remote-graph event downloads AND switches to it
          (state/pub-event! [:rtc/download-remote-graph GraphName GraphUUID GraphSchemaVersion graph-e2ee?]))))))

;; ---------------------------------------------------------------------------
;; Auto-upload (first-run: a graph you create is synced without a manual step)
;; ---------------------------------------------------------------------------

(defn- <wait-for-db-conn
  "Graph creation/switch sets the current repo before the main-thread conn is
   registered, so poll for it. Resolves false on timeout."
  [repo]
  (p/create
   (fn [resolve _reject]
     (let [started (js/Date.now)]
       ((fn poll []
          (cond
            (some? (db/get-db repo)) (resolve true)
            (> (- (js/Date.now) started) 30000) (resolve false)
            :else (js/setTimeout poll 500))))))))

(defn- <auto-upload-graph!
  "Upload `repo` to the self-host server when it has never been synced (no RTC
   graph id in its local db). Demo graphs stay local: every fresh browser creates
   its own local Demo before init runs, so syncing it would collide with a
   remote Demo uploaded by another browser."
  [repo]
  (when (and repo
             (config/db-based-graph? repo)
             (not (config/demo-graph? repo)))
    (p/let [db-ready? (<wait-for-db-conn repo)]
      (if-not db-ready?
        (log/info :self-host/auto-upload-no-db-conn repo)
        (when (and (= repo (state/get-current-repo))
                   (nil? (ldb/get-graph-rtc-uuid (db/get-db repo)))
                   (not (some #(= repo (:url %)) (state/get-rtc-graphs)))
                   (nil? (:rtc/downloading-graph-uuid @state/state))
                   (not (true? (:rtc/uploading? @state/state))))
          (log/info :self-host/auto-upload-graph repo)
          (-> (rtc-handler/<rtc-upload-graph! repo false)
              (p/catch (fn [e] (log/error :self-host/auto-upload-failed {:repo repo :error e})))))))))

;; Direct m/reduce over the continuous current-repo-flow (same shape as
;; frontend.background-tasks); fire-and-forget - the upload guards itself.
(when config/self-host?
  (c.m/run-background-task
   ::auto-upload-local-graphs
   (m/reduce
    (fn [_ repo]
      (when repo
        (<auto-upload-graph! repo))
      nil)
    flows/current-repo-flow)))

;; The db-worker needs OPFS, so without it boot fails before :self-host/init
;; ever fires - surface the error page at namespace load (main.js is deferred,
;; the DOM is ready by now).
(when (and config/self-host? (not (opfs-supported?)))
  (show-opfs-error-page!))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

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
           (if (some #(= current-repo (:url %)) (state/get-rtc-graphs))
             (rtc-flows/trigger-rtc-start current-repo)
             (<auto-upload-graph! current-repo)))
         (<self-host-auto-open!))
        (p/catch (fn [e] (log/error :self-host/init-failed e))))))
