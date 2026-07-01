/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.app.sandbox;

/** Best-effort backend resource termination for admin recovery workflows. */
public interface SandboxBackendTerminator {

    TerminationResult terminate(String sandboxType, String externalId);

    static SandboxBackendTerminator unsupported() {
        return (sandboxType, externalId) ->
                TerminationResult.unsupported("no backend terminator configured");
    }

    record TerminationResult(String status, boolean attempted, boolean succeeded, String message) {

        public static TerminationResult skipped(String message) {
            return new TerminationResult("skipped", false, false, message);
        }

        public static TerminationResult noExternalId() {
            return new TerminationResult("no_external_id", false, false, "externalId is missing");
        }

        public static TerminationResult unsupported(String message) {
            return new TerminationResult("unsupported", false, false, message);
        }

        public static TerminationResult success() {
            return new TerminationResult("succeeded", true, true, null);
        }

        public static TerminationResult failed(String message) {
            return new TerminationResult("failed", true, false, message);
        }
    }
}
