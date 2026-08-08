package com.nexusagent.identity.api;

public interface AuthenticationService {

    LoginResponse login(LoginRequest request);
}