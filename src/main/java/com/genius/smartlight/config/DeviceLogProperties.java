package com.genius.smartlight.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "device.log")
public class DeviceLogProperties {

    /** 日志存储根目录 */
    private String basePath = "/opt/smartlight/device-logs";

    /** 单次批上传最大行数 */
    private int maxBatchLines = 500;

    /** 单行最大字符数 */
    private int maxLineLength = 4096;

    /** 日志保留天数 */
    private int retentionDays = 7;

    /** 设备上传日志时需要携带的密钥 */
    private String uploadSecret = "change-me";

    @PostConstruct
    public void checkConfig() {
        if ("change-me".equals(uploadSecret)) {
            log.warn("⚠️ 设备日志上传密钥使用默认值 'change-me'，请在生产环境通过 DEVICE_LOG_UPLOAD_SECRET 环境变量配置强密钥！");
        }
    }
}
