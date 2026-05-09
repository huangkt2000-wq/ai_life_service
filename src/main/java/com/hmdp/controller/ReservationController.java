package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.Shop;
import com.hmdp.service.IReservationService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 餐厅预约 API Controller
 * 前端直接调用的预约接口
 */
@Slf4j
@RestController
@RequestMapping("/reservation")
public class ReservationController {

    @Resource
    private IReservationService reservationService;

    @Resource
    private IShopService shopService;

    /**
     * 创建预约（前端表单提交）
     * 用户提交后，返回整理好的信息供确认
     */
    @PostMapping("/submit")
    public Result submitReservation(@RequestBody ReservationSubmitRequest request) {
        log.info("提交预约: shopId={}, name={}, phone={}, time={}, people={}",
                request.getShopId(), request.getContactName(), request.getContactPhone(),
                request.getReservationTime(), request.getPeopleCount());

        // 校验登录
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }

        // 校验参数
        String validateMsg = validateRequest(request);
        if (validateMsg != null) {
            return Result.fail(validateMsg);
        }

        // 检查餐厅是否存在
        Result shopResult = shopService.queryById(request.getShopId());
        if (!shopResult.isSuccess()) {
            return Result.fail("餐厅不存在");
        }
        Shop shop = (Shop) shopResult.getData();

        // 解析预约时间
        LocalDateTime reservationTime;
        try {
            reservationTime = LocalDateTime.parse(request.getReservationTime(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return Result.fail("时间格式错误，请使用 yyyy-MM-dd HH:mm 格式");
        }

        // 校验时间不能是过去
        if (reservationTime.isBefore(LocalDateTime.now())) {
            return Result.fail("预约时间不能是过去的时间");
        }

        // 返回整理好的信息供前端展示（不立即创建预约）
        ReservationPreview preview = new ReservationPreview();
        preview.setShopId(request.getShopId());
        preview.setShopName(shop.getName());
        preview.setShopAddress(shop.getAddress());
        preview.setContactName(request.getContactName());
        preview.setContactPhone(request.getContactPhone());
        preview.setReservationTime(request.getReservationTime());
        preview.setPeopleCount(request.getPeopleCount());
        preview.setRemark(request.getRemark());

        return Result.ok(preview);
    }

    /**
     * 确认并创建预约
     */
    @PostMapping("/confirm")
    public Result confirmReservation(@RequestBody ReservationConfirmRequest request) {
        log.info("确认预约: shopId={}, name={}, phone={}, time={}, people={}",
                request.getShopId(), request.getContactName(), request.getContactPhone(),
                request.getReservationTime(), request.getPeopleCount());

        // 校验登录
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }

        // 校验参数
        String validateMsg = validateConfirmRequest(request);
        if (validateMsg != null) {
            return Result.fail(validateMsg);
        }

        // 解析预约时间
        LocalDateTime reservationTime;
        try {
            reservationTime = LocalDateTime.parse(request.getReservationTime(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return Result.fail("时间格式错误");
        }

        // 创建预约
        Result result = reservationService.createReservation(
                userId,
                request.getShopId(),
                request.getContactName(),
                request.getContactPhone(),
                reservationTime,
                request.getPeopleCount(),
                request.getRemark()
        );

        return result;
    }

    /**
     * 获取用户预约列表
     */
    @GetMapping("/list")
    public Result getUserReservations() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }

        return reservationService.getUserReservations(userId);
    }

    /**
     * 取消预约
     */
    @PostMapping("/cancel/{id}")
    public Result cancelReservation(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }

