package com.genius.smartlight.service.device;

import com.genius.smartlight.vo.device.DeviceOtaCheckRespVO;
import com.genius.smartlight.vo.device.DeviceOtaStartReqVO;

public interface DeviceOtaService {

    DeviceOtaCheckRespVO checkUpdate(String chipId, String channel);

    DeviceOtaCheckRespVO startUpdate(String chipId, DeviceOtaStartReqVO reqVO);
}
