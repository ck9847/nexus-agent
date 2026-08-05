package com.nexusagent.identity.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleMapper {

    @Insert("""
            INSERT INTO roles
                (id, tenant_id, code, name, description)
            VALUES
                (#{id}, #{tenantId}, #{code}, #{name}, #{description})
            """)
    int insert(RoleRow role);
}