package com.nexusagent.identity.spi;

public interface AccessTokenIssuer {

    IssuedAccessToken issue(TokenSubject subject);
}
