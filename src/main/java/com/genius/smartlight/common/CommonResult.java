package com.genius.smartlight.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "通用返回结果。所有业务接口统一返回 code、msg、data；code = 200 表示业务成功，code != 200 表示业务失败")
@Data
public class CommonResult<T> {

    @Schema(description = "业务状态码。200 表示成功，非 200 表示业务失败", example = "200")
    private Integer code;

    @Schema(description = "返回消息。失败时为可展示给前端用户的错误原因", example = "success")
    private String msg;

    @Schema(description = "返回数据。不同接口对应不同响应对象；业务失败时通常为 null")
    private T data;

    public static <T> CommonResult<T> success(T data) {
        CommonResult<T> result = new CommonResult<>();
        result.setCode(200);
        result.setMsg("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> CommonResult<T> error(Integer code, String msg) {
        CommonResult<T> result = new CommonResult<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(null);
        return result;
    }
}
