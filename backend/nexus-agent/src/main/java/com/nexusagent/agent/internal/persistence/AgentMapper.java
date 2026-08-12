package com.nexusagent.agent.internal.persistence;

import com.nexusagent.agent.domain.AgentStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

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

    @Select("""
            SELECT
                id,
                tenant_id,
                code,
                name,
                description,
                system_prompt,
                model_provider,
                model_name,
                CAST(model_config AS CHAR)
                    AS model_config_json,
                status,
                created_by_user_id,
                version,
                created_at,
                updated_at
            FROM agents
            WHERE tenant_id = #{tenantId}
              AND code = #{code}
            LIMIT 1
            """)
    Optional<AgentDetailRow>
    findDetailByTenantIdAndCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    @Select("""
            SELECT
                id,
                tenant_id,
                code,
                status,
                version,
                updated_at
            FROM agents
            WHERE tenant_id = #{tenantId}
              AND code = #{code}
            LIMIT 1
            """)
    Optional<AgentStatusRow>
    findStatusByTenantIdAndCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    @Select("""
        SELECT
            id,
            tenant_id,
            code,
            status
        FROM agents
        WHERE tenant_id = #{tenantId}
          AND code = #{code}
          AND status = #{status}
        LIMIT 1
        """)
    Optional<ActiveAgentRow>
    findReferenceByTenantIdAndCodeAndStatus(
            @Param("tenantId") long tenantId,
            @Param("code") String code,
            @Param("status") AgentStatus status
    );

    @Update("""
            UPDATE agents
            SET status = #{targetStatus},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE tenant_id = #{tenantId}
              AND code = #{code}
              AND status = #{currentStatus}
              AND version = #{expectedVersion}
            """)
    int updateStatus(
            @Param("tenantId") long tenantId,
            @Param("code") String code,
            @Param("currentStatus")
            AgentStatus currentStatus,
            @Param("targetStatus")
            AgentStatus targetStatus,
            @Param("expectedVersion")
            int expectedVersion
    );

    @Select("""
        SELECT
            id,
            tenant_id,
            code,
            system_prompt,
            model_provider,
            model_name,
            CAST(model_config AS CHAR)
                AS model_config_json,
            status
        FROM agents
        WHERE id = #{agentId}
          AND tenant_id = #{tenantId}
          AND status = 'ACTIVE'
        LIMIT 1
        """)
    Optional<ActiveAgentRuntimeRow>
    findActiveRuntimeByTenantIdAndId(
            @Param("tenantId") long tenantId,
            @Param("agentId") long agentId
    );
}