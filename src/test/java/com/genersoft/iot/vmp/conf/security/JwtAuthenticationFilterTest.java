package com.genersoft.iot.vmp.conf.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtAuthenticationFilterTest {

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

    @Test
    void restrictedCookieIsAcceptedOnlyForItsGetPath() {
        MockHttpServletRequest snapshot = request("GET", "/api/device/query/snap/device/channel",
                JwtUtils.SNAPSHOT_COOKIE);
        MockHttpServletRequest record = request("GET", "/record_proxy/server/record.mp4",
                JwtUtils.RECORD_COOKIE);
        MockHttpServletRequest sse = request("GET", "/api/emit", JwtUtils.SSE_COOKIE);

        assertEquals("token", resolve(snapshot));
        assertEquals("token", resolve(record));
        assertEquals("token", resolve(sse));
    }

    @Test
    void restrictedCookieCannotAuthenticateOtherApisOrWriteRequests() {
        MockHttpServletRequest api = request("GET", "/api/user/all", JwtUtils.SNAPSHOT_COOKIE);
        MockHttpServletRequest write = request("DELETE", "/record_proxy/api/record/delete",
                JwtUtils.RECORD_COOKIE);
        MockHttpServletRequest wrongCookie = request("GET", "/record_proxy/server/record.mp4",
                JwtUtils.SNAPSHOT_COOKIE);

        assertNull(resolve(api));
        assertNull(resolve(write));
        assertNull(resolve(wrongCookie));
    }

    private MockHttpServletRequest request(String method, String uri, String cookieName) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setCookies(new Cookie(cookieName, "token"));
        return request;
    }

    private String resolve(MockHttpServletRequest request) {
        return ReflectionTestUtils.invokeMethod(filter, "resolvePathRestrictedToken",
                request, request.getRequestURI());
    }
}
