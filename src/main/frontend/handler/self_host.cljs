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
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.flows :as flows]
            [frontend.handler.db-based.rtc-flows :as rtc-flows]
            [frontend.handler.db-based.sync :as rtc-handler]
            [frontend.handler.events :as events]
            [frontend.handler.notification :as notification]
            [frontend.handler.repo :as repo-handler]
            [frontend.persist-db :as persist-db]
            [frontend.state :as state]
            [lambdaisland.glogi :as log]
            [logseq.common.config :as common-config]
            [missionary.core :as m]
            [promesa.core :as p]))

;; The sub is a UUID so the graph-member page's :block/uuid is valid (search
;; rejects non-UUID block ids). Must match the server's local-user-claims.
(def ^:private local-user
  {:sub "00000000-0000-0000-0000-000000000001"
   :email "local@localhost"
   :username "local"})

;; ---------------------------------------------------------------------------
;; Storage capability gate
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

(defn- show-storage-error-page!
  [body-text]
  (let [wrap (js/document.createElement "div")
        inner (js/document.createElement "div")
        title (js/document.createElement "h1")
        body (js/document.createElement "p")]
    (set! (.-id wrap) "self-host-opfs-error")
    (set! (.. wrap -style -cssText)
          (str "position:fixed;inset:0;z-index:99999;display:flex;align-items:center;"
               "justify-content:center;background:#1a1a1a;color:#eee;"
               "font-family:system-ui,sans-serif;text-align:center;padding:2rem;"))
    (set! (.. inner -style -cssText) "max-width:34rem")
    (set! (.. title -style -cssText) "font-size:1.4rem;margin-bottom:1rem")
    (set! (.. body -style -cssText) "line-height:1.6")
    (set! (.-textContent title) (t :self-host/storage-error-title))
    (set! (.-textContent body) body-text)
    (.append inner title body)
    (.append wrap inner)
    (js/document.body.appendChild wrap)))

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
        payload (b64url (js/JSON.stringify #js {:sub (:sub local-user)
                                                :email (:email local-user)
                                                "cognito:username" (:username local-user)
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
  "On a fresh browser, download + open the newest READY remote graph so a new
   device lands directly in the user's data. Newest by the server's
   `updated-at`, which changes on graph creation and on upload completion but
   NOT on edits - so with several graphs this picks the most recently added
   one, not the most recently edited one. A not-ready graph is still being
   bootstrap-uploaded (downloading it would 409), so when only not-ready graphs
   exist, poll for a while until the uploader finishes. A browser that already
   holds a user graph keeps its last-used graph. Uses the actual local OPFS db
   list (not get-repos, which also lists remote graphs)."
  []
  (p/let [local-dbs (persist-db/<list-db)
          local-urls (set (map :name local-dbs))]
    (p/loop [attempt 0]
      (let [graphs (state/get-rtc-graphs)
            ready (filterv #(not= false (:graph-ready-for-use? %)) graphs)]
        (when (fresh-browser? (state/get-current-repo))
          (cond
            (seq ready)
            (let [{:keys [url GraphName GraphUUID GraphSchemaVersion graph-e2ee?]}
                  (apply max-key #(or (:updated-at %) 0) ready)]
              (when (and url (not (contains? local-urls url)))
                ;; the :rtc/download-remote-graph event downloads AND switches to it
                (state/pub-event! [:rtc/download-remote-graph GraphName GraphUUID GraphSchemaVersion graph-e2ee?])))

            (and (seq graphs) (< attempt 12))
            (p/do!
             (p/delay 10000)
             (rtc-handler/<get-remote-graphs)
             (p/recur (inc attempt)))

            (seq graphs)
            (log/warn :self-host/auto-open-gave-up
                      {:reason :no-graph-became-ready :attempts attempt})))))))

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

(defn- remote-counterpart
  [repo]
  (some #(when (= repo (:url %)) %) (state/get-rtc-graphs)))

(def ^:private interrupted-upload-min-age-ms
  "The server's ready bit alone cannot distinguish an interrupted upload from
   one that is still in progress (another tab or browser bootstrap-uploading
   right now). `updated-at` is written at row creation, so only a not-ready row
   older than this is treated as interrupted and safe to recover."
  (* 30 60 1000))

(defn- interrupted-upload?
  "True when `remote` is a not-ready graph row old enough that no live
   bootstrap upload can plausibly still be writing it. The server creates the
   row not-ready and flips it only after the final snapshot batch."
  [remote]
  (and (false? (:graph-ready-for-use? remote))
       (number? (:updated-at remote))
       (> (- (js/Date.now) (:updated-at remote)) interrupted-upload-min-age-ms)))

(defn- <delete-interrupted-remote-graph!
  "Delete `repo`'s interrupted-upload leftover so the retry starts clean (a
   retried upload would otherwise fail with graph-already-exists)."
  [repo]
  (let [{:keys [GraphUUID GraphSchemaVersion]} (remote-counterpart repo)]
    (when GraphUUID
      (log/info :self-host/delete-interrupted-remote-graph {:repo repo :graph-uuid GraphUUID})
      (p/let [_ (rtc-handler/<rtc-delete-graph! GraphUUID GraphSchemaVersion)]
        (rtc-handler/<get-remote-graphs)))))

(defn- <upload-graph-with-recovery!
  "Upload `repo`, clearing an interrupted-upload leftover first. Failures are
   surfaced to the user; the next open of the graph retries automatically."
  [repo]
  (-> (p/let [remote (remote-counterpart repo)
              _ (when (interrupted-upload? remote)
                  (<delete-interrupted-remote-graph! repo))]
        ;; the delete round-trip may have outlived this graph being current
        (when (= repo (state/get-current-repo))
          (log/info :self-host/auto-upload-graph repo)
          (rtc-handler/<rtc-upload-graph! repo false)))
      (p/catch
       (fn [e]
         (log/error :self-host/auto-upload-failed {:repo repo :error e})
         (notification/show!
          (t :self-host/auto-upload-failed
             (common-config/strip-leading-db-version-prefix repo)
             (or (ex-message e) (str e)))
          :error)))))

(def ^:private remote-check-retry-delays-ms [5000 15000 45000])

(defn- <fetch-remote-graphs-with-retry!
  "Fetch the graph list, retrying a few times over a transient backend outage
   (the repo-flow is deduplicated, so nothing else would re-trigger the check).
   Rejects after the last attempt; stops early when `repo` is no longer
   current."
  [repo]
  (p/loop [attempt 0]
    (-> (rtc-handler/<get-remote-graphs)
        (p/catch
         (fn [e]
           (let [delay-ms (get remote-check-retry-delays-ms attempt)]
             (if (and delay-ms (= repo (state/get-current-repo)))
               (p/do!
                (p/delay delay-ms)
                (p/recur (inc attempt)))
               (throw e))))))))

(defn- <auto-upload-graph!
  "Sync `repo` to the self-host server when it has no ready remote counterpart:
   never-synced graphs upload, and interrupted uploads (old not-ready remote
   row + already-persisted local RTC id) recover instead of being skipped
   forever. The decision is made against a freshly fetched graph list, never a
   stale one. Demo graphs stay local: every fresh browser creates its own local
   Demo before init runs, so syncing it would collide with a remote Demo
   uploaded by another browser."
  [repo]
  (when (and repo
             (config/db-based-graph? repo)
             (not (config/demo-graph? repo))
             ;; pre-session fires can't reach the server; init re-runs this
             ;; after it installs the local session
             (some? (state/get-auth-id-token)))
    (p/let [db-ready? (<wait-for-db-conn repo)]
      (if-not db-ready?
        (log/info :self-host/auto-upload-no-db-conn repo)
        (when (= repo (state/get-current-repo))
          (-> (p/let [_ (<fetch-remote-graphs-with-retry! repo)]
                ;; re-check: the user may have switched graphs during the fetch
                (when (= repo (state/get-current-repo))
                  (let [remote (remote-counterpart repo)]
                    (cond
                      (and (false? (:graph-ready-for-use? remote))
                           (not (interrupted-upload? remote)))
                      (log/info :self-host/auto-upload-deferred
                                {:repo repo :reason :remote-upload-possibly-in-progress})

                      (and (or (nil? remote) (interrupted-upload? remote))
                           (nil? (:rtc/downloading-graph-uuid @state/state))
                           (not (true? (:rtc/uploading? @state/state))))
                      (<upload-graph-with-recovery! repo)))))
              (p/catch
               (fn [e]
                 (log/error :self-host/auto-upload-check-failed {:repo repo :error e})
                 (notification/show!
                  (t :self-host/auto-upload-failed
                     (common-config/strip-leading-db-version-prefix repo)
                     (or (ex-message e) (str e)))
                  :error)))))))))

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
;; the DOM is ready by now). Insecure context is the usual cause: OPFS only
;; exists on HTTPS or localhost origins.
(when (and config/self-host? (not (opfs-supported?)))
  (show-storage-error-page!
   (if (false? (.-isSecureContext js/window))
     (t :self-host/insecure-context-error-body)
     (t :self-host/opfs-error-body))))

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
      (state/set-user-info! {:UserGroups ["team"]})
      ;; What user-handler/set-tokens! does on the normal login path: without a
      ;; current login user, trigger-start-rtc-flow drops graph-switch/restore
      ;; triggers and live sync never starts in the first session.
      (reset! flows/*current-login-user
              {:email (:email local-user)
               :sub (:sub local-user)
               :cognito:username (:username local-user)}))
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
