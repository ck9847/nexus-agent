package com.nexusagent.identity.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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
}