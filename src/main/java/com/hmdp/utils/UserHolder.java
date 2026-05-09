package com.hmdp.utils;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.dto.UserDTO;

/**
 * 用户上下文工具类
 * 使用 Sa-Token 的 Session 存储用户信息
 * ThreadLocal 用于当前请求线程访问
 */
public class UserHolder {

    private static final String USER_SESSION_KEY = "user";

    // ThreadLocal 存储当前请求的用户信息
    private static final ThreadLocal<UserDTO> tlUser = new ThreadLocal<>();

    /**
     * 获取当前登录用户（从 ThreadLocal）
     */
    public static UserDTO getUser() {
        return tlUser.get();
    }

    /**
     * 保存用户信息到 ThreadLocal（由拦截器调用）
     */
    public static void saveUser(UserDTO user) {
        tlUser.set(user);
    }

    /**
     * 清理 ThreadLocal（请求结束时由拦截器调用）
     */
    public static void removeUser() {
        tlUser.remove();
    }

    /**
     * 获取当前 token
     */
    public static String getToken() {
        return StpUtil.getTokenValue();
    }

    /**
     * 获取当前用户 ID
     */
    public static Long getUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 判断是否已登录
     */
    public static boolean isLogin() {
        return StpUtil.isLogin();
    }
}