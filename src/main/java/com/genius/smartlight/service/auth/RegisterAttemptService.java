package com.genius.smartlight.service.auth;

public interface RegisterAttemptService {

    void checkIpRateLimit(String ip);
}
