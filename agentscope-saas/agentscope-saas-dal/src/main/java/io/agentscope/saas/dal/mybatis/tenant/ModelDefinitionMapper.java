/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.model.ModelDefinitionEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant MyBatis mapper for managed model definitions. */
public interface ModelDefinitionMapper {

    String COLUMNS =
            """
            SELECT id, org_id, model_id, display_name, provider_type, base_url,
                   api_key_ciphertext, model_name, context_window_tokens, max_output_tokens,
                   safety_margin_tokens, enabled, default_model, version, created_at, updated_at
              FROM model_definitions
            """;

    @Select(COLUMNS + " WHERE org_id = #{orgId} ORDER BY model_id ASC")
    List<ModelDefinitionEntity> findByOrg(@Param("orgId") UUID orgId);

    @Select(COLUMNS + " WHERE org_id = #{orgId} AND model_id = #{modelId} LIMIT 1")
    List<ModelDefinitionEntity> findByNaturalId(
            @Param("orgId") UUID orgId, @Param("modelId") String modelId);

    @Insert(
            """
            INSERT INTO model_definitions (
                id, org_id, model_id, display_name, provider_type, base_url,
                api_key_ciphertext, model_name, context_window_tokens, max_output_tokens,
                safety_margin_tokens, enabled, default_model, version, updated_at)
            VALUES (
                #{id}, #{orgId}, #{modelId}, #{displayName}, #{providerType}, #{baseUrl},
                #{apiKeyCiphertext}, #{modelName}, #{contextWindowTokens}, #{maxOutputTokens},
                #{safetyMarginTokens}, #{enabled}, #{defaultModel}, #{version}, #{updatedAt})
            """)
    int insert(ModelDefinitionEntity definition);

    @Update(
            """
            UPDATE model_definitions
               SET display_name = #{displayName}, provider_type = #{providerType},
                   base_url = #{baseUrl}, api_key_ciphertext = #{apiKeyCiphertext},
                   model_name = #{modelName}, context_window_tokens = #{contextWindowTokens},
                   max_output_tokens = #{maxOutputTokens},
                   safety_margin_tokens = #{safetyMarginTokens}, enabled = #{enabled},
                   default_model = #{defaultModel}, version = version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND org_id = #{orgId} AND version = #{version}
            """)
    int update(ModelDefinitionEntity definition);

    @Update(
            """
            UPDATE model_definitions
               SET default_model = FALSE, version = version + 1, updated_at = CURRENT_TIMESTAMP
             WHERE org_id = #{orgId} AND default_model = TRUE AND id <> #{exceptId}
            """)
    int clearDefault(@Param("orgId") UUID orgId, @Param("exceptId") UUID exceptId);

    @Delete("DELETE FROM model_definitions WHERE org_id = #{orgId} AND model_id = #{modelId}")
    int delete(@Param("orgId") UUID orgId, @Param("modelId") String modelId);
}
