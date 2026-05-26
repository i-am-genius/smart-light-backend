package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.OtaFirmwareDO;
import com.genius.smartlight.dal.mysql.OtaFirmwareMapper;
import com.genius.smartlight.service.device.DeviceOtaFirmwareService;
import com.genius.smartlight.vo.device.DeviceOtaFirmwareRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DeviceOtaFirmwareServiceImpl implements DeviceOtaFirmwareService {

    private static final String LOCALHOST_ERROR = "请使用电脑局域网IP访问后端后再上传固件，否则ESP8266无法下载固件";

    @Value("${ota.firmware.max-size-mb:4}")
    private int maxSizeMb;

    private final OtaFirmwareMapper otaFirmwareMapper;

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
        String normalizedHost = validateHost(host);

        Path targetPath = Path.of(
                "data",
                "ota",
                normalizedDeviceType,
                normalizedChannel,
                String.valueOf(normalizedVersionCode),
                "firmware.bin"
        ).toAbsolutePath().normalize();

        try {
            Files.createDirectories(targetPath.getParent());
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ServiceException("固件文件保存失败：" + e.getMessage());
        }

        String fileUrl = "http://" + normalizedHost
                + "/ota/"
                + normalizedDeviceType
                + "/"
                + normalizedChannel
                + "/"
                + normalizedVersionCode
                + "/firmware.bin";

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
        firmware.setMd5(blankToNull(md5));
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

    private String normalizeDeviceType(String deviceType) {
        String value = deviceType == null ? "" : deviceType.trim().toLowerCase(Locale.ROOT);
        if ("lamp".equals(value) || "camlamp".equals(value)) {
            return value;
        }
        throw new ServiceException("deviceType 只能是 lamp 或 camlamp");
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
        if (fileUrl != null && !fileUrl.isBlank()) fw.setFileUrl(fileUrl.trim());
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
        respVO.setFileUrl(firmware.getFileUrl());
        respVO.setMd5(firmware.getMd5());
        respVO.setChangelog(firmware.getChangelog());
        respVO.setEnabled(firmware.getEnabled());
        respVO.setCreateTime(firmware.getCreateTime());
        respVO.setUpdateTime(firmware.getUpdateTime());
        return respVO;
    }
}
