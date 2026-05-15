package com.genius.smartlight.service.device;

import com.genius.smartlight.vo.device.DeviceOnlineStatusRespVO;

import java.util.List;

public interface DeviceOnlineService {

    DeviceOnlineStatusRespVO getOnlineStatus(String chipId);

    List<DeviceOnlineStatusRespVO> getOnlineStatusList();
}