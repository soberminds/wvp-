package com.genersoft.iot.vmp.vmanager.user;

import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.conf.security.LoginAttemptService;
import com.genersoft.iot.vmp.conf.security.SecurityUtils;
import com.genersoft.iot.vmp.conf.security.dto.LoginUser;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.Role;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerSecurityTest {

    private static final int MAX_FAILURES = 5;

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final IUserService userService = mock(IUserService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginAttemptService loginAttemptService = new LoginAttemptService();
    private final UserController controller = new UserController();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "authenticationManager", authenticationManager);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(controller, "loginAttemptService", loginAttemptService);
        request.setRemoteAddr("192.0.2.10");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void changePasswordRequiresAnAuthenticatedUser() {
        assertThrows(ControllerException.class,
                () -> controller.changePassword(request, "old-password", "NewPassword1!"));
        verify(authenticationManager, never()).authenticate(any());
        verify(userService, never()).changePassword(anyInt(), anyString());
    }

    @Test
    void fiveWrongOldPasswordsBlockFurtherPasswordVerification() {
        authenticateAs(user(7, "stcz", 2));
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        for (int i = 0; i < MAX_FAILURES; i++) {
            assertThrows(ControllerException.class,
                    () -> controller.changePassword(request, "wrong", "NewPassword1!"));
        }
        assertThrows(ControllerException.class,
                () -> controller.changePassword(request, "wrong", "NewPassword1!"));

        verify(authenticationManager, times(MAX_FAILURES)).authenticate(any());
        verify(userService, never()).changePassword(anyInt(), anyString());
    }

    @Test
    void validOldPasswordChangesOnlyTheAuthenticatedUsersPassword() {
        User current = user(7, "stcz", 2);
        authenticateAs(current);
        LoginUser authenticated = new LoginUser(current, LocalDateTime.now());
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken(authenticated, null));
        when(passwordEncoder.encode("NewPassword1!")).thenReturn("bcrypt-value");
        when(userService.changePassword(7, "bcrypt-value")).thenReturn(true);

        controller.changePassword(request, "correct-old-password", "NewPassword1!");

        verify(userService).changePassword(7, "bcrypt-value");
    }

    @Test
    void nonAdminCannotUseAdministratorPasswordReset() {
        authenticateAs(user(7, "stcz", 2));

        assertThrows(ControllerException.class,
                () -> controller.changePasswordForAdmin(8, "NewPassword1!"));
        verify(userService, never()).changePassword(anyInt(), anyString());
    }

    @Test
    void apiKeyCannotChangePasswordEvenWithTheOldPassword() {
        authenticateAsApiKey(user(7, "stcz", 2));

        assertThrows(ControllerException.class,
                () -> controller.changePassword(request, "correct-old-password", "NewPassword1!"));
        verify(authenticationManager, never()).authenticate(any());
        verify(userService, never()).changePassword(anyInt(), anyString());
    }

    @Test
    void administratorApiKeyCannotResetAnotherUsersPassword() {
        authenticateAsApiKey(user(1, "admin", 1));

        assertThrows(ControllerException.class,
                () -> controller.changePasswordForAdmin(7, "NewPassword1!"));
        verify(userService, never()).changePassword(anyInt(), anyString());
    }

    @Test
    void administratorApiKeyCannotCreateAUser() {
        authenticateAsApiKey(user(1, "admin", 1));

        assertThrows(ControllerException.class,
                () -> controller.add("backdoor", "NewPassword1!", 1));
        verify(userService, never()).addUser(any());
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.singletonList(
                        new SimpleGrantedAuthority(SecurityUtils.LOGIN_TOKEN_AUTHORITY))));
    }

    private void authenticateAsApiKey(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.singletonList(
                        new SimpleGrantedAuthority(SecurityUtils.API_KEY_AUTHORITY))));
    }

    private User user(int id, String username, int roleId) {
        Role role = new Role();
        role.setId(roleId);
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }
}