        return reservationService.cancelReservation(id, userId);
    }

    /**
     * 获取当前用户ID
     */
    private Long getCurrentUserId() {
        try {
            return UserHolder.getUser().getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验提交请求
     */
    private String validateRequest(ReservationSubmitRequest request) {
        if (request.getShopId() == null) {
            return "请选择餐厅";
        }
        if (request.getContactName() == null || request.getContactName().trim().isEmpty()) {
            return "请填写预约人姓名";
        }
        if (request.getContactPhone() == null || !request.getContactPhone().matches("^1[3-9]\\d{9}$")) {
            return "请填写正确的手机号码";
        }
        if (request.getReservationTime() == null || request.getReservationTime().isEmpty()) {
            return "请选择预约时间";
        }
        if (request.getPeopleCount() == null || request.getPeopleCount() < 1 || request.getPeopleCount() > 20) {
            return "就餐人数需在1-20人之间";
        }
        return null;
    }

    /**
     * 校验确认请求
     */
    private String validateConfirmRequest(ReservationConfirmRequest request) {
        if (request.getShopId() == null) {
            return "请选择餐厅";
        }
        if (request.getContactName() == null || request.getContactName().trim().isEmpty()) {
            return "请填写预约人姓名";
        }
        if (request.getContactPhone() == null || !request.getContactPhone().matches("^1[3-9]\\d{9}$")) {
            return "请填写正确的手机号码";
        }
        if (request.getReservationTime() == null || request.getReservationTime().isEmpty()) {
            return "请选择预约时间";
        }
        if (request.getPeopleCount() == null || request.getPeopleCount() < 1 || request.getPeopleCount() > 20) {
            return "就餐人数需在1-20人之间";
        }
        return null;
    }

    /**
     * 预约提交请求 DTO
     */
    public static class ReservationSubmitRequest {
        private Long shopId;
        private String contactName;
        private String contactPhone;
        private String reservationTime;
        private Integer peopleCount;
        private String remark;

        public Long getShopId() { return shopId; }
        public void setShopId(Long shopId) { this.shopId = shopId; }
        public String getContactName() { return contactName; }
        public void setContactName(String contactName) { this.contactName = contactName; }
        public String getContactPhone() { return contactPhone; }
        public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
        public String getReservationTime() { return reservationTime; }
        public void setReservationTime(String reservationTime) { this.reservationTime = reservationTime; }
        public Integer getPeopleCount() { return peopleCount; }
        public void setPeopleCount(Integer peopleCount) { this.peopleCount = peopleCount; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    /**
     * 预约确认请求 DTO
     */
    public static class ReservationConfirmRequest {
        private Long shopId;
        private String contactName;
        private String contactPhone;
        private String reservationTime;
        private Integer peopleCount;
        private String remark;

        public Long getShopId() { return shopId; }
        public void setShopId(Long shopId) { this.shopId = shopId; }
        public String getContactName() { return contactName; }
        public void setContactName(String contactName) { this.contactName = contactName; }
        public String getContactPhone() { return contactPhone; }
        public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
        public String getReservationTime() { return reservationTime; }
        public void setReservationTime(String reservationTime) { this.reservationTime = reservationTime; }
        public Integer getPeopleCount() { return peopleCount; }
        public void setPeopleCount(Integer peopleCount) { this.peopleCount = peopleCount; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    /**
     * 预览信息 DTO
     */
    public static class ReservationPreview {
        private Long shopId;
        private String shopName;
        private String shopAddress;
        private String contactName;
        private String contactPhone;
        private String reservationTime;
        private Integer peopleCount;
        private String remark;

        public Long getShopId() { return shopId; }
        public void setShopId(Long shopId) { this.shopId = shopId; }
        public String getShopName() { return shopName; }
        public void setShopName(String shopName) { this.shopName = shopName; }
        public String getShopAddress() { return shopAddress; }
        public void setShopAddress(String shopAddress) { this.shopAddress = shopAddress; }
        public String getContactName() { return contactName; }
        public void setContactName(String contactName) { this.contactName = contactName; }
        public String getContactPhone() { return contactPhone; }
        public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
        public String getReservationTime() { return reservationTime; }
        public void setReservationTime(String reservationTime) { this.reservationTime = reservationTime; }
        public Integer getPeopleCount() { return peopleCount; }
        public void setPeopleCount(Integer peopleCount) { this.peopleCount = peopleCount; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }
}