package com.genius.smartlight.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "账号登录响应结果")
public class LoginRespVO {

    @Schema(description = "登录令牌 Token。前端后续请求通过 Authorization 携带", example = "eyJhbGciOiJIUzI1NiJ9.xxx")
    private String token;

    @Schema(description = "用户ID", example = "1")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long userId;

    @Schema(description = "用户名", example = "admin")
    private String username;

    @Schema(description = "当前用户所属店铺ID，未配置店铺时可能为空", example = "1001")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long storeId;

    @Schema(description = "当前用户所属店铺名称，未配置店铺时可能为空", example = "智慧服装体验店")
    private String storeName;

    @Schema(description = "店铺风格，用于智能推荐或页面展示", example = "简约现代")
    private String storeStyle;

    @Schema(description = "店铺所在省份", example = "湖南省")
    private String province;

    @Schema(description = "店铺所在城市", example = "长沙市")
    private String city;

    @Schema(description = "是否已完成店铺配置。false 时前端可引导进入店铺设置", example = "true")
    private Boolean storeConfigured;
}
