package com.genius.smartlight.service.device;

import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceStateSyncReqVO;

public interface DeviceControlService {

    DeviceRespVO syncStateToDevice(String chipId, DeviceStateSyncReqVO reqVO);
}
