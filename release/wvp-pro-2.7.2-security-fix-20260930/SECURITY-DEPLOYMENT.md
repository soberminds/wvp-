# WVP 2.7.2 security fix deployment

## Package contents

- `wvp-pro-2.7.2-security-fix-20260930.jar`: fixed application and rebuilt frontend.
- `config/security-application.yml`: security settings to merge into the existing external configuration.
- `SHA256SUMS.txt`: package integrity checksum.

## Mandatory deployment steps

1. Stop WVP and back up the database, current configuration, logs, and the current JAR.
2. Remove every old WVP JAR from the runtime directory, especially `wvp-pro-2.7.2-04300637.jar`. Keep exactly one WVP JAR in the directory because existing startup scripts may use `*.jar`.
3. Copy the fixed JAR to the runtime directory. Do not reuse the old `jwk.json` from source code or an old JAR.
4. Merge `config/security-application.yml` into the external application configuration. For Docker use `/opt/wvp/config/jwt-private.jwk`, or set `JWT_KEY_FILE`.
5. Start WVP once. It generates a new 3072-bit RSA private key at the configured external path. Make the file readable and writable only by the WVP service account, and back it up securely. All old login tokens and API keys are intentionally invalid after this key rotation.
6. Immediately change passwords for `admin`, `stcz`, and every privileged or known account. New passwords must be 8-64 characters and include letters, digits, and special characters.
7. Delete or disable every old API key in `wvp_user_api_key`. Recreate only required keys; each key must expire within 90 days and its full value is shown only once.
8. Serve the platform only through HTTPS. Plain HTTP exposes the login password and access token in transit.
9. Restrict MySQL, Redis, ZLMediaKit, record-assist, RTSP, RTMP, HTTP-FLV, WebRTC, and record storage ports to trusted internal networks. Expose only the HTTPS reverse proxy unless a protocol is explicitly required.
10. On multiple WVP nodes, mount the same new private key read-only after initial generation. Never commit it to Git or package it inside the JAR.

## Verification after deployment

1. A pre-upgrade token must return HTTP 401.
2. Five failed logins for the same username or source IP must block further attempts for 15 minutes.
3. A successful login token must expire after 10 minutes.
4. `GET /api/user/all`, `/api/user/users`, and API key management must require an administrator token and must not return password or push-key values.
5. A token in a URL query parameter must be rejected; only `access-token` and `api-key` request headers are accepted.
6. Confirm `jwt-private.jwk` exists outside the JAR and is not web-accessible.
7. Without a valid login, device snapshots, `/record_proxy/**`, and `/api/emit` must return HTTP 401. Browser media requests use short-lived, path-restricted secure cookies issued at login.

## Implementation notes

- Existing MD5 password rows remain usable only when the user submits the original plaintext password. The first successful login upgrades the row to BCrypt cost 12. A leaked MD5 value cannot be replayed as the password.
- Login attempt tracking is in process memory. For a multi-node deployment, enforce an additional rate limit at the reverse proxy or migrate the counters to a shared Redis implementation.
- API keys are intended for programmatic video APIs. They cannot manage users, roles, passwords, push keys, or API keys; those operations require a short-lived web login token.
- This package does not contain environment-specific database, Redis, SIP, or media server credentials. Continue using the existing external configuration after adding the security section above.
