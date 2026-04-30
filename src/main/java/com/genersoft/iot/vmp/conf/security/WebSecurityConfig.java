package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.UserSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * 配置Spring Security
 *
 * @author lin
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
@Order(1)
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

    private final static Logger logger = LoggerFactory.getLogger(WebSecurityConfig.class);

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private DefaultUserDetailsServiceImpl userDetailsService;
    /**
     * 登出成功的处理
     */
    @Autowired
    private LogoutHandler logoutHandler;
    /**
     * 未登录的处理
     */
    @Autowired
    private AnonymousAuthenticationEntryPoint anonymousAuthenticationEntryPoint;
    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;


    /**
     * 描述: 静态资源放行，这里的放行，是不走 Spring Security 过滤器链
     **/
    @Override
    public void configure(WebSecurity web) {
        // 不忽略静态资源，确保所有响应都经过安全过滤器链并写入安全响应头
    }

    /**
     * 配置认证方式
     *
     * @param auth
     * @throws Exception
     */
    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        // 设置不隐藏 未找到用户异常
        provider.setHideUserNotFoundExceptions(true);
        // 用户认证service - 查询数据库的逻辑
        provider.setUserDetailsService(userDetailsService);
        // 设置密码加密算法
        provider.setPasswordEncoder(passwordEncoder());
        auth.authenticationProvider(provider);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.headers()
                .addHeaderWriter((request, response) -> {
                    response.setHeader("X-Frame-Options", "DENY");
                    response.setHeader("X-Permitted-Cross-Domain-Policies", "none");
                    response.setHeader("X-XSS-Protection", "1; mode=block");
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    response.setHeader("X-Download-Options", "noopen");
                    response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                    response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                })
                .and().cors().configurationSource(configurationSource())
                .and().csrf().disable()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)

                // 配置拦截规则
                .and()
                .authorizeRequests()
                .antMatchers(HttpMethod.OPTIONS, "/**").denyAll()
                .antMatchers(userSetting.getInterfaceAuthenticationExcludes().toArray(new String[0])).permitAll()
                .antMatchers(
                        "/",
                        "/index.html",
                        "/static/**",
                        "/js/**",
                        "/favicon.ico",
                        "/api/user/login",
                        "/index/hook/**",
                        "/index/hook/abl/**",
                        "/api/device/query/snap/**",
                        "/record_proxy/*/**",
                        "/api/emit")
                .permitAll()
                .anyRequest().authenticated()
                // 异常处理器
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(anonymousAuthenticationEntryPoint)
                .and().logout().logoutUrl("/api/user/logout").permitAll()
                .logoutSuccessHandler(logoutHandler)
        ;
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    }

    CorsConfigurationSource configurationSource() {
        // 配置跨域
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedHeaders(Arrays.asList("*"));
        corsConfiguration.setAllowedMethods(Arrays.asList("*"));
        corsConfiguration.setMaxAge(3600L);
        if (userSetting.getAllowedOrigins() != null && !userSetting.getAllowedOrigins().isEmpty()) {
            corsConfiguration.setAllowCredentials(true);
            corsConfiguration.setAllowedOrigins(userSetting.getAllowedOrigins());
        }else {
            corsConfiguration.setAllowCredentials(false);
            corsConfiguration.setAllowedOrigins(Collections.singletonList(CorsConfiguration.ALL));
        }

        corsConfiguration.setExposedHeaders(Arrays.asList(JwtUtils.getHeader()));

        UrlBasedCorsConfigurationSource url = new UrlBasedCorsConfigurationSource();
        url.registerCorsConfiguration("/**", corsConfiguration);
        return url;
    }

    /**
     * 描述: 密码加密算法 BCrypt 推荐使用
     **/
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 描述: 注入AuthenticationManager管理器
     **/
    @Override
    @Bean
    public AuthenticationManager authenticationManager() throws Exception {
        return super.authenticationManager();
    }
}
