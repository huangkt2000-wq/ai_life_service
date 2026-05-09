package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Reservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 餐厅预约服务接口
 */
public interface IReservationService extends IService<Reservation> {

    /**
     * 创建预约
     */
    Result createReservation(Long userId, Long shopId, String contactName, String contactPhone,
                            LocalDateTime reservationTime, Integer peopleCount, String remark);

    /**
     * 查询可用时段
     */
    Result checkAvailable(Long shopId, LocalDate date);

    /**
     * 取消预约
     */
    Result cancelReservation(Long reservationId, Long userId);

    /**
     * 获取用户预约列表
     */
    Result getUserReservations(Long userId);

    /**
     * 获取预约详情
     */
    Result getReservationById(Long id);
}