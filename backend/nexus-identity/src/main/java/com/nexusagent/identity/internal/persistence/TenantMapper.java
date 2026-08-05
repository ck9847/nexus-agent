package com.nexusagent.identity.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenantMapper {

    @Select("""
            SELECT EXISTS(
                SELECT 1
                FROM tenants
                WHERE code = #{code}
            )
            """)
    boolean existsByCode(@Param("code") String code);

    @Insert("""
            INSERT INTO tenants
                (id, code, name, status, version)
            VALUES
                (#{id}, #{code}, #{name}, #{status}, #{version})
            """)
    int insert(TenantRow tenant);
}