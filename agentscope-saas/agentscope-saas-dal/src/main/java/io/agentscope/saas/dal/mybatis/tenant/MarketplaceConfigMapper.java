/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.model.MarketplaceEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant mapper for internal Skills and MCP marketplace declarations. */
public interface MarketplaceConfigMapper {

    String COLUMNS =
            """
            SELECT id, org_id, marketplace_id, type, properties, created_at, updated_at
              FROM marketplaces
            """;

    @Select(COLUMNS + " WHERE org_id = #{orgId} ORDER BY id ASC")
    List<MarketplaceEntity> findByOrg(@Param("orgId") UUID orgId);

    @Select(COLUMNS + " WHERE org_id = #{orgId} AND marketplace_id = #{marketplaceId} LIMIT 1")
    List<MarketplaceEntity> findByNaturalId(
            @Param("orgId") UUID orgId, @Param("marketplaceId") String marketplaceId);

    @Insert(
            """
            INSERT INTO marketplaces (id, org_id, marketplace_id, type, properties, updated_at)
            VALUES (#{id}, #{orgId}, #{marketplaceId}, #{type},
                    #{properties,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                    #{updatedAt})
            """)
    int insert(MarketplaceEntity marketplace);

    @Update(
            """
            UPDATE marketplaces
               SET marketplace_id = #{marketplaceId}, type = #{type},
                   properties = #{properties,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND org_id = #{orgId}
            """)
    int update(MarketplaceEntity marketplace);

    @Delete(
            """
            DELETE FROM marketplaces
             WHERE org_id = #{orgId} AND marketplace_id = #{marketplaceId}
            """)
    int delete(@Param("orgId") UUID orgId, @Param("marketplaceId") String marketplaceId);
}
