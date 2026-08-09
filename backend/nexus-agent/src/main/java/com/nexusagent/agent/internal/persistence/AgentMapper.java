package com.nexusagent.agent.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentMapper {

    @Select("""
            SELECT EXISTS
            (
                SELECT 1
                FROM agents
                WHERE tenant_id = #{tenantId}
                  AND code = #{code}
            )
            """)
    boolean existsByTenantIdAndCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    @Insert("""
            INSERT INTO agents
            (
                id,
                tenant_id,
                code,
                name,
                description,
                system_prompt,
                model_provider,
                model_name,
                model_config,
                status,
                created_by_user_id,
                version
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{code},
                #{name},
                #{description},
                #{systemPrompt},
                #{modelProvider},
                #{modelName},
                #{modelConfigJson,jdbcType=VARCHAR},
                #{status},
                #{createdByUserId},
                #{version}
            )
            """)
    int insert(AgentRow row);
}