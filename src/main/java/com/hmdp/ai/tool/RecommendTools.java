package com.hmdp.ai.tool;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 餐厅推荐工具集
 * 根据位置、场景、预算进行精准推荐
 */
@Slf4j
@Component
public class RecommendTools {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 场景配置
    private static final SceneConfig[] SCENE_CONFIGS = {
        new SceneConfig("约会", 100, 300, 40, 0.4, 0.4, 0.2, "环境安静浪漫，非常适合约会"),
        new SceneConfig("情侣", 100, 300, 40, 0.4, 0.4, 0.2, "环境私密浪漫，适合情侣用餐"),
        new SceneConfig("聚餐", 50, 150, 35, 0.3, 0.5, 0.2, "菜品丰富多样，聚餐氛围好"),
        new SceneConfig("朋友聚会", 50, 150, 35, 0.3, 0.5, 0.2, "空间宽敞，适合多人聚餐"),
        new SceneConfig("商务", 200, 500, 45, 0.4, 0.4, 0.2, "高端大气，服务专业，适合商务宴请"),
        new SceneConfig("商务宴请", 200, 500, 45, 0.4, 0.4, 0.2, "档次高，环境好，适合商务场合"),
        new SceneConfig("家庭", 30, 100, 35, 0.4, 0.3, 0.3, "菜品丰富实惠，适合家庭聚餐"),
        new SceneConfig("家庭聚餐", 30, 100, 35, 0.4, 0.3, 0.3, "实惠温馨，适合家庭用餐"),
        new SceneConfig("生日", 100, 200, 40, 0.5, 0.3, 0.2, "有生日氛围，可提供定制服务"),
        new SceneConfig("默认", 0, 9999, 30, 0.3, 0.5, 0.2, "综合推荐")
    };

    /**
     * 最佳匹配推荐 - 根据位置、场景、预算综合推荐
     */
    @Tool(
        name = "recommend/bestMatch",
        description = "根据位置、场景、人均预算进行最佳匹配推荐餐厅。" +
                      "必须提供三个参数：location(位置描述)、scene(用餐场景：约会/聚餐/商务/家庭/生日)、avgPrice(人均预算)。" +
                      "返回Markdown格式的推荐列表，包含推荐理由。"
    )
    public String bestMatch(
        @ToolParam(description = "用户位置描述，如'西湖附近'、'市中心'、'延安路'") String location,
        @ToolParam(description = "用餐场景：约会/情侣/聚餐/朋友聚会/商务/商务宴请/家庭/家庭聚餐/生日") String scene,
        @ToolParam(description = "人均预算（元），如100、200") Integer avgPrice
    ) {
        log.info("Tool called: recommend_bestMatch, location={}, scene={}, avgPrice={}", location, scene, avgPrice);

        if (location == null || location.isEmpty()) {
            return "❌ 缺少位置信息。请告诉我在哪个区域用餐。";
        }
        if (scene == null || scene.isEmpty()) {
            return "❌ 缺少场景信息。请告诉我是什么场合用餐，如约会、聚餐、商务等。";
        }
        if (avgPrice == null || avgPrice <= 0) {
            return "❌ 缺少预算信息。请告诉我人均预算大概是多少。";
        }

        // 获取场景配置
        SceneConfig config = getSceneConfig(scene);

        // 获取所有餐厅
        Result result = shopService.queryAll();
        if (!result.isSuccess()) {
            return "获取餐厅列表失败，请稍后再试。";
        }

        List<Shop> allShops = (List<Shop>) result.getData();

        // 筛选和评分
        List<ShopRecommend> recommendations = allShops.stream()
            .filter(shop -> shop.getAvgPrice() != null && shop.getScore() != null)
            .filter(shop -> {
                long priceRange = Math.abs(shop.getAvgPrice() - avgPrice);
                // 价格差距不超过50%的预算
                return priceRange <= avgPrice * 0.5 ||
                       (shop.getAvgPrice() >= config.minPrice && shop.getAvgPrice() <= config.maxPrice);
            })
            .filter(shop -> shop.getScore() >= config.minScore)
            .map(shop -> {
                double score = calculateMatchScore(shop, avgPrice, config);
                double distance = estimateDistance(shop, location);
                return new ShopRecommend(shop, score, distance);
            })
            .sorted(Comparator.comparingDouble(ShopRecommend::getScore).reversed())
            .limit(5)
            .collect(Collectors.toList());

        if (recommendations.isEmpty()) {
            return formatNoResult(location, scene, avgPrice);
        }

        return formatMarkdownRecommendation(recommendations, location, scene, avgPrice, config);
    }

