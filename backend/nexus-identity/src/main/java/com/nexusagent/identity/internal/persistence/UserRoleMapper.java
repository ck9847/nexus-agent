package com.nexusagent.identity.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRoleMapper {

    @Insert("""
            INSERT INTO user_roles
                (tenant_id, user_id, role_id, assigned_by)
            VALUES
                (#{tenantId}, #{userId}, #{roleId}, #{assignedBy})
            """)
    int insert(UserRoleRow userRole);
}