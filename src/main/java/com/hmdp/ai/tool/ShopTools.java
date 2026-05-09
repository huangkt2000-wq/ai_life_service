package com.hmdp.ai.tool;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 店铺工具集
 * 实现店铺查询相关的 Tool
 */
@Slf4j
@Component
public class ShopTools {

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    /**
     * 根据ID查询商铺详情
     */
    @Tool(
            name = "shop/getById",
            description = "根据ID查询商铺详情，包含营业时间、评分、地址、人均消费等信息"
    )
    public String getById(Long id) {
        log.info("Tool called: shop/getById, id={}", id);
        Result result = shopService.queryById(id);
        if (result.isSuccess()) {
            Shop shop = (Shop) result.getData();
            return formatShopInfo(shop);
        } else {
            return "未找到该商铺";
        }
    }

    /**
     * 按名称搜索商铺
     */
    @Tool(
            name = "shop/searchByName",
            description = "根据商铺名称关键字搜索商铺列表"
    )
    public String searchByName(String name) {
        log.info("Tool called: shop/searchByName, name={}", name);
        Result result = shopService.queryShopByName(name, 1);
        if (result.isSuccess()) {
            List<Shop> shops = (List<Shop>) result.getData();
            return formatShopList(shops);
        } else {
            return "未找到匹配的商铺";
        }
    }

    /**
     * 按类型查询商铺
     */
    @Tool(
            name = "shop/getByType",
            description = "根据商铺类型ID查询商铺列表，支持按距离排序"
    )
    public String getByType(Integer typeId, Double x, Double y) {
        log.info("Tool called: shop/getByType, typeId={}, x={}, y={}", typeId, x, y);
        Result result = shopService.queryShopByType(typeId, 1, x, y);
        if (result.isSuccess()) {
            List<Shop> shops = (List<Shop>) result.getData();
            return formatShopList(shops);
        } else {
            return "未找到该类型的商铺";
        }
    }

    /**
     * 获取所有商铺分类
     */
    @Tool(
            name = "shop/getTypes",
            description = "获取所有商铺分类列表"
    )
    public String getTypes() {
        log.info("Tool called: shop/getTypes");
        List<ShopType> types = shopTypeService.list();
        if (types == null || types.isEmpty()) {
            return "获取分类失败";
        }

        StringBuilder sb = new StringBuilder("商铺分类列表：\n");
        for (ShopType type : types) {
            sb.append("- ").append(type.getName())
                    .append(" (ID: ").append(type.getId()).append(")\n");
        }
        return sb.toString();
    }

    private String formatShopInfo(Shop shop) {
        if (shop == null) {
            return "商铺不存在";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(shop.getName()).append("】\n");
        sb.append("评分：").append(shop.getScore() / 10.0).append("分\n");
        sb.append("人均消费：").append(shop.getAvgPrice()).append("元\n");
        sb.append("营业时间：").append(shop.getOpenHours()).append("\n");
        sb.append("地址：").append(shop.getAddress()).append("\n");
        sb.append("商圈：").append(shop.getArea()).append("\n");
        sb.append("销量：").append(shop.getSold()).append("\n");
        sb.append("评论数：").append(shop.getComments()).append("\n");
        return sb.toString();
    }

    private String formatShopList(List<Shop> shops) {
        if (shops == null || shops.isEmpty()) {
            return "暂无商铺";
        }
        StringBuilder sb = new StringBuilder("找到以下商铺：\n\n");
        for (int i = 0; i < shops.size(); i++) {
            Shop shop = shops.get(i);
            sb.append(i + 1).append(". 【").append(shop.getName()).append("】\n");
            sb.append("   评分：").append(shop.getScore() / 10.0).append("分 | ");
            sb.append("人均：").append(shop.getAvgPrice()).append("元\n");
            sb.append("   地址：").append(shop.getAddress()).append("\n\n");
        }
        return sb.toString();
    }
}
