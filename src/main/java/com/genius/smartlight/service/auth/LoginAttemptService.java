package com.genius.smartlight.service.auth;

public interface LoginAttemptService {

    void checkIpRateLimit(String ip);

    void checkUsernameLocked(String username);

    void recordFailure(String username);

    void recordSuccess(String username);
}
