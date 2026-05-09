package com.hmdp.utils;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Sa-Token 登录拦截器
 * 同时从 Sa-Token Session 中恢复用户信息到 UserHolder
 */
@Slf4j
public class SaTokenLoginInterceptor implements HandlerInterceptor {

    private static final String USER_SESSION_KEY = "user";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断是否已登录
        if (!StpUtil.isLogin()) {
            // 未登录，返回 401
            response.setStatus(401);
            return false;
        }

        // 已登录，从 Sa-Token Session 中获取用户信息
        UserDTO user = (UserDTO) StpUtil.getSession().get(USER_SESSION_KEY);
        if (user == null) {
            log.warn("用户已登录但 Session 中没有用户信息，token={}", StpUtil.getTokenValue());
            response.setStatus(401);
            return false;
        }

        // 将用户信息放入 UserHolder（ThreadLocal）
        UserHolder.saveUser(user);

        // 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理 ThreadLocal
        UserHolder.removeUser();
    }
}