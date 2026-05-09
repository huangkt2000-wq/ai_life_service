package com.hmdp.ai.tool;

import com.hmdp.dto.Result;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.Shop;
import com.hmdp.service.IReservationService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 餐厅预约工具集
 * 实现餐厅预约相关功能
 */
@Slf4j
@Component
public class ReservationTools {

    @Resource
    private IReservationService reservationService;

    @Resource
    private IShopService shopService;

    /**
     * 创建餐厅预约
     */
    @Tool(
            name = "reservation/create",
            description = "创建餐厅预约，需要商铺ID、预约人姓名、联系电话、预约时间、就餐人数"
    )
    public String create(Long shopId, String contactName, String contactPhone,
                         String reservationTime, Integer peopleCount, String remark) {
        log.info("Tool called: reservation/create, shopId={}, name={}, phone={}, time={}, peopleCount={}",
                shopId, contactName, contactPhone, reservationTime, peopleCount);

        // 获取当前用户ID
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "请先登录后再预约";
        }

        // 校验姓名
        if (contactName == null || contactName.trim().isEmpty()) {
            return "请提供预约人姓名";
        }

        // 校验电话
        if (contactPhone == null || !contactPhone.matches("^1[3-9]\\d{9}$")) {
            return "请提供正确的手机号码（11位数字）";
        }

        // 解析预约时间
        LocalDateTime time = parseReservationTime(reservationTime);
        if (time == null) {
            return "时间格式错误，请使用格式：yyyy-MM-dd HH:mm，例如：2024-01-15 18:00";
        }

        // 校验就餐人数
        if (peopleCount == null || peopleCount < 1 || peopleCount > 20) {
            return "就餐人数需在1-20人之间";
        }

        // 获取商铺信息
        Result shopResult = shopService.queryById(shopId);
        if (!shopResult.isSuccess()) {
            return "商铺不存在，请确认商铺ID";
        }
        Shop shop = (Shop) shopResult.getData();

        // 创建预约
        Result result = reservationService.createReservation(userId, shopId, contactName, contactPhone,
                time, peopleCount, remark);

