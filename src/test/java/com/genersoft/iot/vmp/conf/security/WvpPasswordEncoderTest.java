package com.genersoft.iot.vmp.conf.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WvpPasswordEncoderTest {

    private final WvpPasswordEncoder encoder = new WvpPasswordEncoder();

    @Test
    void acceptsLegacyMd5OnlyForTheOriginalPlaintext() {
        String legacyAdminMd5 = "21232f297a57a5a743894a0e4a801fc3";

        assertTrue(encoder.matches("admin", legacyAdminMd5));
        assertFalse(encoder.matches(legacyAdminMd5, legacyAdminMd5));
        assertTrue(encoder.upgradeEncoding(legacyAdminMd5));
    }

    @Test
    void encodesNewPasswordsWithBcrypt() {
        String encoded = encoder.encode("StrongPassword1!");

        assertTrue(encoded.startsWith("$2"));
        assertTrue(encoder.matches("StrongPassword1!", encoded));
        assertFalse(encoder.matches("wrong", encoded));
        assertFalse(encoder.upgradeEncoding(encoded));
    }
}
