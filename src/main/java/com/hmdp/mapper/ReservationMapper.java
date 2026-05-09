package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Reservation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 餐厅预约 Mapper
 */
@Mapper
public interface ReservationMapper extends BaseMapper<Reservation> {
}