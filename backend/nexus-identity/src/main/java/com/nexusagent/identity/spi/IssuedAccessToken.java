package com.nexusagent.identity.spi;

import java.util.Objects;

public record IssuedAccessToken(
        String value,
        long expiresInSeconds
) {

    public IssuedAccessToken{
        Objects.requireNonNull(value, "token value must not be null");

        if(value.isBlank()){
            throw new IllegalArgumentException(
                    "token value must not be blank"
            );
        }

        if(expiresInSeconds <= 0){
            throw new IllegalArgumentException(
                    "expiresInSeconds must be positive"
            );
        }
    }
}
