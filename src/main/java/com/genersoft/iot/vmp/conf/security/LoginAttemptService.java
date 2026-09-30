package com.genersoft.iot.vmp.conf.security;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final long WINDOW_MILLIS = 15 * 60 * 1000L;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String username, String remoteAddress) {
        long now = clock.millis();
        return isBlocked(key("user", username), now) || isBlocked(key("ip", remoteAddress), now);
    }

    public void loginFailed(String username, String remoteAddress) {
        long now = clock.millis();
        recordFailure(key("user", username), now);
        recordFailure(key("ip", remoteAddress), now);
        cleanup(now);
    }

    public void loginSucceeded(String username, String remoteAddress) {
        attempts.remove(key("user", username));
        attempts.remove(key("ip", remoteAddress));
    }

    private boolean isBlocked(String key, long now) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }
        if (now - attempt.windowStart >= WINDOW_MILLIS) {
            attempts.remove(key, attempt);
            return false;
        }
        return attempt.failures >= MAX_FAILURES;
    }

    private void recordFailure(String key, long now) {
        attempts.compute(key, (ignored, attempt) -> {
            if (attempt == null || now - attempt.windowStart >= WINDOW_MILLIS) {
                return new Attempt(now, 1);
            }
            return new Attempt(attempt.windowStart, attempt.failures + 1);
        });
    }

    private void cleanup(long now) {
        if (attempts.size() > 10_000) {
            attempts.entrySet().removeIf(entry -> now - entry.getValue().windowStart >= WINDOW_MILLIS);
        }
    }

    private String key(String type, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return type + ':' + normalized;
    }

    private static class Attempt {
        private final long windowStart;
        private final int failures;

        private Attempt(long windowStart, int failures) {
            this.windowStart = windowStart;
            this.failures = failures;
        }
    }
}
