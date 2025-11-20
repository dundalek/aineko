#!/usr/bin/env bb
;; Aineko - manage multiple Claude Code agent sessions across projects and devices
(ns aineko.main
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str])
  (:import
   [java.net UnixDomainSocketAddress StandardProtocolFamily]
   [java.nio.channels ServerSocketChannel SocketChannel Channels]
   [java.nio ByteBuffer]))

;; State management

(defn now
  "Returns the current time as a java.time.Instant."
  []
  (java.time.Instant/now))

(defn seances-dir
  "Returns the path to the seances state directory."
  []
  (fs/path (fs/xdg-state-home) "aineko" "seances"))

(defn sockets-dir
  "Returns the path to the sockets directory in project .tmp."
  []
  (fs/path (fs/cwd) ".tmp" "aineko" "sockets"))

(defn socket-path
  "Returns the path to the socket file for a given seance ID."
  [seance-id sockets-dir-path]
  (fs/path sockets-dir-path (str seance-id ".socket")))

(defn seance-state-file
  "Returns the path to the state file for a given seance ID."
  [seance-id seances-dir-path]
  (fs/path seances-dir-path (str seance-id ".edn")))

(defn current-project-dir
  "Returns the absolute path of the current working directory as a string."
  []
  (str (fs/absolutize (fs/cwd))))

(defn read-seance-state
  "Reads the state for a given seance ID.
   Returns nil if the state file doesn't exist."
  [seance-id seances-dir-path]
  (let [state-file (seance-state-file seance-id seances-dir-path)]
    (when (fs/exists? state-file)
      (edn/read-string (slurp (str state-file))))))

(defn write-seance-state!
  "Writes the state for a given seance ID.
   Creates the state directory if it doesn't exist.
   Automatically adds :updated-at timestamp."
  [seance-id state seances-dir-path]
  (fs/create-dirs seances-dir-path)
  (let [state-with-timestamp (assoc state :updated-at (str (now)))
        state-file (seance-state-file seance-id seances-dir-path)]
    (spit (str state-file) (pr-str state-with-timestamp))))

;; Zellij session detection

