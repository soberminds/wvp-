package com.genersoft.iot.vmp.vmanager.user;

import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.conf.security.JwtUtils;
import com.genersoft.iot.vmp.conf.security.SecurityUtils;
import com.genersoft.iot.vmp.service.IUserApiKeyService;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import com.genersoft.iot.vmp.storager.dao.dto.UserApiKey;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.github.pagehelper.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;


@Tag(name = "用户ApiKey管理")
@RestController
@RequestMapping("/api/userApiKey")
public class UserApiKeyController {

    @Autowired
    private IUserService userService;

    @Autowired
    private IUserApiKeyService userApiKeyService;

    /**
     * 添加用户ApiKey
     *
     * @param userId
     * @param app
     * @param remark
     * @param expiresAt
     * @param enable
     */
    @PostMapping("/add")
    @Operation(summary = "添加用户ApiKey", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "userId", description = "用户Id", required = true)
    @Parameter(name = "app", description = "应用名称", required = false)
    @Parameter(name = "remark", description = "备注信息", required = false)
    @Parameter(name = "expiredAt", description = "过期时间（必填，最长90天）", required = true)
    @Transactional
    public synchronized String add(
            @RequestParam(required = true) int userId,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String remark,
            @RequestParam(required = false) String expiresAt,
            @RequestParam(required = false) Boolean enable
    ) {
        SecurityUtils.requireAdminLoginToken();
        User user = userService.getUserById(userId);
        if (user == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "用户不存在");
        }

