package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.OtaFirmwareDO;
import com.genius.smartlight.dal.mysql.OtaFirmwareMapper;
import com.genius.smartlight.service.device.DeviceOtaFirmwareService;
import com.genius.smartlight.service.device.OtaDownloadSecurityService;
import com.genius.smartlight.vo.device.DeviceOtaFirmwareRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceOtaFirmwareServiceImpl implements DeviceOtaFirmwareService {

    private static final String LOCALHOST_ERROR = "请使用电脑局域网IP访问后端后再上传固件，否则ESP8266无法下载固件";

    @Value("${ota.firmware.max-size-mb:4}")
    private int maxSizeMb;

    private final OtaFirmwareMapper otaFirmwareMapper;
    private final OtaDownloadSecurityService otaDownloadSecurityService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceOtaFirmwareRespVO uploadFirmware(
            MultipartFile file,
            String deviceType,
            String channel,
            String version,
            Integer versionCode,
            String changelog,
            String md5,
            String host
    ) {
        String normalizedDeviceType = normalizeDeviceType(deviceType);
        String normalizedChannel = normalizeChannel(channel);
        String normalizedVersion = validateVersion(version);
        int normalizedVersionCode = validateVersionCode(versionCode);
        validateFile(file);

        String relativePath = otaDownloadSecurityService.buildRelativePath(
                normalizedDeviceType,
                normalizedChannel,
                normalizedVersionCode
        );
        Path targetPath = otaDownloadSecurityService.resolveDownloadPath(relativePath);

        String computedMd5;
        Path tempPath = null;
        try {
            Files.createDirectories(targetPath.getParent());
            tempPath = createUploadTempPath(targetPath);
            computedMd5 = streamFirmwareToDisk(file, tempPath);
            validateProvidedMd5(md5, computedMd5);
            moveFirmwareIntoPlace(tempPath, targetPath);
            tempPath = null;
        } catch (IOException e) {
            throw new ServiceException("固件文件保存失败：" + e.getMessage());
        } finally {
            cleanupTempFile(tempPath);
        }
        String fileUrl = otaDownloadSecurityService.buildStoredFileUrl(
                host,
                normalizedDeviceType,
                normalizedChannel,
                normalizedVersionCode
        );

        LocalDateTime now = LocalDateTime.now();
        OtaFirmwareDO firmware = otaFirmwareMapper.selectOne(
                new LambdaQueryWrapper<OtaFirmwareDO>()
                        .eq(OtaFirmwareDO::getDeviceType, normalizedDeviceType)
                        .eq(OtaFirmwareDO::getChannel, normalizedChannel)
                        .eq(OtaFirmwareDO::getVersion, normalizedVersion)
        );

        if (firmware == null) {
            firmware = new OtaFirmwareDO();
            firmware.setDeviceType(normalizedDeviceType);
            firmware.setChannel(normalizedChannel);
            firmware.setVersion(normalizedVersion);
            firmware.setCreateTime(now);
        }

        firmware.setVersionCode(normalizedVersionCode);
        firmware.setFileUrl(fileUrl);
        firmware.setMd5(computedMd5);
        firmware.setChangelog(blankToNull(changelog));
        firmware.setEnabled(true);
        firmware.setUpdateTime(now);

        if (firmware.getId() == null) {
            otaFirmwareMapper.insert(firmware);
        } else {
            otaFirmwareMapper.updateById(firmware);
        }
        disableOtherFirmware(normalizedDeviceType, normalizedChannel, firmware.getId(), now);

        return toRespVO(firmware);
    }

    @Override
    public List<DeviceOtaFirmwareRespVO> listFirmware(String deviceType, String channel) {
        LambdaQueryWrapper<OtaFirmwareDO> query = new LambdaQueryWrapper<>();

        String normalizedDeviceType = blankToNull(deviceType);
        if (normalizedDeviceType != null) {
            query.eq(OtaFirmwareDO::getDeviceType, normalizeDeviceType(normalizedDeviceType));
        }

        String normalizedChannel = blankToNull(channel);
        if (normalizedChannel != null) {
            query.eq(OtaFirmwareDO::getChannel, normalizeChannel(normalizedChannel));
        }

        query.orderByDesc(OtaFirmwareDO::getVersionCode)
                .orderByDesc(OtaFirmwareDO::getUpdateTime)
                .orderByDesc(OtaFirmwareDO::getId);

        return otaFirmwareMapper.selectList(query)
                .stream()
                .map(this::toRespVO)
                .toList();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("固件文件不能为空");
        }

        long maxSizeBytes = (long) maxSizeMb * 1024L * 1024L;
        if (file.getSize() > maxSizeBytes) {
            throw new ServiceException("固件文件大小不能超过 " + maxSizeMb + "MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            throw new ServiceException("固件文件必须是 .bin 文件");
        }
    }

    /**
     * 流式写入固件文件并同时计算 MD5，避免将整个固件（最大 32MB）读入内存。
     * 读取前 4 字节校验固件魔数，然后将完整文件流经 DigestInputStream 写入磁盘，
     * 写入完成后返回 MD5 十六进制字符串。
     */
    private Path createUploadTempPath(Path targetPath) {
        return targetPath.getParent()
                .resolve(".uploading-" + UUID.randomUUID().toString().replace("-", "") + ".tmp")
                .normalize();
    }

    private void moveFirmwareIntoPlace(Path tempPath, Path targetPath) throws IOException {
        try {
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some mounted filesystems do not support atomic rename. The temp file is in the
            // same directory and has already passed validation, so this fallback only affects
            // the final replacement step.
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupTempFile(Path tempPath) {
        if (tempPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempPath);
        } catch (IOException ignored) {
        }
    }

    /**
     * Streams the upload to disk while calculating MD5 and validating the firmware magic byte.
     */
    private String streamFirmwareToDisk(MultipartFile file, Path targetPath) throws IOException {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("MD5 is unavailable");
        }

        try (InputStream is = file.getInputStream();
             DigestInputStream dis = new DigestInputStream(is, md5);
             OutputStream os = Files.newOutputStream(targetPath)) {

            // 读取前 4 字节进行魔数校验（同时计入 MD5 摘要）
            byte[] header = new byte[4];
            int read = dis.readNBytes(header, 0, 4);
            if (read < 4) {
                throw new ServiceException("Firmware file is incomplete");
            }
            if ((header[0] & 0xFF) != 0xE9) {
                throw new ServiceException("Firmware file format is invalid");
            }

            // 剩余内容流式写入磁盘，边写边更新 MD5
            os.write(header, 0, read);
            dis.transferTo(os);
        }

        return HexFormat.of().formatHex(md5.digest());
    }

    private void validateProvidedMd5(String providedMd5, String computedMd5) {
        String value = blankToNull(providedMd5);
        if (value == null) {
            return;
        }
        if (!value.matches("(?i)^[0-9a-f]{32}$") || !computedMd5.equalsIgnoreCase(value)) {
            throw new ServiceException("Firmware MD5 mismatch");
        }
    }

    private String normalizeDeviceType(String deviceType) {
        return DeviceTypeUtil.normalizeAndValidate(deviceType);
    }

    private String normalizeChannel(String channel) {
        String value = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if ("stable".equals(value) || "test".equals(value)) {
            return value;
        }
        throw new ServiceException("channel 只能是 stable 或 test");
    }

    private String validateVersion(String version) {
        String value = version == null ? "" : version.trim();
        if (value.isEmpty()) {
            throw new ServiceException("version 不能为空");
        }
        return value;
    }

    private int validateVersionCode(Integer versionCode) {
        if (versionCode == null || versionCode <= 0) {
            throw new ServiceException("versionCode 必须大于 0");
        }
        return versionCode;
    }

    private String validateHost(String host) {
        String value = host == null ? "" : host.trim();
        if (value.isEmpty()) {
            throw new ServiceException("请求 Host 不能为空");
        }

        String hostOnly = value;
        if (hostOnly.startsWith("[")) {
            int endBracket = hostOnly.indexOf(']');
            if (endBracket > 0) {
                hostOnly = hostOnly.substring(1, endBracket);
            }
        } else {
            int colonIndex = hostOnly.indexOf(':');
            if (colonIndex > 0) {
                hostOnly = hostOnly.substring(0, colonIndex);
            }
        }

        String lowerHost = hostOnly.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lowerHost)
                || "127.0.0.1".equals(lowerHost)
                || "::1".equals(lowerHost)
                || "0:0:0:0:0:0:0:1".equals(lowerHost)) {
            throw new ServiceException(LOCALHOST_ERROR);
        }

        return value;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void disableOtherFirmware(String deviceType, String channel, Long enabledFirmwareId, LocalDateTime now) {
        if (enabledFirmwareId == null) {
            return;
        }
        OtaFirmwareDO update = new OtaFirmwareDO();
        update.setEnabled(false);
        update.setUpdateTime(now);

        otaFirmwareMapper.update(
                update,
                new LambdaUpdateWrapper<OtaFirmwareDO>()
                        .eq(OtaFirmwareDO::getDeviceType, deviceType)
                        .eq(OtaFirmwareDO::getChannel, channel)
                        .ne(OtaFirmwareDO::getId, enabledFirmwareId)
                        .eq(OtaFirmwareDO::getEnabled, true)
        );
    }

    @Override
    @Transactional
    public DeviceOtaFirmwareRespVO updateFirmware(Long id, String version, Integer versionCode,
                                                   String changelog, String md5, String fileUrl) {
        OtaFirmwareDO fw = otaFirmwareMapper.selectById(id);
        if (fw == null) throw new ServiceException("固件不存在");
        if (version != null && !version.isBlank()) fw.setVersion(version.trim());
        if (versionCode != null && versionCode > 0) fw.setVersionCode(versionCode);
        if (changelog != null) fw.setChangelog(blankToNull(changelog));
        if (md5 != null) fw.setMd5(blankToNull(md5));
        if (fileUrl != null && !fileUrl.isBlank()) {
            otaDownloadSecurityService.validateStoredFileUrl(fileUrl);
            fw.setFileUrl(fileUrl.trim());
        }
        fw.setUpdateTime(LocalDateTime.now());
        otaFirmwareMapper.updateById(fw);
        return toRespVO(fw);
    }

    @Override
    @Transactional
    public void deleteFirmware(Long id) {
        OtaFirmwareDO fw = otaFirmwareMapper.selectById(id);
        if (fw == null) throw new ServiceException("固件不存在");
        otaFirmwareMapper.deleteById(id);
    }

    @Override
    @Transactional
    public DeviceOtaFirmwareRespVO enableFirmware(Long id) {
        OtaFirmwareDO fw = otaFirmwareMapper.selectById(id);
        if (fw == null) throw new ServiceException("固件不存在");
        fw.setEnabled(true);
        fw.setUpdateTime(LocalDateTime.now());
        otaFirmwareMapper.updateById(fw);
        disableOtherFirmware(fw.getDeviceType(), fw.getChannel(), fw.getId(), fw.getUpdateTime());
        return toRespVO(fw);
    }

    @Override
    @Transactional
    public DeviceOtaFirmwareRespVO disableFirmware(Long id) {
        OtaFirmwareDO fw = otaFirmwareMapper.selectById(id);
        if (fw == null) throw new ServiceException("固件不存在");
        fw.setEnabled(false);
        fw.setUpdateTime(LocalDateTime.now());
        otaFirmwareMapper.updateById(fw);
        return toRespVO(fw);
    }

    private DeviceOtaFirmwareRespVO toRespVO(OtaFirmwareDO firmware) {
        DeviceOtaFirmwareRespVO respVO = new DeviceOtaFirmwareRespVO();
        respVO.setId(firmware.getId());
        respVO.setDeviceType(firmware.getDeviceType());
        respVO.setChannel(firmware.getChannel());
        respVO.setVersion(firmware.getVersion());
        respVO.setVersionCode(firmware.getVersionCode());
        respVO.setFileUrl(otaDownloadSecurityService.signDownloadUrl(firmware.getFileUrl()));
        respVO.setMd5(firmware.getMd5());
        respVO.setChangelog(firmware.getChangelog());
        respVO.setEnabled(firmware.getEnabled());
        respVO.setCreateTime(firmware.getCreateTime());
        respVO.setUpdateTime(firmware.getUpdateTime());
        return respVO;
    }
}
