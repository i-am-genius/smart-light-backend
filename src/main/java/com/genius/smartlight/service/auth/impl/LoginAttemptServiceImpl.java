package com.genius.smartlight.service.auth.impl;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.service.auth.LoginAttemptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class LoginAttemptServiceImpl implements LoginAttemptService {

    private static final int MAX_USERNAME_FAILURES = 5;
    private static final long USER_LOCK_MS = Duration.ofMinutes(15).toMillis();
    private static final int MAX_IP_ATTEMPTS_PER_MINUTE = 10;
    private static final long IP_WINDOW_MS = Duration.ofMinutes(1).toMillis();

    private final Map<String, FailureState> usernameFailures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> ipAttempts = new ConcurrentHashMap<>();

    @Override
    public void checkIpRateLimit(String ip) {
        String key = normalizeIp(ip);
        long now = System.currentTimeMillis();
        Deque<Long> attempts = ipAttempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (attempts) {
            pruneAttempts(attempts, now, IP_WINDOW_MS);
            if (attempts.size() >= MAX_IP_ATTEMPTS_PER_MINUTE) {
                log.warn("Login request rate limited by IP, ip={}", key);
                throw new ServiceException("请求过于频繁，请稍后再试");
            }
            attempts.addLast(now);
        }
    }

    @Override
    public void checkUsernameLocked(String username) {
        String key = normalizeUsername(username);
        if (key == null) {
            return;
        }

        FailureState state = usernameFailures.get(key);
        if (state == null) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.lockedUntilMs > now) {
                log.warn("Login blocked by username lock, username={}", key);
                throw new ServiceException("登录失败次数过多，请稍后再试");
            }
            if (state.lockedUntilMs > 0) {
                usernameFailures.remove(key);
            }
        }
    }

    @Override
    public void recordFailure(String username) {
        String key = normalizeUsername(username);
        if (key == null) {
            return;
        }

        FailureState state = usernameFailures.computeIfAbsent(key, ignored -> new FailureState());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.lockedUntilMs > now) {
                return;
            }
            state.failureCount++;
            state.lastUpdatedMs = now;
            if (state.failureCount >= MAX_USERNAME_FAILURES) {
                state.lockedUntilMs = now + USER_LOCK_MS;
                log.warn("Login username locked after repeated failures, username={}, failureCount={}", key, state.failureCount);
                throw new ServiceException("登录失败次数过多，请稍后再试");
            } else {
                log.warn("Login failed, username={}, failureCount={}", key, state.failureCount);
            }
        }
    }

    @Override
    public void recordSuccess(String username) {
        String key = normalizeUsername(username);
        if (key != null) {
            usernameFailures.remove(key);
        }
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String value = username.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        return ip.trim();
    }

    @Scheduled(fixedDelay = 600_000)
    public void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        ipAttempts.entrySet().removeIf(entry -> {
            Deque<Long> attempts = entry.getValue();
            synchronized (attempts) {
                pruneAttempts(attempts, now, IP_WINDOW_MS);
                return attempts.isEmpty();
            }
        });
        usernameFailures.entrySet().removeIf(entry -> shouldRemoveFailureState(entry.getValue(), now));
    }

    private void pruneAttempts(Deque<Long> attempts, long now, long windowMs) {
        while (!attempts.isEmpty() && now - attempts.peekFirst() > windowMs) {
            attempts.removeFirst();
        }
    }

    private boolean shouldRemoveFailureState(FailureState state, long now) {
        synchronized (state) {
            if (state.lockedUntilMs > 0) {
                return state.lockedUntilMs <= now;
            }
            return state.lastUpdatedMs > 0 && now - state.lastUpdatedMs > USER_LOCK_MS;
        }
    }

    private static class FailureState {
        private int failureCount;
        private long lockedUntilMs;
        private long lastUpdatedMs;
    }
}
