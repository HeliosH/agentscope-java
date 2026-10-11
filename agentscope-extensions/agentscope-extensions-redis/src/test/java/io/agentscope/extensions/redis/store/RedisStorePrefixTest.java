/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.extensions.redis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

class RedisStorePrefixTest {
    @Test
    void listsOnlyPrefixKeysWithoutReadingItemBodies() {
        var jedis = mock(UnifiedJedis.class);
        when(jedis.zrangeByLex(
                        anyString(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of("/skills/test/SKILL.md"));
        var store = new RedisStore(jedis);
        assertEquals(
                List.of("/skills/test/SKILL.md"),
                store.listKeys(List.of("user"), "/skills/", 100, 5));
        verify(jedis).zrangeByLex("agentscope:store:idx:user", "[/skills/", "(/skills0", 5, 100);
        verifyNoMoreInteractions(jedis);
        assertThrows(
                IllegalArgumentException.class, () -> store.listKeys(List.of("user"), "", 100, 0));
    }
}
