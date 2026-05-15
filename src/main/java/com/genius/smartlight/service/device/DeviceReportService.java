package com.genius.smartlight.service.device;

import com.genius.smartlight.vo.device.DeviceStateReportReqVO;

public interface DeviceReportService {

    void reportState(DeviceStateReportReqVO reqVO);
}
