package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ReservationMapper;
import com.hmdp.service.IReservationService;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 餐厅预约服务实现
 */
@Slf4j
@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation> implements IReservationService {

    @Resource
    private IShopService shopService;

    // 预约状态常量
    private static final int STATUS_PENDING = 1;     // 待确认
    private static final int STATUS_CONFIRMED = 2;   // 已确认
    private static final int STATUS_CANCELLED = 3;   // 已取消
    private static final int STATUS_COMPLETED = 4;   // 已完成

    // 默认时段配置
    private static final List<LocalTime> DEFAULT_TIME_SLOTS = List.of(
            LocalTime.of(10, 0),
            LocalTime.of(12, 0),
            LocalTime.of(14, 0),
            LocalTime.of(16, 0),
            LocalTime.of(18, 0),
            LocalTime.of(20, 0)
    );

    @Override
    @Transactional
    public Result createReservation(Long userId, Long shopId, String contactName, String contactPhone,
                                    LocalDateTime reservationTime, Integer peopleCount, String remark) {
        log.info("创建预约: userId={}, shopId={}, name={}, phone={}, time={}, peopleCount={}",
                userId, shopId, contactName, contactPhone, reservationTime, peopleCount);

        // 1. 检查商铺是否存在
        Result shopResult = shopService.queryById(shopId);
        if (!shopResult.isSuccess()) {
            return Result.fail("商铺不存在");
        }

        // 2. 检查是否已有重复预约（同一用户、同一餐厅、同一天、未取消）
        LocalDate reservationDate = reservationTime.toLocalDate();
        LocalDateTime startOfDay = reservationDate.atStartOfDay();
        LocalDateTime endOfDay = reservationDate.atTime(LocalTime.MAX);

        Reservation existingReservation = query()
                .eq("user_id", userId)
                .eq("shop_id", shopId)
                .between("reservation_time", startOfDay, endOfDay)
                .ne("status", STATUS_CANCELLED)
                .one();

        if (existingReservation != null) {
            return Result.fail("您当天已预约过该餐厅，请勿重复预约");
        }

        // 3. 创建预约
        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        reservation.setShopId(shopId);
        reservation.setContactName(contactName);
        reservation.setContactPhone(contactPhone);
        reservation.setReservationTime(reservationTime);
        reservation.setPeopleCount(peopleCount);
        reservation.setStatus(STATUS_PENDING);
        reservation.setRemark(remark);
        reservation.setCreateTime(LocalDateTime.now());
        reservation.setUpdateTime(LocalDateTime.now());

        save(reservation);

        log.info("预约创建成功: reservationId={}", reservation.getId());

        return Result.ok(reservation);
    }

    @Override
    public Result checkAvailable(Long shopId, LocalDate date) {
        log.info("查询可用时段: shopId={}, date={}", shopId, date);

        // 检查商铺是否存在
        Result shopResult = shopService.queryById(shopId);
        if (!shopResult.isSuccess()) {
            return Result.fail("商铺不存在");
        }

        // 查询当天已有的预约
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

        List<Reservation> existingReservations = query()
                .eq("shop_id", shopId)
                .between("reservation_time", startOfDay, endOfDay)
                .ne("status", STATUS_CANCELLED)
                .list();

        // 计算每个时段的剩余容量（假设每个时段最大容量为10人）
        Map<LocalTime, Integer> slotUsage = existingReservations.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getReservationTime().toLocalTime(),
                        Collectors.summingInt(Reservation::getPeopleCount)
                ));

        List<Map<String, Object>> availableSlots = new ArrayList<>();
        for (LocalTime slot : DEFAULT_TIME_SLOTS) {
            int used = slotUsage.getOrDefault(slot, 0);
            int remaining = 10 - used; // 假设每个时段最大10人
            if (remaining > 0) {
                Map<String, Object> slotInfo = new java.util.HashMap<>();
                slotInfo.put("time", slot.format(DateTimeFormatter.ofPattern("HH:mm")));
                slotInfo.put("remainingSeats", remaining);
                availableSlots.add(slotInfo);
            }
        }

        return Result.ok(availableSlots);
    }

    @Override
    @Transactional
    public Result cancelReservation(Long reservationId, Long userId) {
        log.info("取消预约: reservationId={}, userId={}", reservationId, userId);

        Reservation reservation = getById(reservationId);
        if (reservation == null) {
            return Result.fail("预约不存在");
        }

        // 验证预约归属
        if (!reservation.getUserId().equals(userId)) {
            return Result.fail("只能取消自己的预约");
        }

        // 检查状态
        if (reservation.getStatus() == STATUS_CANCELLED) {
            return Result.fail("预约已取消");
        }
        if (reservation.getStatus() == STATUS_COMPLETED) {
            return Result.fail("预约已完成，无法取消");
        }

        // 更新状态
        reservation.setStatus(STATUS_CANCELLED);
        reservation.setUpdateTime(LocalDateTime.now());
        updateById(reservation);

        log.info("预约取消成功: reservationId={}", reservationId);

        return Result.ok("预约已取消");
    }

    @Override
    public Result getUserReservations(Long userId) {
        log.info("获取用户预约列表: userId={}", userId);

        List<Reservation> reservations = query()
                .eq("user_id", userId)
                .orderByDesc("reservation_time")
                .list();

        // 补充商铺信息
        for (Reservation reservation : reservations) {
            Result shopResult = shopService.queryById(reservation.getShopId());
            if (shopResult.isSuccess()) {
                Shop shop = (Shop) shopResult.getData();
                reservation.setShopName(shop.getName());
                reservation.setShopAddress(shop.getAddress());
            }
        }

        return Result.ok(reservations);
    }

    @Override
    public Result getReservationById(Long id) {
        Reservation reservation = getById(id);
        if (reservation == null) {
            return Result.fail("预约不存在");
        }

        // 补充商铺信息
        Result shopResult = shopService.queryById(reservation.getShopId());
        if (shopResult.isSuccess()) {
            Shop shop = (Shop) shopResult.getData();
            reservation.setShopName(shop.getName());
            reservation.setShopAddress(shop.getAddress());
        }

        return Result.ok(reservation);
    }
}