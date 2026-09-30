package com.genersoft.iot.vmp.conf.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptServiceTest {

    @Test
    void blocksUsernameAndIpAfterFiveFailures() {
        MutableClock clock = new MutableClock();
        LoginAttemptService service = new LoginAttemptService(clock);

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            service.loginFailed("STCZ", "192.0.2.10");
            assertFalse(service.isBlocked("stcz", "192.0.2.10"));
        }

        service.loginFailed("STCZ", "192.0.2.10");
        assertTrue(service.isBlocked("stcz", "198.51.100.20"));
        assertTrue(service.isBlocked("another-user", "192.0.2.10"));
    }

    @Test
    void successClearsAttemptsAndWindowExpires() {
        MutableClock clock = new MutableClock();
        LoginAttemptService service = new LoginAttemptService(clock);

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.loginFailed("stcz", "192.0.2.10");
        }
        service.loginSucceeded("stcz", "192.0.2.10");
        assertFalse(service.isBlocked("stcz", "192.0.2.10"));

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.loginFailed("stcz", "192.0.2.10");
        }
        clock.advance(LoginAttemptService.WINDOW_MILLIS);
        assertFalse(service.isBlocked("stcz", "192.0.2.10"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-30T00:00:00Z");

        void advance(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
