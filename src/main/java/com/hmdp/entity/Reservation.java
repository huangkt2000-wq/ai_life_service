package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 餐厅预约实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reservation")
public class Reservation implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 预约用户ID
     */
    private Long userId;

    /**
     * 商铺ID
     */
    private Long shopId;

    /**
     * 预约人姓名
     */
    private String contactName;

    /**
     * 联系电话
     */
    private String contactPhone;

    /**
     * 预约时间
     */
    private LocalDateTime reservationTime;

    /**
     * 就餐人数
     */
    private Integer peopleCount;

    /**
     * 状态：1=待确认 2=已确认 3=已取消 4=已完成
     */
    private Integer status;


    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 商铺名称（非数据库字段）
     */
    @TableField(exist = false)
    private String shopName;

    /**
     * 商铺地址（非数据库字段）
     */
    @TableField(exist = false)
    private String shopAddress;
}
