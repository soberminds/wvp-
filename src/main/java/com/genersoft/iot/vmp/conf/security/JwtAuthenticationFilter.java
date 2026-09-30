package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.security.dto.JwtUser;
import com.genersoft.iot.vmp.storager.dao.dto.Role;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * jwt token 过滤器
 */

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {


    @Autowired
    private UserSetting userSetting;


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {

        // 忽略登录请求的token验证
        String requestURI = request.getRequestURI();
        if ((requestURI.startsWith("/doc.html") || requestURI.startsWith("/swagger-ui") ) && !userSetting.getDocEnable()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (requestURI.equalsIgnoreCase("/api/user/login")) {
            chain.doFilter(request, response);
            return;
        }

        String jwt = request.getHeader(JwtUtils.getHeader());
        String tokenType = JwtUtils.TOKEN_TYPE_LOGIN;
        if (StringUtils.isBlank(jwt)) {
            jwt = request.getHeader(JwtUtils.getApiKeyHeader());
            tokenType = JwtUtils.TOKEN_TYPE_API_KEY;
            if (StringUtils.isBlank(jwt)) {
                jwt = resolvePathRestrictedToken(request, requestURI);
                tokenType = JwtUtils.TOKEN_TYPE_LOGIN;
                if (StringUtils.isBlank(jwt)) {
                    chain.doFilter(request, response);
                    return;
                }
            }
        }

        JwtUser jwtUser = JwtUtils.verifyToken(jwt, tokenType);
        // TODO 处理各个状态
        switch (jwtUser.getStatus()){
            case EXPIRED:
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                // 异常
                return;
            case EXCEPTION:
                // 过期
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            case EXPIRING_SOON:
            default:
        }

        // 构建UsernamePasswordAuthenticationToken,这里密码为null，是因为提供了正确的JWT,实现自动登录
        User user = new User();
        user.setId(jwtUser.getUserId());
        user.setUsername(jwtUser.getUserName());
        user.setPassword(jwtUser.getPassword());
        Role role = new Role();
        role.setId(jwtUser.getRoleId());
        user.setRole(role);
        String authority = JwtUtils.TOKEN_TYPE_API_KEY.equals(tokenType)
                ? SecurityUtils.API_KEY_AUTHORITY
                : SecurityUtils.LOGIN_TOKEN_AUTHORITY;
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                user, jwtUser.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
        chain.doFilter(request, response);
    }

    private String resolvePathRestrictedToken(HttpServletRequest request, String requestURI) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String cookieName = null;
        if (requestURI.startsWith("/api/device/query/snap/")) {
            cookieName = JwtUtils.SNAPSHOT_COOKIE;
        } else if (requestURI.startsWith("/record_proxy/")) {
            cookieName = JwtUtils.RECORD_COOKIE;
        } else if (requestURI.equals("/api/emit")) {
            cookieName = JwtUtils.SSE_COOKIE;
        }
        if (cookieName == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

}
