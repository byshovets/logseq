(ns frontend.fs.http-fs
  "Implementation of fs protocol for server-side web app, using HTTP API calls"
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.fs.protocol :as protocol]
            [frontend.util :as util]
            [goog.object :as gobj]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]
            [logseq.common.path :as path]))

(defn- get-api-url
  "Get the base URL for filesystem API endpoints"
  [endpoint]
  (str "/api/fs/" endpoint))

(defn- api-fetch
  "Make an HTTP request to the filesystem API"
  [endpoint body]
  (-> (js/fetch (get-api-url endpoint)
                (clj->js {:method "POST"
                          :headers {"Content-Type" "application/json"}
                          :body (js/JSON.stringify (bean/->js body))}))
      (p/then (fn [response]
                (if (>= (.-status response) 400)
                  (p/rejected (js/Error. (str "API error: " (.-status response))))
                  (-> (.json response)
                      (p/then bean/->clj)))))
      (p/catch (fn [error]
                 (log/error :http-fs-api-error {:endpoint endpoint :error error})
                 (p/rejected error)))))

(defn- <contents-matched?
  [disk-content db-content]
  (when (and (string? disk-content) (string? db-content))
    (p/resolved (= (string/trim disk-content) (string/trim db-content)))))

(defn- write-file-impl!
  [repo dir rpath content {:keys [ok-handler error-handler old-content skip-compare? skip-transact?]} stat]
  (let [file-fpath (path/path-join dir rpath)]
    (if skip-compare?
      (p/catch
       (p/let [result (api-fetch "writeFile" {:repo repo :path file-fpath :content content})]
         (when ok-handler
           (ok-handler repo rpath result)))
       (fn [error]
         (if error-handler
           (error-handler error)
           (log/error :write-file-failed error))))
      (p/let [disk-content (when (not= stat :not-found)
                            (-> (api-fetch "readFile" {:path file-fpath})
                                (p/then bean/->clj)
                                (p/catch (fn [error]
                                           (js/console.error error)
                                           nil))))
              disk-content (or disk-content "")
              db-content (or old-content (db/get-file repo rpath) "")
              contents-matched? (<contents-matched? disk-content db-content)]
        (->
         (p/let [result (api-fetch "writeFile" {:repo repo :path file-fpath :content content})
                 mtime (gobj/get result "mtime")]
           (when-not contents-matched?
             (api-fetch "backupDbFile" {:repo-dir (config/get-local-dir repo) :path rpath :db-content disk-content :content content}))
           (when-not skip-transact? (db/set-file-last-modified-at! repo rpath mtime))
           (when ok-handler
             (ok-handler repo rpath result))
           result)
         (p/catch (fn [error]
                    (if error-handler
                      (error-handler error)
                      (log/error :write-file-failed error)))))))))

(defn- open-dir
  "Open a new directory"
  [dir]
  (p/let [dir-path (or dir (util/mocked-open-dir-path))
          result (if dir-path
                   (api-fetch "getFiles" {:path dir-path})
                   (api-fetch "openDir" {}))]
    result))

(defrecord HttpFs []
  protocol/Fs
  (mkdir! [_this dir]
    (-> (api-fetch "mkdir" {:dir dir})
        (p/then (fn [_] (js/console.log (str "Directory created: " dir))))
        (p/catch (fn [error]
                   (when-not (string/includes? (str error) "EEXIST")
                     (js/console.error (str "Error creating directory: " dir) error))))))

  (mkdir-recur! [_this dir]
    (api-fetch "mkdir-recur" {:dir dir}))

  (readdir [_this dir]                   ; recursive
    (api-fetch "readdir" {:dir dir}))

  (unlink! [_this repo path _opts]
    (api-fetch "unlink" {:repo-dir (config/get-repo-dir repo)
                         :path path}))

  (rmdir! [_this _dir]
    ;; !Too dangerous! We'll never implement this.
    nil)

  (read-file [_this dir path _options]
    (let [path (if (nil? dir)
                 path
                 (path/path-join dir path))]
      (-> (api-fetch "readFile" {:path path})
          (p/then (fn [result]
                    ;; Handle both string and object responses
                    (if (string? result)
                      result
                      (:content result result)))))))

  (write-file! [this repo dir path content opts]
    (p/let [fpath (path/path-join dir path)
            stat (p/catch
                  (protocol/stat this fpath)
                  (fn [_e] :not-found))
            parent-dir (path/parent fpath)
            _ (protocol/mkdir-recur! this parent-dir)]
      (write-file-impl! repo dir path content opts stat)))

  (rename! [_this _repo old-path new-path]
    (api-fetch "rename" {:old-path old-path :new-path new-path}))

  ;; copy with overwrite, without confirmation
  (copy! [_this repo old-path new-path]
    (api-fetch "copyFile" {:repo repo :old-path old-path :new-path new-path}))

  (stat [_this fpath]
    (api-fetch "stat" {:path fpath}))

  (open-dir [_this dir]
    (open-dir dir))

  (get-files [_this dir]
    (-> (api-fetch "getFiles" {:path dir})
        (p/then (fn [result]
                  (:files result result)))))

  (watch-dir! [_this dir options]
    ;; Watch is not implemented for HTTP backend yet
    ;; This would require WebSocket or polling
    (js/console.warn "watch-dir! not implemented for HTTP backend"))

  (unwatch-dir! [_this dir]
    ;; Unwatch is not implemented for HTTP backend yet
    (js/console.warn "unwatch-dir! not implemented for HTTP backend")))