    /**
     * 根据场景推荐餐厅（简化版）
     */
    @Tool(
        name = "recommend/byScene",
        description = "根据场景推荐餐厅，场景类型包括：约会、聚餐、商务、家庭、生日。" +
                      "注意：推荐前需要先确认用户的位置和预算信息。"
    )
    public String byScene(String scene) {
        log.info("Tool called: recommend/byScene, scene={}", scene);

        SceneConfig config = getSceneConfig(scene);

        Result result = shopService.queryAll();
        if (!result.isSuccess()) {
            return "获取餐厅列表失败";
        }

        List<Shop> shops = (List<Shop>) result.getData();
        List<Shop> filtered = shops.stream()
            .filter(s -> s.getAvgPrice() != null
                && s.getAvgPrice() >= config.minPrice
                && s.getAvgPrice() <= config.maxPrice
                && s.getScore() >= config.minScore)
            .sorted(Comparator.comparing(Shop::getScore).reversed())
            .limit(5)
            .collect(Collectors.toList());

        return formatSceneList(filtered, scene, config);
    }

    /**
     * 根据预算推荐餐厅
     */
    @Tool(
        name = "recommend/byBudget",
        description = "根据预算范围推荐餐厅。参数：minPrice最低人均，maxPrice最高人均。" +
                      "注意：推荐前需要先确认用户的位置和场景信息。"
    )
    public String byBudget(Integer minPrice, Integer maxPrice) {
        log.info("Tool called: recommend/byBudget, minPrice={}, maxPrice={}", minPrice, maxPrice);

        if (minPrice == null) minPrice = 0;
        if (maxPrice == null) maxPrice = 9999;

        Result result = shopService.queryAll();
        if (!result.isSuccess()) {
            return "获取餐厅列表失败";
        }

        List<Shop> shops = (List<Shop>) result.getData();
        Integer finalMinPrice = minPrice;
        Integer finalMaxPrice = maxPrice;
        List<Shop> filtered = shops.stream()
            .filter(s -> s.getAvgPrice() != null
                && s.getAvgPrice() >= finalMinPrice
                && s.getAvgPrice() <= finalMaxPrice)
            .sorted(Comparator.comparing(Shop::getScore).reversed())
            .limit(10)
            .collect(Collectors.toList());

        return formatBudgetList(filtered, minPrice, maxPrice);
    }

    /**
     * 推荐附近餐厅
     */
    @Tool(
        name = "recommend/nearby",
        description = "根据用户坐标推荐附近餐厅。参数：x经度、y纬度、radius搜索半径（米，默认3000）、limit返回数量（默认5）"
    )
    public String nearby(Double x, Double y, Integer radius, Integer limit) {
        log.info("Tool called: recommend/nearby, x={}, y={}, radius={}, limit={}", x, y, radius, limit);

        if (x == null || y == null) {
            return "❌ 缺少坐标信息";
        }
        if (radius == null) radius = 3000;
        if (limit == null) limit = 5;

        List<Shop> nearbyShops = new ArrayList<>();

        String key = RedisConstants.SHOP_GEO_KEY + 1;
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key, GeoReference.fromCoordinate(x, y),
                    new Distance(radius),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(limit));

