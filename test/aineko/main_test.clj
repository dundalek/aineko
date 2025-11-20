(ns aineko.main-test
  (:require
   [babashka.fs :as fs]
   [aineko.main :as main]
   [lazytest.core :refer [defdescribe describe expect it]]))

(def test-seance-id "test-seance-123")
(def test-state {:name "test-seance"
                 :project-dir "/path/to/project"
                 :created-at "2025-11-17T13:00:00Z"})

(defdescribe state-tests
  (fs/with-temp-dir [test-seances-dir {}]
    (it "seance-state-file returns correct path"
        (let [file (main/seance-state-file test-seance-id test-seances-dir)
              expected-path (str test-seances-dir "/" test-seance-id ".edn")]
          (expect (= expected-path (str file)))))

    (it "write and read seance state roundtrip"
        (let [test-id (str "test-roundtrip-" (random-uuid))
              mock-time (java.time.Instant/parse "2025-11-19T12:34:56Z")]
          (with-redefs [main/now (constantly mock-time)]
            (main/write-seance-state! test-id test-state test-seances-dir)
            (let [read-state (main/read-seance-state test-id test-seances-dir)]
              (expect (= {:name "test-seance"
                          :project-dir "/path/to/project"
                          :created-at "2025-11-17T13:00:00Z"
                          :updated-at "2025-11-19T12:34:56Z"}
                         read-state))))))

    (it "read-seance-state returns nil for non-existent state"
        (let [non-existent-id (str "non-existent-" (random-uuid))]
          (expect (nil? (main/read-seance-state non-existent-id test-seances-dir)))))))

(defdescribe zellij-tests
  (it "generate-seance-id creates 8-char lowercase alphanumeric ID"
      (let [rng (java.util.Random. 42)
            rand-int-mock (fn [n] (.nextInt rng n))]
        (with-redefs [rand-int rand-int-mock]
          (expect (= "0daisz" (main/generate-seance-id)))
          (expect (= "fch3c0" (main/generate-seance-id))))))

  (it "parse-session-line extracts session name from zellij output"
      (expect (= "test:def-456"
                 (main/parse-session-line "test:def-456 [Created 2h ago]"))))

  (it "parse-seance-from-session extracts seance info"
      (expect (= {:name "my-project" :id "x3k9p2m7" :session-name "my-project:x3k9p2m7"}
                 (main/parse-seance-from-session "my-project:x3k9p2m7"))))

  (it "parse-seance-from-session returns nil for invalid format"
      (expect (nil? (main/parse-seance-from-session "invalid-session-name")))
      (expect (nil? (main/parse-seance-from-session "no-id:ABC123")))
      (expect (nil? (main/parse-seance-from-session "has-special:abc-123"))))

  (it "list-zellij-sessions returns list of session names"
      (let [sessions (main/list-zellij-sessions)]
        (expect (vector? sessions))
        (expect (every? string? sessions)))))

(defdescribe seance-formatting-tests
  (it "status-indicator returns correct symbols"
      (expect (= ">" (main/status-indicator :waiting)))
      (expect (= "○" (main/status-indicator :idle)))
      (expect (= "●" (main/status-indicator :working)))
      (expect (= "?" (main/status-indicator :unknown)))
      (expect (= "!" (main/status-indicator :some-other-status))))

  (it "format-seance-lines includes status indicator and session info"
      (let [seance {:session-name "test:abc-123"
                    :project-dir "/home/user/project"
                    :updated-at "2025-11-17T10:00:00Z"
                    :status :working}]
        (expect (= ["[●] test:abc-123 | 11/17/25, 11:00 AM"] (main/format-seance-lines [seance])))))

  (it "format-seance-lines handles missing optional fields"
      (let [seance {:session-name "test:abc-123" :status :idle}
            formatted (main/format-seance-lines [seance])]
        (expect (= ["[○] test:abc-123"] formatted))))

  (it "round-trip: format-seance-lines to parse-formatted-seance-line preserves seance ID and name"
      (let [seance {:session-name "my-project:abc123de"
                    :updated-at "2025-11-17T10:00:00Z"
                    :status :working}
            formatted (first (main/format-seance-lines [seance]))
            parsed (main/parse-formatted-seance-line formatted)]
        (expect (= {:seance-id "abc123de"
                    :seance-name "my-project:abc123de"}
                   parsed))))

  (it "round-trip: format-seance-lines without timestamp to parse-formatted-seance-line"
      (let [seance {:session-name "test:xyz789ab"
                    :status :idle}
            formatted (first (main/format-seance-lines [seance]))
            parsed (main/parse-formatted-seance-line formatted)]
        (expect (= {:seance-id "xyz789ab"
                    :seance-name "test:xyz789ab"}
                   parsed))))

  (it "format-seance-lines aligns columns for multiple seances"
      (let [seances [{:session-name "short:abc123de" :status :idle}
                     {:session-name "longer-name:xyz789ab" :status :working}]
            formatted (main/format-seance-lines seances)
            expected ["[○] short:abc123de      "
                      "[●] longer-name:xyz789ab"]]
        (expect (= expected formatted)))))

