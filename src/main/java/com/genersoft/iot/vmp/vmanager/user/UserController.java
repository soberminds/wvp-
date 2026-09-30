package com.genersoft.iot.vmp.vmanager.user;

import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.conf.security.JwtUtils;
import com.genersoft.iot.vmp.conf.security.LoginAttemptService;
import com.genersoft.iot.vmp.conf.security.SecurityUtils;
import com.genersoft.iot.vmp.conf.security.dto.LoginUser;
import com.genersoft.iot.vmp.service.IRoleService;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.Role;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.github.pagehelper.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.security.SecureRandom;

@Tag(name  = "用户管理")
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private IUserService userService;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    @Operation(summary = "登录", description = "登录成功后返回AccessToken， 可以从返回值获取到也可以从响应头中获取到，" +
            "后续请求必须通过请求头 'access-token' 提交")

    @Parameter(name = "username", description = "用户名", required = true)
    @Parameter(name = "password", description = "密码明文（必须使用HTTPS传输）", required = true)
    public LoginUser login(HttpServletRequest request, HttpServletResponse response, @RequestParam String username, @RequestParam String password){
        String remoteAddress = request.getRemoteAddr();
        if (loginAttemptService.isBlocked(username, remoteAddress)) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "登录失败次数过多，请15分钟后重试");
        }
        LoginUser user;
        try {
            user = SecurityUtils.login(username, password, authenticationManager);
        } catch (AuthenticationException e) {
            loginAttemptService.loginFailed(username, remoteAddress);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "用户名或密码错误");
        }
        if (user == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "用户名或密码错误");
        }else {
            loginAttemptService.loginSucceeded(username, remoteAddress);
            User storedUser = userService.getUserByUsername(username);
            if (storedUser != null && passwordEncoder.upgradeEncoding(storedUser.getPassword())) {
                userService.changePassword(storedUser.getId(), passwordEncoder.encode(password));
            }
            String jwt = JwtUtils.createLoginToken(username);
            response.setHeader(JwtUtils.getHeader(), jwt);
            response.setHeader("Cache-Control", "no-store");
            addRestrictedTokenCookie(response, JwtUtils.SNAPSHOT_COOKIE, "/api/device/query/snap", jwt);
            addRestrictedTokenCookie(response, JwtUtils.RECORD_COOKIE, "/record_proxy", jwt);
            addRestrictedTokenCookie(response, JwtUtils.SSE_COOKIE, "/api/emit", jwt);
            user.setAccessToken(jwt);
        }
        return user;
    }


    @PostMapping("/changePassword")
    @Operation(summary = "修改密码", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "username", description = "用户名", required = true)
    @Parameter(name = "oldpassword", description = "旧密码明文（必须使用HTTPS传输）", required = true)
    @Parameter(name = "password", description = "新密码（未md5加密的密码）", required = true)
    public void changePassword(HttpServletRequest request, @RequestParam String oldPassword,
                               @RequestParam String password){
        SecurityUtils.requireLoginToken();
        // 获取当前登录用户id
        LoginUser userInfo = SecurityUtils.getUserInfo();
        if (userInfo== null) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
        String username = userInfo.getUsername();
        String remoteAddress = request.getRemoteAddr();
        if (loginAttemptService.isBlocked(username, remoteAddress)) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "验证失败次数过多，请15分钟后重试");
        }
        LoginUser user = null;
        try {
            user = SecurityUtils.login(username, oldPassword, authenticationManager);
            if (user == null) {
                throw new ControllerException(ErrorCode.ERROR100);
            }
            //int userId = SecurityUtils.getUserId();
            validatePassword(password);
            boolean result = userService.changePassword(user.getId(), passwordEncoder.encode(password));
            if (!result) {
                throw new ControllerException(ErrorCode.ERROR100);
            }
            loginAttemptService.loginSucceeded(username, remoteAddress);
        } catch (AuthenticationException e) {
            loginAttemptService.loginFailed(username, remoteAddress);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "旧密码错误");
        }
    }


    @PostMapping("/add")
    @Operation(summary = "添加用户", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "username", description = "用户名", required = true)
    @Parameter(name = "password", description = "密码（未md5加密的密码）", required = true)
    @Parameter(name = "roleId", description = "角色ID", required = true)
    public void add(@RequestParam String username,
                                                  @RequestParam String password,
                                                  @RequestParam Integer roleId){
        SecurityUtils.requireAdminLoginToken();
        if (ObjectUtils.isEmpty(username) || ObjectUtils.isEmpty(password) || roleId == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "参数不可为空");
        }
        // 获取当前登录用户id
        int currenRoleId = SecurityUtils.getUserInfo().getRole().getId();
        if (currenRoleId != 1) {
            // 只用角色id为1才可以删除和添加用户
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "用户无权限");
        }
        User user = new User();
        user.setUsername(username);
        validatePassword(password);
        user.setPassword(passwordEncoder.encode(password));
        user.setPushKey(generatePushKey());
        Role role = roleService.getRoleById(roleId);

        if (role == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "角色不存在");
        }
        user.setRole(role);
        user.setCreateTime(DateUtil.getNow());
        user.setUpdateTime(DateUtil.getNow());
        int addResult = userService.addUser(user);
        if (addResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除用户", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "id", description = "用户Id", required = true)
    public void delete(@RequestParam Integer id){
        SecurityUtils.requireAdminLoginToken();
        // 获取当前登录用户id
        int currenRoleId = SecurityUtils.getUserInfo().getRole().getId();
        if (currenRoleId != 1) {
            // 只用角色id为0才可以删除和添加用户
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "用户无权限");
        }
        int deleteResult = userService.deleteUser(id);
        if (deleteResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
    }

    @GetMapping("/all")
    @Operation(summary = "查询用户", security = @SecurityRequirement(name = JwtUtils.HEADER))
    public List<User> all(){
        SecurityUtils.requireAdminLoginToken();
        return userService.getAllUsers();
    }

    /**
     * 分页查询用户
     *
     * @param page  当前页
     * @param count 每页查询数量
     * @return 分页用户列表
     */
    @GetMapping("/users")
    @Operation(summary = "分页查询用户", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "page", description = "当前页", required = true)
    @Parameter(name = "count", description = "每页查询数量", required = true)
    public PageInfo<User> users(int page, int count) {
        SecurityUtils.requireAdminLoginToken();
        return userService.getUsers(page, count);
    }

    @PostMapping("/changePushKey")
    @Operation(summary = "修改pushkey", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "userId", description = "用户Id", required = true)
    @Parameter(name = "pushKey", description = "新的pushKey", required = true)
    public void changePushKey(@RequestParam Integer userId,@RequestParam String pushKey) {
        SecurityUtils.requireAdminLoginToken();
        // 获取当前登录用户id
        int currenRoleId = SecurityUtils.getUserInfo().getRole().getId();
        WVPResult<String> result = new WVPResult<>();
        if (currenRoleId != 1) {
            // 只用角色id为0才可以删除和添加用户
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "用户无权限");
        }
        int resetPushKeyResult = userService.changePushKey(userId,pushKey);
        if (resetPushKeyResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
    }

    @PostMapping("/changePasswordForAdmin")
    @Operation(summary = "管理员修改普通用户密码", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "adminId", description = "管理员id", required = true)
    @Parameter(name = "userId", description = "用户id", required = true)
    @Parameter(name = "password", description = "新密码（未md5加密的密码）", required = true)
    public void changePasswordForAdmin(@RequestParam int userId, @RequestParam String password) {
        SecurityUtils.requireAdminLoginToken();
        validatePassword(password);
        boolean result = userService.changePassword(userId, passwordEncoder.encode(password));
        if (!result) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
    }

    @PostMapping("/userInfo")
    @Operation(summary = "管理员修改普通用户密码")
    public LoginUser getUserInfo() {
        // 获取当前登录用户id
        LoginUser userInfo = SecurityUtils.getUserInfo();

        if (userInfo == null) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
        User user = userService.getUser(userInfo.getUsername(), userInfo.getPassword());
        return new LoginUser(user, LocalDateTime.now());
    }

    private void validatePassword(String password) {
        if (password == null || !password.matches("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,64}$")) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(),
                    "密码需为8-64位，并同时包含字母、数字和特殊字符");
        }
    }

    private String generatePushKey() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }

    private void addRestrictedTokenCookie(HttpServletResponse response, String name, String path, String token) {
        response.addHeader("Set-Cookie", name + "=" + token + "; Path=" + path
                + "; Max-Age=600; HttpOnly; Secure; SameSite=Strict");
    }
}
