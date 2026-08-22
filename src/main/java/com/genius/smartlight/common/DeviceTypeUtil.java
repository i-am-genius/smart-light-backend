package com.genius.smartlight.common;

import java.util.Locale;

/**
 * 设备类型工具类，统一设备类型字符串的规范化和类型判断。
 */
public final class DeviceTypeUtil {

    public static final String LAMP = "lamp";
    public static final String CAM = "cam";
    public static final String CAM_LAMP = "camlamp";
    public static final String CAM_CAPTURE = "cam_capture";

    private DeviceTypeUtil() {
    }

    /**
     * 规范化设备类型字符串（trim + 小写），null 或空白返回空字符串。
     */
    public static String normalize(String deviceType) {
        if (deviceType == null) {
            return "";
        }
        return deviceType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化并校验，不合法类型抛 ServiceException。
     */
    public static String normalizeAndValidate(String deviceType) {
        String value = normalize(deviceType);
        if (LAMP.equals(value) || CAM.equals(value) || CAM_LAMP.equals(value) || CAM_CAPTURE.equals(value)) {
            return value;
        }
        throw new ServiceException("deviceType 只能是 lamp、cam、camlamp 或 cam_capture");
    }

    /**
     * 是否为灯控设备（lamp 或 camlamp）。
     */
    public static boolean isLampLike(String deviceType) {
        String type = normalize(deviceType);
        return LAMP.equals(type) || CAM_LAMP.equals(type);
    }

    /**
     * 是否为独立摄像头设备。
     */
    public static boolean isCam(String deviceType) {
        return CAM.equals(normalize(deviceType));
    }

    /**
     * 是否为专用拍照控制器设备。
     */
    public static boolean isCaptureController(String deviceType) {
        return CAM_CAPTURE.equals(normalize(deviceType));
    }
}