        if (result.isSuccess()) {
            Reservation reservation = (Reservation) result.getData();
            return formatReservationSuccess(reservation, shop);
        } else {
            return "预约失败：" + result.getErrorMsg();
        }
    }

    /**
     * 查询餐厅可用时段
     */
    @Tool(
            name = "reservation/checkAvailable",
            description = "查询餐厅某天的可用预约时段，需要商铺ID和日期"
    )
    public String checkAvailable(Long shopId, String date) {
        log.info("Tool called: reservation/checkAvailable, shopId={}, date={}", shopId, date);

        // 解析日期
        LocalDate queryDate = parseDate(date);
        if (queryDate == null) {
            return "日期格式错误，请使用格式：yyyy-MM-dd，例如：2024-01-15";
        }

        Result result = reservationService.checkAvailable(shopId, queryDate);

        if (result.isSuccess()) {
            List<Map<String, Object>> slots = (List<Map<String, Object>>) result.getData();
            return formatAvailableSlots(slots, queryDate);
        } else {
            return "查询失败：" + result.getErrorMsg();
        }
    }

    /**
     * 取消预约
     */
    @Tool(
            name = "reservation/cancel",
            description = "取消餐厅预约，需要预约ID"
    )
    public String cancel(Long reservationId) {
        log.info("Tool called: reservation/cancel, reservationId={}", reservationId);

        // 获取当前用户ID
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "请先登录后再操作";
        }

        Result result = reservationService.cancelReservation(reservationId, userId);

        if (result.isSuccess()) {
            return "预约已成功取消";
        } else {
            return "取消失败：" + result.getErrorMsg();
        }
    }

    /**
     * 获取用户预约列表
     */
    @Tool(
            name = "reservation/getMine",
            description = "获取当前用户的预约列表"
    )
    public String getMine() {
        log.info("Tool called: reservation/getMine");

        // 获取当前用户ID
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "请先登录后再查看预约";
        }

        Result result = reservationService.getUserReservations(userId);

        if (result.isSuccess()) {
            List<Reservation> reservations = (List<Reservation>) result.getData();
            return formatReservationList(reservations);
        } else {
            return "查询失败：" + result.getErrorMsg();
        }
    }

    /**
     * 获取当前用户ID
     */
    private Long getCurrentUserId() {
        try {
            return UserHolder.getUser().getId();
        } catch (Exception e) {
            log.warn("获取用户ID失败", e);
            return null;
        }
    }

    /**
     * 解析预约时间
     */
    private LocalDateTime parseReservationTime(String timeStr) {
        try {
            return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            log.warn("解析时间失败: {}", timeStr, e);
            return null;
        }
    }

    /**
     * 解析日期
     */
    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (Exception e) {
            log.warn("解析日期失败: {}", dateStr, e);
            return null;
        }
    }

    /**
     * 格式化预约成功信息
     */
    private String formatReservationSuccess(Reservation reservation, Shop shop) {
        StringBuilder sb = new StringBuilder();
        sb.append("预约成功！\n\n");
        sb.append("| 信息 | 内容 |\n");
        sb.append("|------|------|\n");
        sb.append("| 预约ID | ").append(reservation.getId()).append(" |\n");
        sb.append("| 餐厅 | ").append(shop.getName()).append(" |\n");
        sb.append("| 预约人 | ").append(reservation.getContactName()).append(" |\n");
        sb.append("| 电话 | ").append(reservation.getContactPhone()).append(" |\n");
        sb.append("| 时间 | ").append(reservation.getReservationTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(" |\n");
        sb.append("| 人数 | ").append(reservation.getPeopleCount()).append("人 |\n");
        if (reservation.getRemark() != null && !reservation.getRemark().trim().isEmpty()) {
            sb.append("| 备注 | ").append(reservation.getRemark()).append(" |\n");
        }
        sb.append("| 状态 | 待确认 |\n\n");
        sb.append("餐厅将在2小时内确认您的预约，届时会通过短信通知您。");
        return sb.toString();
    }

    /**
     * 格式化可用时段列表
     */
    private String formatAvailableSlots(List<Map<String, Object>> slots, LocalDate date) {
        if (slots.isEmpty()) {
            return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 当天暂无可用时段";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).append(" 可用时段：\n\n");
        for (Map<String, Object> slot : slots) {
            sb.append("- ").append(slot.get("time")).append(" 剩余座位：").append(slot.get("remainingSeats")).append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化预约列表（展示给用户，同时包含隐藏的预约ID供取消使用）
     */
    private String formatReservationList(List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return "暂无预约记录。如需预约餐厅，可以先让我推荐合适的餐厅。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📋 **您的预约记录**\n\n");
        sb.append("| 餐厅 | 时间 | 人数 | 状态 |\n");
        sb.append("|------|------|------|------|\n");

        // 附加隐藏的预约数据供取消时使用
        StringBuilder hiddenData = new StringBuilder("\n<!-- 预约数据供取消使用：\n");

        for (Reservation reservation : reservations) {
            sb.append("| ").append(reservation.getShopName() != null ? reservation.getShopName() : "未知").append(" ");
            sb.append("| ").append(reservation.getReservationTime()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(" ");
            sb.append("| ").append(reservation.getPeopleCount()).append("人 ");
            sb.append("| ").append(getStatusText(reservation.getStatus())).append(" |\n");

            // 隐藏数据：预约ID、餐厅名、时间
            hiddenData.append("ID=").append(reservation.getId())
                    .append(",餐厅=").append(reservation.getShopName())
                    .append(",时间=").append(reservation.getReservationTime()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    .append("\n");
        }
        hiddenData.append("-->");

        sb.append("\n共 ").append(reservations.size()).append(" 条预约记录。");
        sb.append("\n\n如需取消预约，请告诉我要取消哪个餐厅的预约。");
        sb.append(hiddenData);
        return sb.toString();
    }

    /**
     * 获取状态文本
     */
    private String getStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 1: return "待确认";
            case 2: return "已确认";
            case 3: return "已取消";
            case 4: return "已完成";
            default: return "未知";
        }
    }
}