(defn list-zellij-sessions
  "Returns a vector of active Zellij session names.
   Excludes exited sessions."
  []
  (try
    (let [result (shell {:out :string :continue true} "zellij list-sessions --no-formatting")
          output (:out result)]
      (if (zero? (:exit result))
        (->> (str/split-lines output)
             (map str/trim)
             (remove empty?)
             (remove #(str/includes? % "(EXITED"))
             vec)
        []))
    (catch Exception _
      [])))

(defn parse-session-line
  "Parses a zellij session line to extract session name.
   Returns nil if the line cannot be parsed."
  [line]
  (when-let [match (re-find #"^([^\s]+)" line)]
    (second match)))

(defn list-session-names
  "Returns a vector of parsed session names from active Zellij sessions.
   Parses out metadata like timestamps, returning just the session names."
  []
  (->> (list-zellij-sessions)
       (keep parse-session-line)
       vec))

;; Socket communication

(defn write-to-socket
  "Writes data string to a socket channel."
  [channel data]
  (let [bytes (.getBytes data "UTF-8")
        buffer (ByteBuffer/wrap bytes)]
    (while (.hasRemaining buffer)
      (.write channel buffer))))

(defn unix-socket-send
  "Connects to a Unix domain socket and sends data.
   Closes the connection after sending."
  [socket-path data]
  (let [address (UnixDomainSocketAddress/of socket-path)
        channel (SocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.connect channel address)
      (write-to-socket channel data)
      (finally
        (.close channel)))))

(defn send-desktop-notification
  "Sends a desktop notification using notify-send.
   Returns true if successful, false otherwise."
  [summary body]
  (try
    (let [result (shell {:continue true :out :string :err :string}
                        "notify-send"
                        "--app-name=Aineko"
                        "--urgency=normal"
                        summary
                        body)]
      (zero? (:exit result)))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Failed to send desktop notification: " (.getMessage e))))
      false)))

(defn handle-hook-event
  "Processes a hook event received via socket.
   Updates seance state with the event data."
  [seance-id event seances-dir-path]
  (try
    (let [state (read-seance-state seance-id seances-dir-path)
          hook-event-name (:hook_event_name event)

          updated-state (cond-> state
                          true
                          (assoc :last-message event)

                          (:transcript_path event)
                          (assoc :transcript-path (:transcript_path event))

                          (= hook-event-name "SessionStart")
                          (assoc :claude-session-id (:session_id event))

                          (= hook-event-name "SessionEnd")
                          (dissoc :claude-session-id))]
      (write-seance-state! seance-id updated-state seances-dir-path)

      ;; Send desktop notifications for permission prompts and stop events
      (let [seance-name (:name state)
            summary (str "Aineko: " (or seance-name seance-id))]
        (cond
          (and (= hook-event-name "Notification")
               (= (:notification_type event) "permission_prompt"))
          (let [message (:message event)
                body (or message "Claude needs your permission")]
            (send-desktop-notification summary body))

          (= hook-event-name "Stop")
          (send-desktop-notification summary "Claude session stopped and ready for input"))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Error processing hook event: " (.getMessage e)))))))

(defn socket-listener-loop
  "Main loop for the socket listener running in a thread.
   Listens on Unix domain socket and processes incoming events using handler-fn.
   Periodically checks if the Zellij session still exists.
   handler-fn should accept two arguments: seance-id and event-data
   list-sessions-fn returns list of active session names"
  [{:keys [seance-id session-name sock-path handler-fn list-sessions-fn timeout-ms]
    :or {timeout-ms 60000}}]
  (let [address (UnixDomainSocketAddress/of sock-path)
        server-channel (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (fs/create-dirs (fs/parent sock-path))
      (.bind server-channel address)
      (loop [accept-future nil]
        (let [accept-future (or accept-future (future (.accept server-channel)))]
          (if-some [client-channel (deref accept-future timeout-ms nil)]
            (do
              (try
                (let [event-data (slurp (Channels/newInputStream client-channel))]
                  (when-not (str/blank? event-data)
                    (handler-fn seance-id (edn/read-string event-data))))
                (catch Exception e
                  (binding [*out* *err*]
                    (println (str "Error handling connection: " (.getMessage e)))))
                (finally
                  (try
                    (.close client-channel)
                    (catch Exception _))))
              (recur nil))
              ;; Timeout reached, check if session still exists
            (let [session-exists? (some #(= session-name %) (list-sessions-fn))]
              (if session-exists?
                (recur accept-future)
                (binding [*out* *err*]
                  (println (str "Zellij session " session-name " no longer exists, stopping listener"))))))))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "Socket listener error: " (.getMessage e)))))
      (finally
        (try
          (.close server-channel)
          (catch Exception _))
        (fs/delete-if-exists sock-path)))))

(defn start-socket-listener
  "Starts a socket listener in a background thread for the given seance ID.
   Periodically checks if the Zellij session still exists.
   Returns a future that runs the listener.
   handler-fn should accept two arguments: seance-id and event-data"
  [seance-id session-name handler-fn]
  (let [sockets-dir-path (sockets-dir)
        sock-path (str (socket-path seance-id sockets-dir-path))]
    (socket-listener-loop
     {:seance-id seance-id
      :session-name session-name
      :sock-path sock-path
      :handler-fn handler-fn
      :list-sessions-fn list-session-names})))

;; Zellij integration

(defn generate-seance-id
  "Generates a short random alphanumeric ID for a seance.
   Returns an 6-character lowercase string using letters and numbers."
  []
  (let [chars "abcdefghijklmnopqrstuvwxyz0123456789"
        n (count chars)]
    (apply str (repeatedly 6 #(nth chars (rand-int n))))))

(defn parse-seance-from-session
  "Extracts seance information from a Zellij session name.
   Expected format: seance-name:SEANCE_ID
   Returns map with :name and :id, or nil if format doesn't match."
  [session-name]
  (when-let [[_ name id] (re-matches #"([^:]+):([a-z0-9]+)" (parse-session-line session-name))]
    {:name name
     :id id
     :session-name session-name}))

(defn create-zellij-session
  "Creates a new detached Zellij session with the given name.
   Accepts optional socket-path and seance-id to set as env vars."
  [session-name & {:keys [socket-path seance-id]}]
  (let [env (cond-> {}
              socket-path (assoc "AINEKO_SOCKET_PATH" socket-path)
              seance-id (assoc "AINEKO_SEANCE_ID" seance-id))]
    (if (seq env)
      (shell {:extra-env env} "zellij" "attach" session-name "--create-background")
      (shell "zellij" "attach" session-name "--create-background"))))

(defn attach-to-session
  "Attaches to an existing Zellij session.
   Spawns Zellij as a child process and waits for it to complete."
  [session-name]
  (let [result (shell {:continue true} "zellij" "attach" session-name)]
    (:exit result)))

;; Status detection

(defn seance-status
  "Determines the status of a seance from its state.
   Returns one of: :waiting, :idle, :working, :unknown"
  [state]
  (let [last-message (:last-message state)
        hook-event-name (:hook_event_name last-message)
        has-claude-session? (some? (:claude-session-id state))]
    (cond
      (nil? last-message)
      :unknown

      ;; If Notification event asking for permission
      (and (= hook-event-name "Notification")
           (= (:notification_type last-message) "permission_prompt"))
      :waiting

      (and (= hook-event-name "Notification")
           (= (:notification_type last-message) "idle_prompt"))
      :idle

      (#{"Stop" "SubagentStop" "SessionStart"} hook-event-name)
      :idle

      (not has-claude-session?)
      :idle

      :else
      :working)))

(defn- status-priority
  "Returns a numeric priority for a status (lower = higher priority).
   Used for sorting seances."
  [status]
  (case status
    :waiting 0
    :idle 1
    :working 2
    :unknown 3
    4))

(defn seance-last-update-time
  "Extracts the last update time from a seance state.
   Uses timestamp from last-message if available, otherwise created-at.
   Returns nil if no timestamp is available."
  [state]
  (or (:updated-at state)
      (:created-at state)))

(defn compare-seances
  "Comparator for sorting seances.
   Sort order: requires-permission > idle > working, then by most recent update."
  [seance-a seance-b]
  (let [status-a (:status seance-a)
        status-b (:status seance-b)
        priority-a (status-priority status-a)
        priority-b (status-priority status-b)]
    (if (= priority-a priority-b)
      ;; Same status, sort by most recent update (reverse chronological)
      (let [time-a (seance-last-update-time seance-a)
            time-b (seance-last-update-time seance-b)]
        (compare (or time-b "") (or time-a "")))
      ;; Different status, sort by priority
      (compare priority-a priority-b))))

;; Claude settings management

(def hook-events
  "Claude Code hook events that aineko subscribes to."
  ["Notification" "PreToolUse" "SessionEnd" "SessionStart" "Stop" "SubagentStop" "UserPromptSubmit"])

(defn claude-settings-path
  "Returns the path to Claude Code settings.json file."
  []
  (fs/path (fs/home) ".claude" "settings.json"))

(defn read-claude-settings
  "Reads Claude Code settings.json file.
   Returns parsed JSON as Clojure map, or empty map if file doesn't exist."
  [config-file]
  (if (fs/exists? config-file)
    (try
      (json/parse-string (slurp (str config-file)) true)
      (catch Exception e
        (binding [*out* *err*]
          (println (str "Error reading settings file: " (.getMessage e))))
        {}))
    {}))

(def claude-hook-config
  "Hook configuration for aineko handle command."
  {:hooks [{:type "command"
            :command "aineko handle"}]})

(defn claude-hook-enabled?
  "Checks if aineko hook is enabled for a given event.
   hooks - hooks configuration map from settings
   event-name - event name as string"
  [hooks event-name]
  (let [event-hooks (get hooks (keyword event-name) [])]
    (some #(= claude-hook-config %) event-hooks)))

(defn print-configured-hooks
  "Prints the status of configured hooks.
   hooks - hooks configuration map from settings"
  [hooks]
  (let [enabled-hooks (filter #(claude-hook-enabled? hooks %) hook-events)]
    (println (str "Aineko hooks (" (count enabled-hooks) "/" (count hook-events) " enabled):"))
    (doseq [event hook-events]
      (if (claude-hook-enabled? hooks event)
        (println (str "  ✓ " event))
        (println (str "  ✗ " event))))))

(defn merge-hooks
  "Merges aineko hooks into existing hooks configuration.
   Adds aineko hook to each event type if not already present.
   events - collection of event names to add hooks for"
  [existing-hooks events]
  (reduce
   (fn [hooks event]
     (let [event-key (keyword event)
           event-hooks (get hooks event-key [])
           updated-hooks (if (claude-hook-enabled? hooks event)
                           event-hooks
                           (conj (vec event-hooks) claude-hook-config))]
       (assoc hooks event-key updated-hooks)))
   (or existing-hooks {})
   events))

(defn update-settings-with-hooks
  "Pure function that updates settings with aineko hooks.
   Takes existing settings map and collection of event names.
   Returns updated settings map."
  [settings events]
  (update settings :hooks merge-hooks events))

(defn write-claude-settings!
  "Writes settings to Claude Code settings.json file.
   Creates parent directory if it doesn't exist."
  [settings config-file]
  (let [settings-dir (fs/parent config-file)
        pretty-printer (json/create-pretty-printer
                        (assoc json/default-pretty-print-options
                               :indent-arrays? true
                               :object-field-value-separator ": "))]
    (fs/create-dirs settings-dir)
    (spit (str config-file)
          (json/generate-string settings {:pretty pretty-printer}))))

(defn setup-hooks!
  "Configures Claude Code hooks for aineko.
   Backs up existing settings file to settings.json.backup before updating.
   Returns true if successful, false otherwise."
  [config-file]
  (let [backup-file (fs/path (str config-file ".backup"))
        existing-settings (read-claude-settings config-file)
        updated-settings (update-settings-with-hooks existing-settings hook-events)]
    (println (str "Settings file: " config-file))
    (when (fs/exists? config-file)
      (fs/move config-file backup-file)
      (println (str "Backup created: " backup-file)))
    (write-claude-settings! updated-settings config-file)))

;; CLI commands

(defn attach-with-listener
  "Attaches to a Zellij session with socket listener running.
   The listener continues running after detach and is managed by session-watchdog."
  [session-name seance-id]
  (let [seances-dir-path (seances-dir)
        handler (fn [seance-id event]
                  (handle-hook-event seance-id event seances-dir-path))]
    (future (start-socket-listener seance-id session-name handler))
    (println (str "Attaching to seance: " session-name))
    (attach-to-session session-name)))

(defn create-new-seance
  "Creates a new seance with state and Zellij session.
   Returns the created state map."
  [seance-name]
  (let [seances-dir-path (seances-dir)
        sockets-dir-path (sockets-dir)
        seance-id (generate-seance-id)
        session-name (str seance-name ":" seance-id)
        sock-path (str (socket-path seance-id sockets-dir-path))
        state {:id seance-id
               :name seance-name
               :session-name session-name
               :project-dir (current-project-dir)
               :created-at (str (now))
               :socket-path sock-path}]
    (write-seance-state! seance-id state seances-dir-path)
    (println (str "Creating seance: " session-name))
    (create-zellij-session session-name :socket-path sock-path :seance-id seance-id)
    state))

(defn list-active-seances
  "Returns a list of active seances with their state merged in.
   Each seance map contains :name, :id, :session-name, state fields, and :status.
   Seances are sorted by status priority and most recent update time."
  []
  (let [seances-dir-path (seances-dir)
        sessions (list-zellij-sessions)]
    (->> sessions
         (keep parse-seance-from-session)
         (map (fn [seance]
                (let [state (read-seance-state (:id seance) seances-dir-path)
                      merged (merge seance state)]
                  (assoc merged :status (seance-status merged)))))
         (sort compare-seances))))

(defn get-seance-name
  "Gets seance name from argument or current directory basename."
  [name-arg]
  (or name-arg (fs/file-name (fs/cwd))))

(defn cmd-new
  "Creates a new seance.
   Optional name argument, defaults to current directory basename."
  [& args]
  (let [name-arg (first args)
        seance-name (get-seance-name name-arg)
        state (create-new-seance seance-name)]
    (attach-with-listener (:session-name state) (:id state))))

(defn format-time
  "Formats UTC timestamp-str. Shows 'HH:mm' if today, 'M/d/yy, HH:mm' if different day,
  relative to the system's timezone. Optional now for testing."
  ([timestamp-str] (format-time timestamp-str nil))
  ([timestamp-str now]
   (when timestamp-str
     (let [dt (.atZone (java.time.Instant/parse timestamp-str) (java.time.ZoneId/systemDefault))
           today (if now (.toLocalDate (.toZonedDateTime now)) (java.time.LocalDate/now))
           formatter (if (.equals (.toLocalDate dt) today)
                       (java.time.format.DateTimeFormatter/ofLocalizedTime java.time.format.FormatStyle/SHORT)
                       (java.time.format.DateTimeFormatter/ofLocalizedDateTime java.time.format.FormatStyle/SHORT))]
       (.format dt formatter)))))

(defn status-indicator
  "Returns a visual indicator for a seance status."
  [status]
  (case status
    :waiting ">"
    :idle "○"
    :working "●"
    :unknown "?"
    "!"))

(defn- format-seance-line
  "Formats a seance into a display line for fzf with aligned columns.
   Format: [status] session-name | last-update-time
   Optional max-name-width parameter for column alignment."
  ([seance max-name-width]
   (let [name (:session-name seance)
         padded-name (format (str "%-" max-name-width "s") name)]
     (str "[" (status-indicator (:status seance)) "] "
          padded-name
          (when-let [timestamp (seance-last-update-time seance)]
            (str " | " (format-time timestamp)))))))

(defn format-seance-lines
  "Formats a collection of seances with aligned columns.
   Returns a vector of formatted strings."
  [seances]
  (let [max-name-width (apply max (map #(count (:session-name %)) seances))]
    (map #(format-seance-line % max-name-width) seances)))

(defn parse-formatted-seance-line
  "Parses seance information from either a plain ID or a formatted status line.
   Accepts:
   - Formatted status line: '[●] my-project:abc123de | 2025-11-17T10:00:00'
     -> {:seance-id 'abc123de', :seance-name 'my-project:abc123de'}"
  [line]
  (when-let [[_ name id] (re-find #"([^:\s]+:([a-z0-9]+))" line)]
    {:seance-id id
     :seance-name name}))

(defn cmd-open
  "Opens an existing seance for the current directory or creates a new one if none exists."
  []
  (let [current-dir (current-project-dir)
        seances (->> (list-active-seances)
                     (filter #(= (:project-dir %) current-dir)))]
    (if (empty? seances)
      (do
        (println (str "No existing seance for " current-dir ", creating new seance"))
        (cmd-new))
      (let [seance (first seances)]
        (attach-with-listener (:session-name seance) (:id seance))))))

(defn cmd-list
  "Lists all active seances without a picker."
  []
  (if-some [seances (seq (list-active-seances))]
    (doseq [line (format-seance-lines seances)]
      (println line))
    (println "No active seances")))

(defn cmd-select
  "Lists all active seances and allows selection via fzf with preview.
   Options:
   - :watch? - if true, restarts fzf every 5 seconds to show updated status"
  [& {:keys [watch?]}]
  (loop []
    (let [seances (list-active-seances)]
      (if (empty? seances)
        (if watch?
          (do
            (println "No active seances (refreshing in 5s, press Ctrl-C to exit)")
            (Thread/sleep 5000)
            (recur))
          (println "No active seances"))
        (let [formatted-lines (format-seance-lines seances)
              input (str/join "\n" formatted-lines)
              preview-cmd "aineko detail {}"
              prompt (if watch?
                       "Select a seance to attach (auto-refresh 5s): "
                       "Select a seance to attach: ")
              fzf-cmd (cond->> ["fzf"
                                (str "--prompt=" prompt)
                                "--height=60%"
                                "--preview-window=up:50%:wrap"
                                (str "--preview=" preview-cmd)]
                        ;; In watch mode, wrap fzf with timeout to auto-terminate after 5s
                        ;; --foreground allows fzf to maintain TTY access for keyboard input
                        watch? (into ["timeout" "--foreground" "5s"]))
              result (apply shell {:out :string :in input :continue true} fzf-cmd)
              selected (str/trim (:out result))
              exit-code (:exit result)]
          (cond
            ;; User made a selection - attach and exit
            (and (zero? exit-code) (not (str/blank? selected)))
            (let [{:keys [seance-name]} (parse-formatted-seance-line selected)]
              (attach-to-session seance-name))

            ;; Timeout occurred (exit code 124) in watch mode - restart
            (and watch? (= exit-code 124))
            (recur)

            ;; User cancelled (Ctrl-C, ESC) or error - exit
            :else
            (println "No seance selected")))))))

(defn cmd-setup
  "Configures Claude Code hooks for aineko."
  []
  (try
    (let [config-file (claude-settings-path)]
      (println "Setting up aineko hooks in Claude Code settings...")
      (setup-hooks! (str config-file))
      (println "\n✓ Hooks configured successfully!")
      (println "\nAineko will now receive events from Claude Code sessions.")
      (println)
      (print-configured-hooks (:hooks (read-claude-settings config-file)))
      (System/exit 0))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Error setting up hooks: " (.getMessage e))))
      (System/exit 1))))

(defn cmd-help
  "Prints help text."
  []
  (println "Aineko - manage coding agent sessions\n")
  (println "Usage: aineko [COMMAND] [OPTIONS]\n")
  (println "Commands:")
  (println "  (default)             Select and attach to a seance (interactive)")
  (println "  open, o               Open existing seance for current directory or create new one")
  (println "  new, n [NAME]         Start a new seance (defaults to current directory name)")
  (println "  list, l, ls           List all active seances and their status")
  (println "  watch, w              Select with auto-refresh every 5 seconds")
  (println)
  (println "  setup                 Configure Claude Code hooks for aineko")
  (println "  status                Print diagnostics information")
  (println "  detail SEANCE_ID      Show detailed information about a seance")
  (println "  help, h, --help, -h   Show this help message")
  (println)
  (println "Status indicators:")
  (doseq [status [:waiting :idle :working :unknown]]
    (println (str "  " (status-indicator status) " " (name status)))))

(defn cmd-status
  "Prints diagnostics information."
  []
  (let [config-file (claude-settings-path)]
    (println (str "Seances state directory: " (seances-dir)))
    (println)
    (println (str "Claude Code settings: " config-file))
    (println)
    (print-configured-hooks (:hooks (read-claude-settings config-file)))))

(defn format-content-item
  "Formats a single content item from an event.
   Returns a string representation."
  [item]
  (cond
    (string? item) item
    (map? item) (let [type (:type item)
                      text (:text item)]
                  (if text
                    (str type ": " text)
                    (str type)))
    :else (str item)))

(defn format-hook-event
  "Formats a hook event message for display.
   Returns a string with the event details.
   Handles different event structures:
   - Notification events with :message
   - Events with :content collection
   - Events with :text field"
  [event]
  (let [event-name (:hook_event_name event)
        notification-type (:notification_type event)
        message (:message event)
        content (:content event)
        text (:text event)
        timestamp (:timestamp event)]
    (str "Last Event: " event-name
         (when notification-type
           (str " (" notification-type ")"))
         "\n"
           ;; Notification events use :message
         (when message
           (str "Message: " message "\n"))
           ;; Other events may have :content collection
         (when (and content (coll? content))
           (str "Content:\n"
                (str/join "\n" (map #(str "  " (format-content-item %)) content))
                "\n"))
           ;; Some events have :text field
         (when text
           (str "Text: " text "\n"))
         (when timestamp
           (str "Time: " (format-time timestamp) "\n")))))

(defn cmd-detail
  "Displays detailed information about a seance.
   Accepts either a seance ID or a formatted status line as argument."
  [& args]
  (let [seance-arg (first args)
        seances-dir-path (seances-dir)
        state (or (read-seance-state seance-arg seances-dir-path)
                  (read-seance-state (:seance-id (parse-formatted-seance-line seance-arg)) seances-dir-path))]
    (if-not state
      (do
        (println (str "Error: seance not found: " seance-arg))
        (System/exit 1))
      (let [status (seance-status state)
            last-message (:last-message state)]
        (println (str "Seance: " (:name state)))
        (println (str "ID: " (:id state)))
        (println (str "Session: " (:session-name state)))
        (println (str "Status: " (name status) " " (status-indicator status)))
        (when-let [project-dir (:project-dir state)]
          (println (str "Project: " project-dir)))
        (when-let [created-at (:created-at state)]
          (println (str "Created: " (format-time created-at))))
        (when-let [updated-at (:updated-at state)]
          (println (str "Updated: " (format-time updated-at))))
        (when-let [claude-session-id (:claude-session-id state)]
          (println (str "Claude Session: " claude-session-id)))
        (when-let [transcript-path (:transcript-path state)]
          (println (str "Transcript: " transcript-path)))
        (when last-message
          (println)
          (println (format-hook-event last-message)))))))

(defn cmd-handle
  "Handles Claude Code hook events by forwarding stdin to the socket.
   Called by Claude hooks with AINEKO_SOCKET_PATH env var set."
  []
  (let [socket-path (System/getenv "AINEKO_SOCKET_PATH")]
    (if socket-path
      (try
        (let [input (slurp *in*)
              event-data (pr-str (json/parse-string input true))]
          (unix-socket-send socket-path event-data))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "Error forwarding hook event: " (.getMessage e))))
          (System/exit 1)))
      (do
        (binding [*out* *err*]
          (println "Error: AINEKO_SOCKET_PATH environment variable not set"))
        (System/exit 1)))))

(defn -main [& args]
  (let [[command & rest-args] args]
    (case command
      (nil) (cmd-select) ; Default command (no args)
      ("detail") (apply cmd-detail rest-args)
      ("handle") (cmd-handle)
      ("help" "--help" "-h" "h") (cmd-help)
      ("list" "l" "ls") (cmd-list)
      ("new" "n") (apply cmd-new rest-args)
      ("open" "o") (cmd-open)
      ("setup") (cmd-setup)
      ("status") (cmd-status)
      ("watch" "w") (cmd-select :watch? true)
      (do
        (println (str "Unknown command: " command))
        (cmd-help)
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  (->> (list-zellij-sessions)
       (map parse-seance-from-session))

  (parse-seance-from-session "aineko:x3k9p2m7 [Created 3m 17s ago]"))
