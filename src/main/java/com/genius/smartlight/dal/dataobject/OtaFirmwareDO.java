package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ota_firmware")
public class OtaFirmwareDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("device_type")
    private String deviceType;

    @TableField("channel")
    private String channel;

    @TableField("version")
    private String version;

    @TableField("version_code")
    private Integer versionCode;

    @TableField("file_url")
    private String fileUrl;

    @TableField("md5")
    private String md5;

    @TableField("changelog")
    private String changelog;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
