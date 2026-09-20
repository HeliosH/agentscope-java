package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StepSnapshotTest {
    @Test
    void canonicalHashIgnoresMapOrderButNotSchemaChanges() {
        var first = new LinkedHashMap<String, Object>();
        first.put("type", "object");
        first.put("properties", Map.of("q", Map.of("type", "string")));
        var second = new LinkedHashMap<String, Object>();
        second.put("properties", Map.of("q", Map.of("type", "string")));
        second.put("type", "object");
        assertEquals(StepSnapshot.fingerprint(first), StepSnapshot.fingerprint(second));
        second.put("properties", Map.of("q", Map.of("type", "number")));
        assertNotEquals(StepSnapshot.fingerprint(first), StepSnapshot.fingerprint(second));
    }

    @Test
    void modelStartEventRoundTripsSnapshotAndReadsLegacyPayload() {
        var snapshot =
                new StepSnapshot(
                        "step-1",
                        1,
                        new StepSnapshot.Identity("run", "agent-run", "task", "attempt"),
                        "model",
                        "route",
                        "route-version",
                        "sandbox-1",
                        "environment-version-1",
                        4096,
                        "registry",
                        "schema",
                        "prompt",
                        "options",
                        "policy",
                        "extensions",
                        List.of("lookup"));
        var codec = JsonUtils.getJsonCodec();
        var decoded =
                codec.fromJson(
                        codec.toJson(new ModelCallStartEvent("reply", snapshot)),
                        ModelCallStartEvent.class);
        assertEquals(snapshot, decoded.getStepSnapshot());
        assertEquals("reply", decoded.getReplyId());
        var legacy =
                codec.fromJson(
                        "{\"type\":\"MODEL_CALL_START\",\"replyId\":\"legacy\"}",
                        ModelCallStartEvent.class);
        assertEquals("legacy", legacy.getReplyId());
        assertNull(legacy.getStepSnapshot());
    }
}
