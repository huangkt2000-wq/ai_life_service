package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

    /**
     * 根据名称关键字查询商铺
     * @param name 名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    Result queryShopByName(String name, Integer current);

    /**
     * 查询所有商铺列表
     * @return 所有商铺列表
     */
    Result queryAll();
}