(defdescribe hook-event-tests
  (fs/with-temp-dir [test-seances-dir {}]
    (it "handle-hook-event stores last-message"
        (let [test-id (str "test-hook-msg-" (random-uuid))
              event {:hook_event_name "Notification"
                     :message "Test message"
                     :cwd "/test/path"}]
          (try
            (main/write-seance-state! test-id {:name "test"} test-seances-dir)
            (main/handle-hook-event test-id event test-seances-dir)
            (let [state (main/read-seance-state test-id test-seances-dir)]
              (expect (= event (:last-message state))))
            (finally
              (fs/delete-if-exists (main/seance-state-file test-id test-seances-dir))))))

    (it "handle-hook-event processes permission_prompt notification"
        (let [test-id (str "test-hook-permission-" (random-uuid))
              event {:hook_event_name "Notification"
                     :notification_type "permission_prompt"
                     :message "Claude needs your permission to use Write"
                     :cwd "/test/path"}]
          (try
            (main/write-seance-state! test-id {:name "test-seance"} test-seances-dir)
            (main/handle-hook-event test-id event test-seances-dir)
            (let [state (main/read-seance-state test-id test-seances-dir)]
              (expect (= event (:last-message state))))
            (finally
              (fs/delete-if-exists (main/seance-state-file test-id test-seances-dir))))))

    (it "handle-hook-event processes Stop notification"
        (let [test-id (str "test-hook-stop-notif-" (random-uuid))
              event {:hook_event_name "Stop"
                     :cwd "/test/path"}]
          (try
            (main/write-seance-state! test-id {:name "test-seance"} test-seances-dir)
            (main/handle-hook-event test-id event test-seances-dir)
            (let [state (main/read-seance-state test-id test-seances-dir)]
              (expect (= event (:last-message state))))
            (finally
              (fs/delete-if-exists (main/seance-state-file test-id test-seances-dir))))))

    (it "handle-hook-event stores transcript-path"
        (let [test-id (str "test-hook-transcript-" (random-uuid))
              transcript-path "/home/user/.claude/projects/test.jsonl"
              event {:hook_event_name "SessionStart"
                     :session_id "abc-123"
                     :transcript_path transcript-path
                     :cwd "/test/path"}]
          (try
            (main/write-seance-state! test-id {:name "test"} test-seances-dir)
            (main/handle-hook-event test-id event test-seances-dir)
            (let [state (main/read-seance-state test-id test-seances-dir)]
              (expect (= transcript-path (:transcript-path state))))
            (finally
              (fs/delete-if-exists (main/seance-state-file test-id test-seances-dir))))))

    (it "handle-hook-event stores claude-session-id on SessionStart"
        (let [test-id (str "test-hook-start-" (random-uuid))
              session-id "test-session-abc-123"
              event {:hook_event_name "SessionStart"
                     :session_id session-id
                     :transcript_path "/path/to/transcript.jsonl"
                     :cwd "/test/path"}]
          (try
            (main/write-seance-state! test-id {:name "test"} test-seances-dir)
            (main/handle-hook-event test-id event test-seances-dir)
            (let [state (main/read-seance-state test-id test-seances-dir)]
              (expect (= session-id (:claude-session-id state))))
            (finally
              (fs/delete-if-exists (main/seance-state-file test-id test-seances-dir))))))

    (it "handle-hook-event removes claude-session-id on SessionEnd"
        (let [test-id (str "test-hook-stop-" (random-uuid))
              initial-state {:name "test"
                             :claude-session-id "existing-session-123"}
              stop-event {:hook_event_name "SessionEnd"
                          :cwd "/test/path"}]
          (try
            (main/write-seance-state! test-id initial-state test-seances-dir)
            (main/handle-hook-event test-id stop-event test-seances-dir)
            (let [state (main/read-seance-state test-id test-seances-dir)]
              (expect (nil? (:claude-session-id state)))
              (expect (= stop-event (:last-message state))))
            (finally
              (fs/delete-if-exists (main/seance-state-file test-id test-seances-dir))))))

    (it "handle-hook-event preserves other state fields"
        (let [test-id (str "test-hook-preserve-" (random-uuid))
              initial-state {:name "test-seance"
                             :project-dir "/home/user/project"
                             :created-at "2025-11-17T10:00:00Z"
                             :socket-path "/tmp/test.socket"}
              event {:hook_event_name "Notification"
                     :message "Test"}
              mock-time (java.time.Instant/parse "2025-11-19T12:34:56Z")]
          (with-redefs [main/now (constantly mock-time)]
            (try
              (main/write-seance-state! test-id initial-state test-seances-dir)
              (main/handle-hook-event test-id event test-seances-dir)
              (expect (= {:name "test-seance"
                          :project-dir "/home/user/project"
                          :created-at "2025-11-17T10:00:00Z"
                          :socket-path "/tmp/test.socket"
                          :updated-at "2025-11-19T12:34:56Z"
                          :last-message {:hook_event_name "Notification" :message "Test"}}
                         (main/read-seance-state test-id test-seances-dir)))
              (finally
                (fs/delete-if-exists (main/seance-state-file test-id test-seances-dir)))))))))

