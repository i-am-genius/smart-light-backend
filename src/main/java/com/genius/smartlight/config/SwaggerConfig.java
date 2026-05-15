package com.genius.smartlight.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI smartLightOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("智慧服装店智能照明系统后端 API")
                        .description("""
                                智慧服装店智能照明系统后端接口文档。

                                通用返回结构：所有业务接口统一返回 { code, msg, data }，code = 200 表示业务成功，code != 200 表示业务失败，失败原因见 msg。

                                WebSocket 说明：
                                1. 浏览器 WebSocket 地址：/ws，用于接收 state、onlineStatus、fabricRecognize、personDetection、durationUpdate 等推送消息。
                                2. 设备 WebSocket 地址：/ws/device，设备连接后发送注册消息，例如 {"type":"register","chipId":"ABC123456"}。
                                3. 设备状态同步以 chipId 作为设备匹配主键，亮度 brightness、色温 temp、自动模式 autoMode、AI 识别结果 fabric/mainColorRgb/recommendedBrightness/recommendedTemp 会通过状态或 AI 消息推送给前端。
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Hao Lee")))
                .externalDocs(new ExternalDocumentation()
                        .description("Project Repository")
                        .url("https://github.com/lodge520/light"));
    }

    @Bean
    public GroupedOpenApi deviceApi() {
        return GroupedOpenApi.builder()
                .group("设备接口")
                .pathsToMatch("/admin/device/**")
                .build();
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("认证接口")
                .pathsToMatch("/api/auth/**")
                .build();
    }

    @Bean
    public GroupedOpenApi aiApi() {
        return GroupedOpenApi.builder()
                .group("AI识别接口")
                .pathsToMatch("/admin/ai/**")
                .build();
    }

    @Bean
    public GroupedOpenApi luxApi() {
        return GroupedOpenApi.builder()
                .group("光照数据接口")
                .pathsToMatch("/admin/lux/**")
                .build();
    }

    @Bean
    public GroupedOpenApi durationApi() {
        return GroupedOpenApi.builder()
                .group("停留时长接口")
                .pathsToMatch("/admin/duration/**")
                .build();
    }
}
