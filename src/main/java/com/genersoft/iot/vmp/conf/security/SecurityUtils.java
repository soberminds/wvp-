package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.security.dto.LoginUser;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import org.springframework.security.core.AuthenticationException;
import java.time.LocalDateTime;

public class SecurityUtils {

    public static final String LOGIN_TOKEN_AUTHORITY = "TOKEN_LOGIN";
    public static final String API_KEY_AUTHORITY = "TOKEN_API_KEY";

    /**
     * 描述根据账号密码进行调用security进行认证授权 主动调
     * 用AuthenticationManager的authenticate方法实现
     * 授权成功后将用户信息存入SecurityContext当中
     * @param username 用户名
     * @param password 密码
     * @param authenticationManager 认证授权管理器,
     * @see  AuthenticationManager
     * @return UserInfo  用户信息
     */
    public static LoginUser login(String username, String password, AuthenticationManager authenticationManager) throws AuthenticationException {
        //使用security框架自带的验证token生成器  也可以自定义。
        UsernamePasswordAuthenticationToken token =new UsernamePasswordAuthenticationToken(username,password);
        //认证 如果失败，这里会自动异常后返回，所以这里不需要判断返回值是否为空，确定是否登录成功
        Authentication authenticate = authenticationManager.authenticate(token);
        LoginUser user = (LoginUser) authenticate.getPrincipal();

        SecurityContextHolder.getContext().setAuthentication(authenticate);

        return user;
    }

    /**
     * 获取当前登录的所有认证信息
     * @return
     */
    public static Authentication getAuthentication(){
        SecurityContext context = SecurityContextHolder.getContext();
        return context.getAuthentication();
    }

    /**
     * 获取当前登录用户信息
     * @return
     */
    public static LoginUser getUserInfo(){
        Authentication authentication = getAuthentication();
        if(authentication!=null){
            Object principal = authentication.getPrincipal();
            if(principal!=null && !"anonymousUser".equals(principal.toString())){

                User user = (User) principal;
                return new LoginUser(user, LocalDateTime.now());
            }
        }
        return null;
    }

    /**
     * 获取当前登录用户ID
     * @return
     */
    public static int getUserId(){
        LoginUser user = getUserInfo();
        return user.getId();
    }

    public static LoginUser requireAdmin() {
        LoginUser user = getUserInfo();
        if (user == null || user.getRole() == null || user.getRole().getId() != 1) {
            throw new ControllerException(ErrorCode.ERROR403);
        }
        return user;
    }

    public static LoginUser requireLoginToken() {
        Authentication authentication = getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .noneMatch(LOGIN_TOKEN_AUTHORITY::equals)) {
            throw new ControllerException(ErrorCode.ERROR403.getCode(), "此操作必须使用网页登录凭据");
        }
        return getUserInfo();
    }

    public static LoginUser requireAdminLoginToken() {
        LoginUser user = requireLoginToken();
        if (user == null || user.getRole() == null || user.getRole().getId() != 1) {
            throw new ControllerException(ErrorCode.ERROR403);
        }
        return user;
    }

    /**
     * 生成BCryptPasswordEncoder密码
     *
     * @param password 密码
     * @return 加密字符串
     */
    public static String encryptPassword(String password) {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        return passwordEncoder.encode(password);
    }
}
