package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenCounterUtilTest {
    private static ImageBlock image() {
        return ImageBlock.builder()
                .source(URLSource.builder().url("https://example.invalid/image.png").build())
                .build();
    }

    @Test
    void incompleteRawArgumentsCannotHideLargerParsedInput() {
        var use =
                io.agentscope.core.message.ToolUseBlock.builder()
                        .id("call")
                        .name("tool")
                        .content("{}")
                        .input(java.util.Map.of("value", "x".repeat(5000)))
                        .build();
        var message = Msg.builder().role(MsgRole.ASSISTANT).content(use).build();
        assertTrue(TokenCounterUtil.calculateToken(List.of(message)) >= 2000);
    }

    @Test
    void unknownMediaCannotMasqueradeAsFiveTokens() {
        var message = Msg.builder().role(MsgRole.USER).content(image()).build();
        assertThrows(
                ContextWindowExceededException.class,
                () -> TokenCounterUtil.calculateToken(List.of(message)));
    }

    @Test
    void providerMediaEstimateIncludesNestedToolResults() {
        var message = Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of(image())).build();
        int estimate = TokenCounterUtil.calculateToken(List.of(message), List.of(), block -> 1500);
        assertTrue(estimate >= 1500);
        assertTrue(estimate < 1550);
        assertThrows(
                IllegalArgumentException.class,
                () -> TokenCounterUtil.calculateToken(List.of(message), List.of(), block -> -1));
    }

    @Test
    void thinkingTextIsCounted() {
        var message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("x".repeat(10000)).build())
                        .build();
        assertTrue(TokenCounterUtil.calculateToken(List.of(message)) >= 4000);
    }

    @Test
    void repeatedLargeMessagesSaturateInsteadOfWrappingNegative() {
        var message = Msg.builder().role(MsgRole.USER).textContent("x".repeat(2000000)).build();
        assertEquals(
                Integer.MAX_VALUE,
                TokenCounterUtil.calculateToken(Collections.nCopies(3000, message)));
    }
}