            if (results != null) {
                for (GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult : results.getContent()) {
                    String shopIdStr = geoResult.getContent().getName();
                    try {
                        Long shopId = Long.parseLong(shopIdStr);
                        Result shopResult = shopService.queryById(shopId);
                        if (shopResult.isSuccess() && shopResult.getData() != null) {
                            Shop shop = (Shop) shopResult.getData();
                            shop.setDistance(geoResult.getDistance().getValue());
                            nearbyShops.add(shop);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("Redis GEO查询失败", e);
        }

        if (nearbyShops.isEmpty()) {
            Result result = shopService.queryAll();
            if (result.isSuccess()) {
                List<Shop> allShops = (List<Shop>) result.getData();
                for (Shop shop : allShops) {
                    if (shop.getX() != null && shop.getY() != null) {
                        double distance = calculateDistance(x, y, shop.getX(), shop.getY());
                        if (distance <= radius) {
                            shop.setDistance(distance);
                            nearbyShops.add(shop);
                        }
                    }
                }
                nearbyShops.sort(Comparator.comparing(Shop::getDistance));
                if (nearbyShops.size() > limit) {
                    nearbyShops = nearbyShops.subList(0, limit);
                }
            }
        }

        return formatNearbyList(nearbyShops);
    }

    /**
     * 推荐热门餐厅
     */
    @Tool(
        name = "recommend/popular",
        description = "推荐热门餐厅，按评分和销量综合排序"
    )
    public String popular(Integer limit) {
        log.info("Tool called: recommend/popular, limit={}", limit);
        if (limit == null) limit = 10;

        Result result = shopService.queryAll();
        if (!result.isSuccess()) return "获取餐厅列表失败";

        List<Shop> shops = (List<Shop>) result.getData();
        List<Shop> popular = shops.stream()
            .sorted((s1, s2) -> {
                double score1 = s1.getScore() * 0.6 + Math.min(s1.getSold(), 1000) * 0.4 / 10;
                double score2 = s2.getScore() * 0.6 + Math.min(s2.getSold(), 1000) * 0.4 / 10;
                return Double.compare(score2, score1);
            })
            .limit(limit)
            .collect(Collectors.toList());

        return formatPopularList(popular);
    }

    // ========== 私有方法 ==========

    private SceneConfig getSceneConfig(String scene) {
        for (SceneConfig config : SCENE_CONFIGS) {
            if (config.name.equals(scene) || config.name.equals("默认")) {
                return config;
            }
        }
        return SCENE_CONFIGS[SCENE_CONFIGS.length - 1]; // 返回默认配置
    }

    private double calculateMatchScore(Shop shop, int avgPrice, SceneConfig config) {
        double score = 0;

        // 评分权重
        score += (shop.getScore() / 50.0) * config.scoreWeight * 100;

        // 价格匹配权重
        long priceDiff = Math.abs(shop.getAvgPrice() - avgPrice);
        double priceMatch = 1 - (priceDiff / (avgPrice * 0.5 + 50));
        priceMatch = Math.max(0, Math.min(1, priceMatch));
        score += priceMatch * config.priceWeight * 100;

        // 场景适配权重（评分是否达到场景要求）
        double sceneMatch = shop.getScore() >= config.minScore ? 1 : 0.5;
        score += sceneMatch * config.sceneWeight * 100;

        // 销量加分（最高20分）
        score += Math.min(shop.getSold() / 50.0, 20);

        return score;
    }

    private double estimateDistance(Shop shop, String location) {
        // 简化估算：根据地址中是否包含location关键词
        if (shop.getAddress() != null && shop.getAddress().contains(location)) {
            return 0.5; // 很近
        }
        return 3.0; // 默认距离
    }

    private double calculateDistance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy) * 111000;
    }

    private String formatDistance(Double distance) {
        if (distance == null) return "未知";
        if (distance < 1000) return String.format("%.0f米", distance);
        return String.format("%.1f公里", distance / 1000);
    }

    // ========== Markdown格式化方法 ==========

