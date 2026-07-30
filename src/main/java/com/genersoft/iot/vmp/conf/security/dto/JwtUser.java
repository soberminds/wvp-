package com.genersoft.iot.vmp.conf.security.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

public class JwtUser {

    public enum TokenStatus{
        /**
         * 正常的使用状态
         */
        NORMAL,
        /**
         * 过期而失效
         */
        EXPIRED,
        /**
         * 即将过期
         */
        EXPIRING_SOON,
        /**
         * 异常
         */
        EXCEPTION
    }

    private int userId;
    private String userName;

    private String password;

    private int roleId;

    private TokenStatus status;

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public TokenStatus getStatus() {
        return status;
    }

    public void setStatus(TokenStatus status) {
        this.status = status;
    }

    @JsonIgnore
    @JSONField(serialize = false)
    @Schema(hidden = true)
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getRoleId() {
        return roleId;
    }

    public void setRoleId(int roleId) {
        this.roleId = roleId;
    }
}
