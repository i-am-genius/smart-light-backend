package com.genius.smartlight.convert.device;

import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.service.ai.GarmentResultCodec;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceSaveReqVO;

public class DeviceConvert {

    public static DeviceDO convert(DeviceSaveReqVO reqVO) {
        DeviceDO device = new DeviceDO();
        device.setChipId(reqVO.getChipId());
        device.setDeviceType(reqVO.getDeviceType());
        device.setDeviceNo(reqVO.getDeviceNo());
        device.setDisplayName(reqVO.getDisplayName());
        device.setIp(reqVO.getIp());
        device.setBrightness(reqVO.getBrightness());
        device.setTemp(reqVO.getTemp());
        device.setAutoMode(reqVO.getAutoMode());
        device.setGarmentAimEnabled(reqVO.getGarmentAimEnabled());
        device.setRecommendedBrightness(reqVO.getRecommendedBrightness());
        device.setRecommendedTemp(reqVO.getRecommendedTemp());
        device.setFabric(reqVO.getFabric());
        device.setMainColorRgb(reqVO.getMainColorRgb());
        return device;
    }

    public static DeviceRespVO convert(DeviceDO device) {
        DeviceRespVO respVO = new DeviceRespVO();
        respVO.setId(device.getId());
        respVO.setChipId(device.getChipId());
        respVO.setDeviceType(device.getDeviceType());
        respVO.setDeviceNo(device.getDeviceNo());
        respVO.setDisplayName(device.getDisplayName());
        respVO.setIp(device.getIp());
        respVO.setLastSeenAt(device.getLastSeenAt());
        respVO.setBrightness(device.getBrightness());
        respVO.setTemp(device.getTemp());
        respVO.setAutoMode(device.getAutoMode());
        respVO.setGarmentAimEnabled(device.getGarmentAimEnabled());
        respVO.setRecommendedBrightness(device.getRecommendedBrightness());
        respVO.setRecommendedTemp(device.getRecommendedTemp());
        respVO.setFabric(device.getFabric());
        respVO.setMainColorRgb(device.getMainColorRgb());
        respVO.setFirmwareVersion(device.getFirmwareVersion());
        respVO.setFirmwareVersionCode(device.getFirmwareVersionCode());
        respVO.setFirmwareChannel(device.getFirmwareChannel());
        respVO.setOtaStatus(device.getOtaStatus());
        respVO.setSelfTestJson(device.getSelfTestJson());
        respVO.setSelfTestTime(device.getSelfTestTime());
        respVO.setCreateTime(device.getCreateTime());
        respVO.setUpdateTime(device.getUpdateTime());
        respVO.setStoreId(device.getStoreId());
        GarmentResultCodec.applyToResponse(device, respVO);
        return respVO;
    }
}
