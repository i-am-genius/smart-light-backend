package com.genius.smartlight.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "认证接口通用返回结果。code = 200 表示成功，code != 200 表示业务失败")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    @Schema(description = "业务状态码。200 表示成功，非 200 表示业务失败", example = "200")
    private Integer code;

    @Schema(description = "返回数据。登录成功时为 LoginRespVO；注册成功时为提示文本")
    private T data;

    @Schema(description = "返回消息。失败时为可展示给前端用户的错误原因", example = "success")
    private String msg;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, data, "操作成功");
    }

    public static <T> ApiResponse<T> fail(String msg) {
        return new ApiResponse<>(500, null, msg);
    }
}
