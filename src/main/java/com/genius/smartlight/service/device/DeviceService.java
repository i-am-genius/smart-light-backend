package com.genius.smartlight.service.device;

import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceSaveReqVO;
import com.genius.smartlight.vo.device.LightEffectReqVO;

import java.util.List;

public interface DeviceService {

    Long createDevice(DeviceSaveReqVO reqVO);

    void updateDevice(Long id, DeviceSaveReqVO reqVO, boolean lightControl);

    void deleteDevice(Long id);

    DeviceRespVO getDevice(Long id);

    List<DeviceRespVO> getDeviceList(); // 管理员看全量

    DeviceRespVO getDeviceByChipId(String chipId);

    List<DeviceRespVO> getCurrentUserDeviceList();

    void bindDeviceToCurrentStore(String chipId, String displayName);

    boolean locateDevice(String chipId);

    void sendLightEffect(String chipId, LightEffectReqVO reqVO);

    void updateFirmwareChannel(String chipId, String channel);

}
