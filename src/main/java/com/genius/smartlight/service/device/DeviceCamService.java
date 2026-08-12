package com.genius.smartlight.service.device;

import com.genius.smartlight.vo.device.DeviceCamCaptureTaskReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import com.genius.smartlight.vo.device.DeviceCamPresenceReqVO;
import com.genius.smartlight.vo.device.DeviceCamPresenceRespVO;
import com.genius.smartlight.vo.device.DeviceCamRoiConfigVO;
import com.genius.smartlight.vo.device.DeviceCamStatusReqVO;
import com.genius.smartlight.vo.device.DeviceCamStatusRespVO;
import com.genius.smartlight.vo.device.DeviceCamTrackingControlReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateRespVO;
import com.genius.smartlight.vo.device.DeviceSliderStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusRespVO;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface DeviceCamService {
    DeviceCamRoiConfigVO getRoiConfig(String camChipId);

    DeviceCamRoiConfigVO getRoiConfigForDevice(String camChipId);

    DeviceCamRoiConfigVO saveRoiConfig(String camChipId, DeviceCamRoiConfigVO config);

    DeviceCamPresenceRespVO reportPresence(DeviceCamPresenceReqVO reqVO);

    DeviceCamPresenceRespVO getPresence(String camChipId);

    DeviceCamStatusRespVO reportStatus(DeviceCamStatusReqVO reqVO);

    DeviceCamStatusRespVO getStatus(String camChipId);

    DeviceTrackingStatusRespVO startTrackingManually(DeviceCamTrackingControlReqVO reqVO);

    DeviceTrackingStatusRespVO stopTrackingManually(DeviceCamTrackingControlReqVO reqVO);

    DeviceCamCaptureTaskRespVO createCaptureTask(DeviceCamCaptureTaskReqVO reqVO);

    void reportSliderStatus(DeviceSliderStatusReqVO reqVO);

    DeviceCamCaptureTaskRespVO uploadCapturePhoto(String taskId, MultipartFile file);

    DeviceCamCaptureTaskRespVO uploadCapturePhotoByDevice(String taskId, String token, MultipartFile file);

    void uploadFlowPhoto(String camChipId, Integer personCount, Double confidence, String detectTime, MultipartFile file);

    void uploadFlowPhotoByDevice(String camChipId, String token, Integer personCount, Double confidence, String detectTime, MultipartFile file);

    Resource loadUploadImage(String imageName);

    DeviceLampClothStateRespVO reportLampClothState(DeviceLampClothStateReqVO reqVO);

    DeviceTrackingStatusRespVO reportTrackingStatus(DeviceTrackingStatusReqVO reqVO);
}