        if (expiresAt == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "必须设置ApiKey过期时间");
        }
        long expirationTimestamp = DateUtil.yyyy_MM_dd_HH_mm_ssToTimestampMs(expiresAt);
        long expirationTime = (expirationTimestamp - System.currentTimeMillis()) / (60 * 1000);
        if (expirationTime <= 0 || expirationTime > JwtUtils.MAX_API_KEY_EXPIRATION_MINUTES) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "ApiKey有效期必须在1分钟至90天之间");
        }

        UserApiKey userApiKey = new UserApiKey();
        userApiKey.setUserId(userId);
        userApiKey.setApp(app);
        userApiKey.setApiKey(null);
        userApiKey.setRemark(remark);
        userApiKey.setExpiredAt(expirationTimestamp);
        userApiKey.setEnable(enable != null ? enable : false);
        userApiKey.setCreateTime(DateUtil.getNow());
        userApiKey.setUpdateTime(DateUtil.getNow());

        int addResult = userApiKeyService.addApiKey(userApiKey);

        if (addResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }

        String apiKey;
        String apiKeyFingerprint;
        do {
            apiKey = JwtUtils.createApiKeyToken(user.getUsername(), expirationTime, userApiKey.getId());
            apiKeyFingerprint = JwtUtils.fingerprintApiKey(apiKey);
        } while (userApiKeyService.isApiKeyExists(apiKeyFingerprint));

        int resetResult = userApiKeyService.reset(userApiKey.getId(), apiKeyFingerprint);

        if (resetResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
        return apiKey;
    }

    /**
     * 分页查询ApiKey
     *
     * @param page  当前页
     * @param count 每页查询数量
     * @return 分页ApiKey列表
     */
    @GetMapping("/userApiKeys")
    @Operation(summary = "分页查询用户", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "page", description = "当前页", required = true)
    @Parameter(name = "count", description = "每页查询数量", required = true)
    @Transactional
    public PageInfo<UserApiKey> userApiKeys(@RequestParam(required = true) int page, @RequestParam(required = true) int count) {
        SecurityUtils.requireAdminLoginToken();
        return userApiKeyService.getUserApiKeys(page, count);
    }

    @PostMapping("/enable")
    @Operation(summary = "启用用户ApiKey", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "id", description = "用户ApiKeyId", required = true)
    @Transactional
    public void enable(@RequestParam(required = true) Integer id) {
        SecurityUtils.requireAdminLoginToken();
        // 获取当前登录用户id
        int currenRoleId = SecurityUtils.getUserInfo().getRole().getId();
        if (currenRoleId != 1) {
            // 只用角色id为1才可以管理UserApiKey
            throw new ControllerException(ErrorCode.ERROR403);
        }
        UserApiKey userApiKey = userApiKeyService.getUserApiKeyById(id);
        if (userApiKey == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "ApiKey不存在");
        }

        int enableResult = userApiKeyService.enable(id);

        if (enableResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
    }

    @PostMapping("/disable")
    @Operation(summary = "停用用户ApiKey", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "id", description = "用户ApiKeyId", required = true)
    @Transactional
    public void disable(@RequestParam(required = true) Integer id) {
        SecurityUtils.requireAdminLoginToken();
        // 获取当前登录用户id
        int currenRoleId = SecurityUtils.getUserInfo().getRole().getId();
        if (currenRoleId != 1) {
            // 只用角色id为1才可以管理UserApiKey
            throw new ControllerException(ErrorCode.ERROR403);
        }
        UserApiKey userApiKey = userApiKeyService.getUserApiKeyById(id);
        if (userApiKey == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "ApiKey不存在");
        }

        int disableResult = userApiKeyService.disable(id);

        if (disableResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
    }

    @PostMapping("/reset")
    @Operation(summary = "重置用户ApiKey", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "id", description = "用户ApiKeyId", required = true)
    @Transactional
    public String reset(@RequestParam(required = true) Integer id) {
        SecurityUtils.requireAdminLoginToken();
        // 获取当前登录用户id
        int currenRoleId = SecurityUtils.getUserInfo().getRole().getId();
        if (currenRoleId != 1) {
            // 只用角色id为1才可以管理UserApiKey
            throw new ControllerException(ErrorCode.ERROR403);
        }
        UserApiKey userApiKey = userApiKeyService.getUserApiKeyById(id);
        if (userApiKey == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "ApiKey不存在");
        }
        User user = userService.getUserById(userApiKey.getUserId());
        if (user == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "用户不存在");
        }
        if (userApiKey.getExpiredAt() <= 0) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(),
                    "旧版永久ApiKey禁止重置，请删除后重新创建");
        }
        long expirationTime = (userApiKey.getExpiredAt() - System.currentTimeMillis()) / (60 * 1000);
        if (expirationTime <= 0 || expirationTime > JwtUtils.MAX_API_KEY_EXPIRATION_MINUTES) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "ApiKey已失效或有效期不合法");
        }
        String apiKey;
        String apiKeyFingerprint;
        do {
            apiKey = JwtUtils.createApiKeyToken(user.getUsername(), expirationTime, userApiKey.getId());
            apiKeyFingerprint = JwtUtils.fingerprintApiKey(apiKey);
        } while (userApiKeyService.isApiKeyExists(apiKeyFingerprint));

        int resetResult = userApiKeyService.reset(id, apiKeyFingerprint);

        if (resetResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
        return apiKey;
    }

    @PostMapping("/remark")
    @Operation(summary = "备注用户ApiKey", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "id", description = "用户ApiKeyId", required = true)
    @Parameter(name = "remark", description = "用户ApiKey备注", required = false)
    @Transactional
    public void remark(@RequestParam(required = true) Integer id, @RequestParam(required = false) String remark) {
        SecurityUtils.requireAdminLoginToken();
        // 获取当前登录用户id
        int currenRoleId = SecurityUtils.getUserInfo().getRole().getId();
        if (currenRoleId != 1) {
            // 只用角色id为1才可以管理UserApiKey
            throw new ControllerException(ErrorCode.ERROR403);
        }
        UserApiKey userApiKey = userApiKeyService.getUserApiKeyById(id);
        if (userApiKey == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "ApiKey不存在");
        }
        int remarkResult = userApiKeyService.remark(id, remark);

        if (remarkResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除用户ApiKey", security = @SecurityRequirement(name = JwtUtils.HEADER))
    @Parameter(name = "id", description = "用户ApiKeyId", required = true)
    @Transactional
    public void delete(@RequestParam(required = true) Integer id) {
        SecurityUtils.requireAdminLoginToken();
        // 获取当前登录用户id
        int currenRoleId = SecurityUtils.getUserInfo().getRole().getId();
        if (currenRoleId != 1) {
            // 只用角色id为1才可以管理UserApiKey
            throw new ControllerException(ErrorCode.ERROR403);
        }
        UserApiKey userApiKey = userApiKeyService.getUserApiKeyById(id);
        if (userApiKey == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "ApiKey不存在");
        }

        int deleteResult = userApiKeyService.delete(id);

        if (deleteResult <= 0) {
            throw new ControllerException(ErrorCode.ERROR100);
        }
    }
}