    private String formatMarkdownRecommendation(List<ShopRecommend> recommendations,
            String location, String scene, int avgPrice, SceneConfig config) {
        StringBuilder sb = new StringBuilder();

        // 开头装饰
        sb.append("## 🍽️ 餐厅推荐结果\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // 用户需求卡片
        sb.append("### 📋 您的需求\n\n");
        sb.append("| 🗺️ 位置 | 🎉 场景 | 💵 人均预算 |\n");
        sb.append("|:-------:|:-------:|:-----------:|\n");
        sb.append("| ").append(location).append(" | ").append(scene).append(" | ").append(avgPrice).append("元 |\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // 收集店铺ID用于触发指令
        List<Long> shopIds = new ArrayList<>();

        // 最佳推荐
        if (recommendations.size() > 0) {
            ShopRecommend best = recommendations.get(0);
            shopIds.add(best.shop.getId());
            sb.append("### 🏆 最佳推荐\n\n");
            sb.append("**").append(best.shop.getName()).append("**\n\n");

            // 信息卡片
            sb.append("> ┌─────────────────────────────────────┐\n");
            sb.append("> │  ⭐ **评分**：").append(String.format("%.1f", best.shop.getScore() / 10.0)).append(" 分 ").append(getStarDisplay(best.shop.getScore())).append("\n");
            sb.append("> │  💰 **人均**：").append(best.shop.getAvgPrice()).append(" 元 ").append(getPriceLevel(Math.toIntExact(best.shop.getAvgPrice()))).append("\n");
            sb.append("> │  📍 **距离**：约 ").append(String.format("%.1f", best.distance)).append(" km\n");
            sb.append("> │  🏠 **地址**：").append(best.shop.getAddress()).append("\n");
            sb.append("> └─────────────────────────────────────┘\n\n");

            // 推荐理由卡片
            sb.append("#### ✨ 推荐理由\n\n");
            sb.append("```plaintext\n");
            sb.append(config.reasonTemplate).append("\n");
            sb.append("✓ 评分高达 ").append(String.format("%.1f", best.shop.getScore() / 10.0)).append(" 分，口碑极佳\n");
            sb.append("✓ 人均 ").append(best.shop.getAvgPrice()).append(" 元，符合您的预算\n");
            sb.append("✓ 位于 ").append(location).append(" 附近，交通便利\n");
            sb.append("```\n\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        }

        // 备选推荐
        if (recommendations.size() > 1) {
            sb.append("### 🥈 其他推荐\n\n");
            for (int i = 1; i < Math.min(3, recommendations.size()); i++) {
                ShopRecommend sr = recommendations.get(i);
                shopIds.add(sr.shop.getId());
                sb.append("**№.").append(i + 1).append(" ").append(sr.shop.getName()).append("**\n\n");
                sb.append("| 评分 | 人均 | 地址 |\n");
                sb.append("|:----:|:----:|:-----|\n");
                sb.append("| ⭐ ").append(String.format("%.1f", sr.shop.getScore() / 10.0)).append(" | 💰 ").append(sr.shop.getAvgPrice()).append("元 | ").append(truncateAddress(sr.shop.getAddress())).append(" |\n\n");
            }
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        }

        // 温馨提示
        sb.append("### 💡 温馨提示\n\n");
        sb.append("```plaintext\n");
        sb.append("• 建议 ").append(scene).append(" 场景提前预约，避免排队\n");
        sb.append("• 点击餐厅名称可查看详情和优惠券\n");
        sb.append("• 如需更多推荐，告诉我调整筛选条件\n");
        sb.append("```\n\n");

        // 操作引导
        sb.append("### 🤔 接下来可以\n\n");
        sb.append("> 📌 回复 **\"查看详情\"** 了解更多信息\n");
        sb.append("> 📞 回复 **\"预约\"** 帮您预订座位\n");
        sb.append("> 🔍 回复 **\"换一个\"** 重新推荐其他餐厅\n\n");

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("✨ *祝您用餐愉快！* ✨\n");

        // 添加预约表单触发指令（前端检测此指令显示表单）
        if (!shopIds.isEmpty()) {
            sb.append("\n[RESERVATION_FORM:shopIds=");
            sb.append(shopIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
            sb.append("]");
        }

        return sb.toString();
    }

    private String getStarDisplay(int score) {
        int stars = score / 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(stars, 5); i++) {
            sb.append("★");
        }
        for (int i = stars; i < 5; i++) {
            sb.append("☆");
        }
        return sb.toString();
    }

    private String getPriceLevel(int avgPrice) {
        if (avgPrice < 50) return "【实惠】";
        if (avgPrice < 100) return "【适中】";
        if (avgPrice < 200) return "【中档】";
        if (avgPrice < 400) return "【高档】";
        return "【奢华】";
    }

    private String truncateAddress(String address) {
        if (address == null) return "未知";
        if (address.length() > 20) {
            return address.substring(0, 17) + "...";
        }
        return address;
    }

    private String formatNoResult(String location, String scene, int avgPrice) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 😔 没有找到匹配的餐厅\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        sb.append("在 **").append(location).append("** 附近，暂时没有找到符合以下条件的餐厅：\n\n");
        sb.append("> • 场景：").append(scene).append("\n");
        sb.append("> • 人均：").append(avgPrice).append(" 元\n\n");

        sb.append("### 💡 建议调整筛选条件\n\n");
        sb.append("```plaintext\n");
        sb.append("• 扩大预算范围 → ").append(avgPrice - 30).append("-").append(avgPrice + 80).append(" 元\n");
        sb.append("• 更换用餐场景 → 比如改为「聚餐」「家庭」\n");
        sb.append("• 更换位置区域 → 比如改为「市中心」「武林广场」\n");
        sb.append("```\n\n");

        sb.append("> 🔍 请告诉我想如何调整，我来重新推荐！\n");

        return sb.toString();
    }

    private String formatSceneList(List<Shop> shops, String scene, SceneConfig config) {
        if (shops.isEmpty()) return "暂无适合" + scene + "场景的餐厅";

        StringBuilder sb = new StringBuilder();
        sb.append("为您推荐以下适合").append(scene).append("的餐厅：\n\n");
        for (int i = 0; i < shops.size(); i++) {
            Shop shop = shops.get(i);
            sb.append(i + 1).append(". **").append(shop.getName()).append("**\n");
            sb.append("   - 评分：⭐ ").append(shop.getScore() / 10.0).append("分\n");
            sb.append("   - 人均：💰 ").append(shop.getAvgPrice()).append("元\n");
            sb.append("   - 地址：📍 ").append(shop.getAddress()).append("\n\n");
        }
        return sb.toString();
    }

    private String formatBudgetList(List<Shop> shops, int minPrice, int maxPrice) {
        if (shops.isEmpty()) return "暂无人均" + minPrice + "-" + maxPrice + "元的餐厅";

        StringBuilder sb = new StringBuilder();
        sb.append("为您推荐以下人均").append(minPrice).append("-").append(maxPrice).append("元的餐厅：\n\n");
        for (int i = 0; i < shops.size(); i++) {
            Shop shop = shops.get(i);
            sb.append(i + 1).append(". **").append(shop.getName()).append("**\n");
            sb.append("   - 评分：⭐ ").append(shop.getScore() / 10.0).append("分\n");
            sb.append("   - 人均：💰 ").append(shop.getAvgPrice()).append("元\n");
            sb.append("   - 地址：📍 ").append(shop.getAddress()).append("\n\n");
        }
        return sb.toString();
    }

    private String formatNearbyList(List<Shop> shops) {
        if (shops.isEmpty()) return "附近暂无餐厅";

        StringBuilder sb = new StringBuilder();
        sb.append("为您推荐以下附近餐厅：\n\n");
        for (int i = 0; i < shops.size(); i++) {
            Shop shop = shops.get(i);
            sb.append(i + 1).append(". **").append(shop.getName()).append("**\n");
            sb.append("   - 距离：📍 ").append(formatDistance(shop.getDistance())).append("\n");
            sb.append("   - 评分：⭐ ").append(shop.getScore() / 10.0).append("分\n");
            sb.append("   - 人均：💰 ").append(shop.getAvgPrice()).append("元\n\n");
        }
        return sb.toString();
    }

    private String formatPopularList(List<Shop> shops) {
        if (shops.isEmpty()) return "暂无热门餐厅";

        StringBuilder sb = new StringBuilder();
        sb.append("为您推荐以下热门餐厅：\n\n");
        for (int i = 0; i < shops.size(); i++) {
            Shop shop = shops.get(i);
            sb.append(i + 1).append(". **").append(shop.getName()).append("**\n");
            sb.append("   - 评分：⭐ ").append(shop.getScore() / 10.0).append("分\n");
            sb.append("   - 人均：💰 ").append(shop.getAvgPrice()).append("元\n");
            sb.append("   - 销量：🔥 ").append(shop.getSold()).append("单\n\n");
        }
        return sb.toString();
    }

    // ========== 内部类 ==========

    private static class SceneConfig {
        String name;
        int minPrice;
        int maxPrice;
        int minScore;
        double scoreWeight;
        double priceWeight;
        double sceneWeight;
        String reasonTemplate;

        SceneConfig(String name, int minPrice, int maxPrice, int minScore,
                    double scoreWeight, double priceWeight, double sceneWeight, String reasonTemplate) {
            this.name = name;
            this.minPrice = minPrice;
            this.maxPrice = maxPrice;
            this.minScore = minScore;
            this.scoreWeight = scoreWeight;
            this.priceWeight = priceWeight;
            this.sceneWeight = sceneWeight;
            this.reasonTemplate = reasonTemplate;
        }
    }

    private static class ShopRecommend {
        Shop shop;
        double score;
        double distance;

        ShopRecommend(Shop shop, double score, double distance) {
            this.shop = shop;
            this.score = score;
            this.distance = distance;
        }

        double getScore() { return score; }
    }
}