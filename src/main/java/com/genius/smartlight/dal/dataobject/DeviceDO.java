package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("device")
public class DeviceDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chip_id")
    private String chipId;

    @TableField("device_type")
    private String deviceType;

    @TableField("device_no")
    private String deviceNo;

    @TableField("display_name")
    private String displayName;

    @TableField("ip")
    private String ip;

    @TableField("brightness")
    private Integer brightness;

    @TableField("temp")
    private Integer temp;

    @TableField("auto_mode")
    private Boolean autoMode;

    @TableField("recommended_brightness")
    private Integer recommendedBrightness;

    @TableField("recommended_temp")
    private Integer recommendedTemp;

    @TableField("fabric")
    private String fabric;

    @TableField("main_color_rgb")
    private String mainColorRgb;

    @TableField("firmware_version")
    private String firmwareVersion;

    @TableField("firmware_version_code")
    private Integer firmwareVersionCode;

    @TableField("firmware_channel")
    private String firmwareChannel;

    @TableField("ota_status")
    private String otaStatus;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("store_id")
    private Long storeId;

}