(defdescribe sorting-tests
  (it "compare-seances sorts by status priority first"
      (let [seance-working {:status :working :updated-at "2025-11-17T10:00:00Z"}
            seance-idle {:status :idle :updated-at "2025-11-17T09:00:00Z"}
            seance-permission {:status :waiting :updated-at "2025-11-17T08:00:00Z"}
            seance-unknown {:status :unknown :updated-at "2025-11-17T08:00:00Z"}
            seances #{seance-working seance-idle seance-permission seance-unknown}
            sorted (sort main/compare-seances seances)
            expected [seance-permission seance-idle seance-working seance-unknown]]
        (expect (= expected sorted))))

  (it "compare-seances sorts by timestamp within same status"
      (let [seance-recent {:status :idle :updated-at "2025-11-17T12:00:00Z"}
            seance-older {:status :idle :updated-at "2025-11-17T10:00:00Z"}
            seances #{seance-recent seance-older}
            sorted (sort main/compare-seances seances)
            expected [seance-recent seance-older]]
        (expect (= expected sorted))))

  (it "compare-seances uses updated-at timestamp for sorting"
      (let [seance-a {:status :idle
                      :created-at "2025-11-17T10:00:00Z"
                      :updated-at "2025-11-17T14:00:00Z"}
            seance-b {:status :idle
                      :created-at "2025-11-17T13:00:00Z"
                      :updated-at "2025-11-17T13:00:00Z"}
            seances #{seance-a seance-b}
            sorted (sort main/compare-seances seances)
            expected [seance-a seance-b]]
        (expect (= expected sorted)))))

(defdescribe status-detection-tests
  (it "seance-status returns :waiting for permission notification"
      (let [state {:last-message {:hook_event_name "Notification"
                                  :notification_type "permission_prompt"
                                  :message "Claude needs your permission to use Write"}
                   :claude-session-id "active-session"}]
        (expect (= :waiting (main/seance-status state)))))

  (it "seance-status returns :idle for idle_prompt notification"
      (let [state {:last-message {:hook_event_name "Notification"
                                  :notification_type "idle_prompt"
                                  :message "Claude is idle"}
                   :claude-session-id "active-session"}]
        (expect (= :idle (main/seance-status state)))))

  (it "seance-status returns :idle when no claude-session-id"
      (let [state {:last-message {:hook_event_name "Notification"
                                  :message "Some message"}}]
        (expect (= :idle (main/seance-status state)))))

  (it "seance-status returns :unknown when last-message is missing"
      (let [state {:claude-session-id "active-session-123"}]
        (expect (= :unknown (main/seance-status state)))))

  (it "seance-status returns :working when claude-session-id exists"
      (let [state {:claude-session-id "active-session-123"
                   :last-message {:hook_event_name "ToolUse"
                                  :message "Using tool"}}]
        (expect (= :working (main/seance-status state)))))

  (it "seance-status returns :unknown for empty state"
      (let [state {}]
        (expect (= :unknown (main/seance-status state)))))

  (it "seance-status returns :idle when session stopped"
      (let [state {:last-message {:hook_event_name "Stop"}}]
        (expect (= :idle (main/seance-status state)))))

  (it "seance-status returns :idle for SessionStart event"
      (let [state {:claude-session-id "new-session-123"
                   :last-message {:hook_event_name "SessionStart"
                                  :session_id "new-session-123"}}]
        (expect (= :idle (main/seance-status state))))))

