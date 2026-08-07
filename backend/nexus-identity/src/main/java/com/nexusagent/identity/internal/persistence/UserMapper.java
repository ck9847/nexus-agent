package com.nexusagent.identity.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Insert("""
            INSERT INTO users
            (
                id,
                tenant_id,
                username,
                email,
                password_hash,
                display_name,
                status,
                version
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{username},
                #{email},
                #{passwordHash},
                #{displayName},
                #{status},
                #{version}
            )
            """)
    int insert(UserRow user);

    @Select("""
        SELECT
            u.id,
            u.tenant_id,
            u.username,
            u.password_hash,
            u.status,
            COALESCE(
                GROUP_CONCAT(
                    r.code
                    ORDER BY r.code
                    SEPARATOR ','
                ),
                ''
            ) AS role_codes
        FROM tenants t
        INNER JOIN users u
            ON u.tenant_id = t.id
        LEFT JOIN user_roles ur
            ON ur.tenant_id = u.tenant_id
           AND ur.user_id = u.id
        LEFT JOIN roles r
            ON r.tenant_id = ur.tenant_id
           AND r.id = ur.role_id
        WHERE t.code = #{tenantCode}
          AND t.status = 'ACTIVE'
          AND u.username = #{username}
        GROUP BY
            u.id,
            u.tenant_id,
            u.username,
            u.password_hash,
            u.status
        """)
    LoginUserRow findForAuthentication(
            @Param("tenantCode") String tenantCode,
            @Param("username") String username
    );
}