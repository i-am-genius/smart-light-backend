package com.genius.smartlight.service.auth.impl;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.service.auth.RegisterAttemptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RegisterAttemptServiceImpl implements RegisterAttemptService {

    private static final int MAX_IP_ATTEMPTS_PER_HOUR = 5;
    private static final long IP_WINDOW_MS = Duration.ofHours(1).toMillis();

    private final Map<String, Deque<Long>> ipAttempts = new ConcurrentHashMap<>();

    @Override
    public void checkIpRateLimit(String ip) {
        String key = normalizeIp(ip);
        long now = System.currentTimeMillis();
        Deque<Long> attempts = ipAttempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (attempts) {
            pruneAttempts(attempts, now);
            if (attempts.size() >= MAX_IP_ATTEMPTS_PER_HOUR) {
                log.warn("Register request rate limited by IP, ip={}", key);
                throw new ServiceException("注册过于频繁，请稍后再试");
            }
            attempts.addLast(now);
        }
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
                pruneAttempts(attempts, now);
                return attempts.isEmpty();
            }
        });
    }

    private void pruneAttempts(Deque<Long> attempts, long now) {
        while (!attempts.isEmpty() && now - attempts.peekFirst() > IP_WINDOW_MS) {
            attempts.removeFirst();
        }
    }
}
