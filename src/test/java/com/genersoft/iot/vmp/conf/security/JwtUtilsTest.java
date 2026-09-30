package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.security.dto.JwtUser;
import com.genersoft.iot.vmp.service.IUserApiKeyService;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.Role;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import com.genersoft.iot.vmp.storager.dao.dto.UserApiKey;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtUtilsTest {

    @TempDir
    Path tempDirectory;

    private final AtomicReference<User> currentUser = new AtomicReference<>();
    private final AtomicReference<UserApiKey> currentApiKey = new AtomicReference<>();

    @BeforeEach
    void initializeSigningKeyAndServices() {
        IUserService userService = mock(IUserService.class);
        IUserApiKeyService apiKeyService = mock(IUserApiKeyService.class);
        when(userService.getUserByUsername(anyString())).thenAnswer(invocation -> currentUser.get());
        when(apiKeyService.getUserApiKeyById(anyInt())).thenAnswer(invocation -> currentApiKey.get());

        currentUser.set(user(7, "stcz", "2026-09-30 10:00:00"));

        JwtUtils jwtUtils = new JwtUtils();
        jwtUtils.setUserService(userService);
        jwtUtils.setUserApiKeyService(apiKeyService);
        ReflectionTestUtils.setField(jwtUtils, "configuredKeyFile",
                tempDirectory.resolve("jwt-private.jwk").toString());
        jwtUtils.afterPropertiesSet();
    }

    @Test
    void loginTokenExpiresInTenMinutesAndCannotBeUsedAsApiKey() throws Exception {
        String token = JwtUtils.createLoginToken("stcz");
        JwtUser login = JwtUtils.verifyToken(token, JwtUtils.TOKEN_TYPE_LOGIN);
        JwtUser wrongType = JwtUtils.verifyToken(token, JwtUtils.TOKEN_TYPE_API_KEY);
        JwtClaims claims = new JwtConsumerBuilder()
                .setSkipAllValidators()
                .setDisableRequireSignature()
                .setSkipSignatureVerification()
                .build()
                .processToClaims(token);

        assertEquals(JwtUser.TokenStatus.NORMAL, login.getStatus());
        assertEquals("stcz", login.getUserName());
        assertEquals(JwtUser.TokenStatus.EXCEPTION, wrongType.getStatus());
        assertEquals(10 * 60,
                claims.getExpirationTime().getValue() - claims.getIssuedAt().getValue());
        assertTrue(Files.exists(tempDirectory.resolve("jwt-private.jwk")));
    }

    @Test
    void changingUserInvalidatesExistingLoginToken() {
        String token = JwtUtils.createLoginToken("stcz");
        currentUser.set(user(7, "stcz", "2026-09-30 10:01:00"));

        assertEquals(JwtUser.TokenStatus.EXPIRED,
                JwtUtils.verifyToken(token, JwtUtils.TOKEN_TYPE_LOGIN).getStatus());
    }

    @Test
    void passwordChangeInTheSameSecondInvalidatesExistingLoginToken() {
        String token = JwtUtils.createLoginToken("stcz");
        User changed = user(7, "stcz", "2026-09-30 10:00:00");
        changed.setPassword("different-encoded-password");
        currentUser.set(changed);

        assertEquals(JwtUser.TokenStatus.EXPIRED,
                JwtUtils.verifyToken(token, JwtUtils.TOKEN_TYPE_LOGIN).getStatus());
    }

    @Test
    void apiKeyRequiresCorrectHeaderTypeAndCurrentDatabaseValue() {
        String token = JwtUtils.createApiKeyToken("stcz", 60, 42);
        String fingerprint = JwtUtils.fingerprintApiKey(token);
        currentApiKey.set(apiKey(42, fingerprint));

        assertFalse(fingerprint.contains(token));
        assertEquals(JwtUser.TokenStatus.NORMAL,
                JwtUtils.verifyToken(token, JwtUtils.TOKEN_TYPE_API_KEY).getStatus());
        assertEquals(JwtUser.TokenStatus.EXCEPTION,
                JwtUtils.verifyToken(token, JwtUtils.TOKEN_TYPE_LOGIN).getStatus());

        currentApiKey.get().setApiKey("replacement-key");
        assertEquals(JwtUser.TokenStatus.EXPIRED,
                JwtUtils.verifyToken(token, JwtUtils.TOKEN_TYPE_API_KEY).getStatus());
    }

    @Test
    void rejectsMalformedAndMissingTokens() {
        assertEquals(JwtUser.TokenStatus.EXCEPTION,
                JwtUtils.verifyToken("not-a-jwt", JwtUtils.TOKEN_TYPE_LOGIN).getStatus());
        assertEquals(JwtUser.TokenStatus.EXCEPTION,
                JwtUtils.verifyToken(null, JwtUtils.TOKEN_TYPE_LOGIN).getStatus());
        assertFalse(Files.exists(tempDirectory.resolve("jwk.json")));
    }

    private User user(int id, String username, String updateTime) {
        Role role = new Role();
        role.setId(1);
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("encoded");
        user.setUpdateTime(updateTime);
        user.setRole(role);
        return user;
    }

    private UserApiKey apiKey(int id, String token) {
        UserApiKey apiKey = new UserApiKey();
        apiKey.setId(id);
        apiKey.setApiKey(token);
        apiKey.setEnable(true);
        apiKey.setExpiredAt(System.currentTimeMillis() + 60 * 60 * 1000L);
        return apiKey;
    }
}
