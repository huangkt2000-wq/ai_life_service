package com.hmdp.config;

import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Sa-Token 配置类
 * Sa-Token 会自动使用 Spring 的 Redis 配置
 */
@Slf4j
@Configuration
public class SaTokenConfig {

    @PostConstruct 
    public void init() {
        log.info("Sa-Token 配置已加载，token 名称: authorization");
        log.info("Sa-Token 会自动从请求头 'authorization' 读取 token");
    }
}