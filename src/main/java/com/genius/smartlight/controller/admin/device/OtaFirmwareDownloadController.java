package com.genius.smartlight.controller.admin.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.FileDownloadUtil;
import com.genius.smartlight.dal.dataobject.OtaFirmwareDO;
import com.genius.smartlight.dal.mysql.OtaFirmwareMapper;
import com.genius.smartlight.service.device.OtaDownloadSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequiredArgsConstructor
public class OtaFirmwareDownloadController {

    private final OtaDownloadSecurityService otaDownloadSecurityService;
    private final OtaFirmwareMapper otaFirmwareMapper;

    @GetMapping("/ota/**")
    public ResponseEntity<Resource> download(
            HttpServletRequest request,
            @RequestParam(required = false) String token) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }

        if (!requestPath.startsWith("/ota/")) {
            return ResponseEntity.notFound().build();
        }

        String relativePath = requestPath.substring("/ota/".length());
        relativePath = UriUtils.decode(relativePath, StandardCharsets.UTF_8);

        // 一次性规范化，避免重复计算
        String normalizedRelativePath;
        try {
            normalizedRelativePath = otaDownloadSecurityService.normalizeRelativePath(relativePath);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }

        // 验证 token：如果提供了 token 则必须有效，未提供 token 则允许旧设备下载
        boolean hasToken = token != null && !token.isBlank();
        if (hasToken) {
            if (!otaDownloadSecurityService.isTokenValidForNormalized(normalizedRelativePath, token)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        // 校验固件路径是否对应已启用的固件（使用索引查询代替全表扫描）
        if (!isEnabledFirmwarePathByParts(normalizedRelativePath)) {
            return ResponseEntity.notFound().build();
        }

        Path target = otaDownloadSecurityService.resolveDownloadPath(normalizedRelativePath);
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }

        return FileDownloadUtil.attachmentFile(target, MediaType.APPLICATION_OCTET_STREAM);
    }

    /**
     * 解析路径为 {deviceType}/{channel}/{versionCode}/firmware.bin 并用索引字段直接查询，
     * 代替原来的全表 selectList + Java 内存匹配。
     */
    private boolean isEnabledFirmwarePathByParts(String relativePath) {
        String[] parts = relativePath.split("/");
        if (parts.length != 4) {
            return false;
        }
        int versionCode;
        try {
            versionCode = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }
        Long count = otaFirmwareMapper.selectCount(
                new LambdaQueryWrapper<OtaFirmwareDO>()
                        .eq(OtaFirmwareDO::getDeviceType, parts[0])
                        .eq(OtaFirmwareDO::getChannel, parts[1])
                        .eq(OtaFirmwareDO::getVersionCode, versionCode)
                        .eq(OtaFirmwareDO::getEnabled, true)
                        .last("limit 1")
        );
        return count != null && count > 0;
    }
}
