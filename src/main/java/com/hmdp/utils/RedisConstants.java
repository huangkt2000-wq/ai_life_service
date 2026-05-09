package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    // AI Agent 相关 Key
    public static final String AI_MEMORY_KEY = "ai:memory:";
    public static final Long AI_MEMORY_TTL = 1800L; // 30分钟
    public static final String AI_KNOWLEDGE_KEY = "knowledge:shop:";
    public static final String AI_KNOWLEDGE_IDX = "knowledge_idx";

    // Blog Draft 相关 Key
    public static final String BLOG_DRAFT_KEY = "blog:draft:";
    public static final Long BLOG_DRAFT_TTL = 3600L; // 1小时
}