(defdescribe claude-settings-tests
  (it "update-settings-with-hooks adds hooks to empty settings"
      (let [events ["Event1" "Event2"]
            settings {}
            expected {:hooks {:Event1 [main/claude-hook-config]
                              :Event2 [main/claude-hook-config]}}
            result (main/update-settings-with-hooks settings events)]
        (expect (= expected result))))

  (it "update-settings-with-hooks preserves existing settings"
      (let [events ["Event1"]
            settings {:some-setting "value"
                      :another-setting 42}
            expected {:some-setting "value"
                      :another-setting 42
                      :hooks {:Event1 [main/claude-hook-config]}}
            result (main/update-settings-with-hooks settings events)]
        (expect (= expected result))))

  (it "update-settings-with-hooks preserves existing hooks"
      (let [events ["Event1" "Event2"]
            other-hook {:hooks [{:type "command" :command "other-command"}]}
            settings {:hooks {:Event1 [other-hook]}}
            expected {:hooks {:Event1 [other-hook main/claude-hook-config]
                              :Event2 [main/claude-hook-config]}}
            result (main/update-settings-with-hooks settings events)]
        (expect (= expected result))))

  (it "update-settings-with-hooks does not duplicate aineko hook"
      (let [events ["Event1" "Event2"]
            settings {:hooks {:Event1 [main/claude-hook-config]
                              :Event2 [main/claude-hook-config]}}
            expected settings
            result (main/update-settings-with-hooks settings events)]
        (expect (= expected result))))

  (describe "update-settings-with-hooks works with real hook-events"
            (fs/with-temp-dir [temp-dir {}]
              (let [config-file (fs/path temp-dir "settings.json")
                    settings {}
                    result (main/update-settings-with-hooks settings main/hook-events)
                    expected {:hooks {:Notification [main/claude-hook-config]
                                      :SessionStart [main/claude-hook-config]
                                      :SessionEnd [main/claude-hook-config]
                                      :Stop [main/claude-hook-config]
                                      :PreToolUse [main/claude-hook-config]
                                      :UserPromptSubmit [main/claude-hook-config]
                                      :SubagentStop [main/claude-hook-config]}}]
                (it "sets real event hooks"
                    (expect (= expected result)))
                (it "setups hooks when there is no settings file"
                    (main/setup-hooks! (str config-file))
                    (expect (= expected (main/read-claude-settings config-file))))
                (it "setups hooks updating existing settings file"
                    (main/setup-hooks! (str config-file))
                    (expect (= expected (main/read-claude-settings config-file))))))))

(defdescribe socket-communication-tests
  (fs/with-temp-dir [test-sockets-dir {:prefix ""}]
    (it "socket listener handles two messages with custom handler"
        (let [test-id (str "test-socket-" (random-uuid))
              session-name (str "test-session:" test-id)
              sock-path (str (main/socket-path test-id test-sockets-dir))
              messages (atom [])
              handler-fn (fn [seance-id event]
                           (swap! messages conj {:seance-id seance-id :event event}))
              ;; Mock list-sessions-fn that always returns the session as active
              list-sessions-fn (constantly [session-name])
              event1 {:hook_event_name "Notification" :message "First message"}
              event2 {:hook_event_name "Stop" :message "Second message"}
              listener-future (future
                                (main/socket-listener-loop
                                 {:seance-id test-id
                                  :session-name session-name
                                  :sock-path sock-path
                                  :handler-fn handler-fn
                                  :list-sessions-fn list-sessions-fn}))]

          ;; Give the socket time to start listening
          (Thread/sleep 100)

          (main/unix-socket-send sock-path (pr-str event1))
          (Thread/sleep 50)

          (main/unix-socket-send sock-path (pr-str event2))
          (Thread/sleep 50)

          (expect (= [{:seance-id test-id :event event1}
                      {:seance-id test-id :event event2}]
                     @messages))

          (future-cancel listener-future)))))

