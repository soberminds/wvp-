package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.security.dto.JwtUser;
import com.genersoft.iot.vmp.service.IUserApiKeyService;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import com.genersoft.iot.vmp.storager.dao.dto.UserApiKey;
import org.apache.commons.lang3.StringUtils;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.ErrorCodes;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtils implements InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

    public static final String HEADER = "access-token";
    public static final String API_KEY_HEADER = "api-key";
    public static final String SNAPSHOT_COOKIE = "wvp-snapshot-token";
    public static final String RECORD_COOKIE = "wvp-record-token";
    public static final String SSE_COOKIE = "wvp-sse-token";
    public static final String TOKEN_TYPE_LOGIN = "login";
    public static final String TOKEN_TYPE_API_KEY = "api-key";

    private static final String AUDIENCE = "wvp-web";
    private static final String ISSUER = "wvp";
    private static final String USER_UPDATE_TIME_CLAIM = "userUpdateTime";
    private static final String CREDENTIAL_VERSION_CLAIM = "credentialVersion";
    private static final String TOKEN_TYPE_CLAIM = "tokenType";
    private static final String API_KEY_ID_CLAIM = "apiKeyId";

    public static final long EXPIRATION_TIME = 10;
    public static final long MAX_API_KEY_EXPIRATION_MINUTES = 90L * 24 * 60;

    private static RsaJsonWebKey rsaJsonWebKey;
    private static IUserService userService;
    private static IUserApiKeyService userApiKeyService;

    @Value("${security.jwt.key-file:}")
    private String configuredKeyFile;

    @Resource
    public void setUserService(IUserService userService) {
        JwtUtils.userService = userService;
    }

    @Resource
    public void setUserApiKeyService(IUserApiKeyService userApiKeyService) {
        JwtUtils.userApiKeyService = userApiKeyService;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            rsaJsonWebKey = loadOrCreateKey(resolveKeyPath());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize JWT signing key", e);
        }
    }

    private Path resolveKeyPath() {
        if (StringUtils.isNotBlank(configuredKeyFile)) {
            return Paths.get(configuredKeyFile).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".wvp", "jwt-private.jwk")
                .toAbsolutePath().normalize();
    }

    private RsaJsonWebKey loadOrCreateKey(Path keyPath) throws IOException, JoseException {
        if (Files.exists(keyPath)) {
            String json = new String(Files.readAllBytes(keyPath), StandardCharsets.UTF_8);
            List<JsonWebKey> keys = new JsonWebKeySet(json).getJsonWebKeys();
            if (keys.size() != 1 || !(keys.get(0) instanceof RsaJsonWebKey)) {
                throw new IllegalStateException("JWT key file must contain exactly one RSA private key: " + keyPath);
            }
            RsaJsonWebKey key = (RsaJsonWebKey) keys.get(0);
            if (key.getPrivateKey() == null || StringUtils.isBlank(key.getKeyId())) {
                throw new IllegalStateException("JWT key file is missing private key material or kid: " + keyPath);
            }
            logger.info("Loaded JWT signing key from {}", keyPath);
            return key;
        }

        Path parent = keyPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        RsaJsonWebKey key = RsaJwkGenerator.generateJwk(3072);
        key.setKeyId(UUID.randomUUID().toString());
        String json = new JsonWebKeySet(key).toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE);
        Path temp = Files.createTempFile(parent, "jwt-private-", ".tmp");
        Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
        setOwnerOnlyPermissions(temp);
        try {
            Files.move(temp, keyPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, keyPath);
        }
        setOwnerOnlyPermissions(keyPath);
        logger.warn("Generated a new JWT signing key at {}. Back up this file securely.", keyPath);
        return key;
    }

    private void setOwnerOnlyPermissions(Path path) {
        try {
            EnumSet<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some mounted filesystems do not support POSIX permissions.
        }
    }

    public static String createLoginToken(String username) {
        User user = requireUser(username);
        Map<String, Object> extra = new HashMap<>(2);
        extra.put(TOKEN_TYPE_CLAIM, TOKEN_TYPE_LOGIN);
        extra.put(USER_UPDATE_TIME_CLAIM, user.getUpdateTime());
        extra.put(CREDENTIAL_VERSION_CLAIM, fingerprintCredential(user.getPassword()));
        return createToken(username, EXPIRATION_TIME, extra);
    }

    public static String createApiKeyToken(String username, long expirationMinutes, int apiKeyId) {
        if (expirationMinutes <= 0 || expirationMinutes > MAX_API_KEY_EXPIRATION_MINUTES) {
            throw new IllegalArgumentException("API key expiration must be between 1 minute and 90 days");
        }
        Map<String, Object> extra = new HashMap<>(2);
        extra.put(TOKEN_TYPE_CLAIM, TOKEN_TYPE_API_KEY);
        extra.put(API_KEY_ID_CLAIM, apiKeyId);
        return createToken(username, expirationMinutes, extra);
    }

    private static String createToken(String username, long expirationMinutes, Map<String, Object> extra) {
        if (rsaJsonWebKey == null) {
            throw new IllegalStateException("JWT signing key is not initialized");
        }
        try {
            JwtClaims claims = new JwtClaims();
            claims.setGeneratedJwtId();
            claims.setIssuedAtToNow();
            claims.setExpirationTimeMinutesInTheFuture(expirationMinutes);
            claims.setNotBeforeMinutesInThePast(0);
            claims.setIssuer(ISSUER);
            claims.setSubject(username);
            claims.setAudience(AUDIENCE);
            claims.setClaim("userName", username);
            extra.forEach(claims::setClaim);

            JsonWebSignature jws = new JsonWebSignature();
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
            jws.setKeyIdHeaderValue(rsaJsonWebKey.getKeyId());
            jws.setPayload(claims.toJson());
            jws.setKey(rsaJsonWebKey.getPrivateKey());
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException("Unable to create JWT", e);
        }
    }

    public static JwtUser verifyToken(String token, String expectedTokenType) {
        JwtUser jwtUser = new JwtUser();
        try {
            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setRequireExpirationTime()
                    .setRequireIssuedAt()
                    .setRequireJwtId()
                    .setAllowedClockSkewInSeconds(30)
                    .setRequireSubject()
                    .setExpectedIssuer(ISSUER)
                    .setExpectedAudience(AUDIENCE)
                    .setVerificationKey(rsaJsonWebKey.getPublicKey())
                    .setJwsAlgorithmConstraints(
                            AlgorithmConstraints.ConstraintType.PERMIT,
                            AlgorithmIdentifiers.RSA_USING_SHA256)
                    .build();

            JwtClaims claims = consumer.processToClaims(token);
            String tokenType = claims.getClaimValue(TOKEN_TYPE_CLAIM, String.class);
            if (!expectedTokenType.equals(tokenType)) {
                jwtUser.setStatus(JwtUser.TokenStatus.EXCEPTION);
                return jwtUser;
            }

            String username = claims.getClaimValue("userName", String.class);
            if (username == null || !username.equals(claims.getSubject())) {
                jwtUser.setStatus(JwtUser.TokenStatus.EXCEPTION);
                return jwtUser;
            }
            User user = userService.getUserByUsername(username);
            if (user == null || user.getRole() == null) {
                jwtUser.setStatus(JwtUser.TokenStatus.EXPIRED);
                return jwtUser;
            }

            if (TOKEN_TYPE_LOGIN.equals(tokenType)) {
                String tokenUserUpdateTime = claims.getClaimValue(USER_UPDATE_TIME_CLAIM, String.class);
                if (tokenUserUpdateTime == null || !tokenUserUpdateTime.equals(user.getUpdateTime())) {
                    jwtUser.setStatus(JwtUser.TokenStatus.EXPIRED);
                    return jwtUser;
                }
                String credentialVersion = claims.getClaimValue(CREDENTIAL_VERSION_CLAIM, String.class);
                if (credentialVersion == null || !MessageDigest.isEqual(
                        credentialVersion.getBytes(StandardCharsets.UTF_8),
                        fingerprintCredential(user.getPassword()).getBytes(StandardCharsets.UTF_8))) {
                    jwtUser.setStatus(JwtUser.TokenStatus.EXPIRED);
                    return jwtUser;
                }
            } else {
                Long apiKeyId = claims.getClaimValue(API_KEY_ID_CLAIM, Long.class);
                if (apiKeyId == null || !isCurrentApiKey(apiKeyId.intValue(), token)) {
                    jwtUser.setStatus(JwtUser.TokenStatus.EXPIRED);
                    return jwtUser;
                }
            }

            NumericDate expirationTime = claims.getExpirationTime();
            long remainingSeconds = expirationTime.getValue() - NumericDate.now().getValue();
            jwtUser.setStatus(remainingSeconds <= 5 * 60
                    ? JwtUser.TokenStatus.EXPIRING_SOON
                    : JwtUser.TokenStatus.NORMAL);
            jwtUser.setUserName(username);
            jwtUser.setPassword(user.getPassword());
            jwtUser.setRoleId(user.getRole().getId());
            jwtUser.setUserId(user.getId());
            return jwtUser;
        } catch (InvalidJwtException e) {
            jwtUser.setStatus(e.hasErrorCode(ErrorCodes.EXPIRED)
                    ? JwtUser.TokenStatus.EXPIRED
                    : JwtUser.TokenStatus.EXCEPTION);
            return jwtUser;
        } catch (Exception e) {
            logger.warn("Token verification failed: {}", e.getMessage());
            jwtUser.setStatus(JwtUser.TokenStatus.EXCEPTION);
            return jwtUser;
        }
    }

    private static boolean isCurrentApiKey(int apiKeyId, String token) {
        UserApiKey apiKey = userApiKeyService.getUserApiKeyById(apiKeyId);
        if (apiKey == null || !apiKey.isEnable() || apiKey.getExpiredAt() <= System.currentTimeMillis()) {
            return false;
        }
        String stored = apiKey.getApiKey();
        String fingerprint = fingerprintApiKey(token);
        return stored != null && MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), fingerprint.getBytes(StandardCharsets.UTF_8));
    }

    public static String fingerprintApiKey(String token) {
        if (token == null) {
            throw new IllegalArgumentException("API key must not be null");
        }
        return "sha256:" + sha256Hex(token);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte digestByte : digest) {
                hex.append(String.format("%02x", digestByte & 0xff));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String fingerprintCredential(String passwordHash) {
        if (passwordHash == null) {
            return "";
        }
        return sha256Hex(passwordHash);
    }

    private static User requireUser(String username) {
        User user = userService.getUserByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("User does not exist");
        }
        return user;
    }

    public static String getHeader() {
        return HEADER;
    }

    public static String getApiKeyHeader() {
        return API_KEY_HEADER;
    }
}
