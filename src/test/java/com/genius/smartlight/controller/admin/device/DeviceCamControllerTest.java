package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.service.device.DeviceCamCaptureConfigService;
import com.genius.smartlight.service.device.DeviceCamService;
import com.genius.smartlight.service.device.FixedPersonTrackingService;
import com.genius.smartlight.vo.device.DeviceTrackingStatusRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeviceCamControllerTest {

    private DeviceCamService deviceCamService;
    private DeviceCamCaptureConfigService captureConfigService;
    private FixedPersonTrackingService fixedPersonTrackingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deviceCamService = mock(DeviceCamService.class);
        captureConfigService = mock(DeviceCamCaptureConfigService.class);
        fixedPersonTrackingService = mock(FixedPersonTrackingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceCamController(
                deviceCamService,
                captureConfigService,
                fixedPersonTrackingService)).build();
    }

    @Test
    void manualTrackingStart_delegatesToFixedTrackingService() throws Exception {
        DeviceTrackingStatusRespVO response = new DeviceTrackingStatusRespVO();
        response.setTrackingStatus("tracking");
        when(fixedPersonTrackingService.startManually(any())).thenReturn(response);

        mockMvc.perform(post("/admin/device/cam/tracking/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"camChipId":"CAM-001","targetChipId":"LAMP-001","targetIndex":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trackingStatus").value("tracking"));
    }

    @Test
    void manualTrackingStop_delegatesToFixedTrackingService() throws Exception {
        DeviceTrackingStatusRespVO response = new DeviceTrackingStatusRespVO();
        response.setTrackingStatus("stopped");
        when(fixedPersonTrackingService.stopManually(any())).thenReturn(response);

        mockMvc.perform(post("/admin/device/cam/tracking/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"camChipId":"CAM-001","targetChipId":"LAMP-001","targetIndex":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trackingStatus").value("stopped"));
    }
}
