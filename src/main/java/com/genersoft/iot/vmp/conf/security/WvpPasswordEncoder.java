package com.genersoft.iot.vmp.conf.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

@Component
public class WvpPasswordEncoder implements PasswordEncoder {

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);

    @Override
    public String encode(CharSequence rawPassword) {
        return bcrypt.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        if (isBcrypt(encodedPassword)) {
            return bcrypt.matches(rawPassword, encodedPassword);
        }
        if (encodedPassword.matches("(?i)^[0-9a-f]{32}$")) {
            String legacyMd5 = DigestUtils.md5DigestAsHex(
                    rawPassword.toString().getBytes(StandardCharsets.UTF_8));
            return legacyMd5.equalsIgnoreCase(encodedPassword);
        }
        return false;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return !isBcrypt(encodedPassword) || bcrypt.upgradeEncoding(encodedPassword);
    }

    private boolean isBcrypt(String encodedPassword) {
        return encodedPassword != null && encodedPassword.matches("^\\$2[ayb]\\$.+");
    }
}
