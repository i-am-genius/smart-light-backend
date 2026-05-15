# 会话交接信息

> 最后更新：2026-05-12

## 项目信息

| 项目 | 路径 | 技术栈 |
|------|------|--------|
| 后端 | `E:\smart-light-backend` | Spring Boot 4.0.5 + MyBatis-Plus 3.5.15 + MySQL |
| Web 前端 | `E:\smart-light-front` | Vue 3 |
| uniapp 移动端 | `E:\smart-light-mini` | uniapp (微信小程序兼容) |

## 当前工作区状态

- **Git**: 非 git 仓库，无版本控制
- **编译状态**: `mvnw compile` BUILD SUCCESS
- **未提交变更**: 无 git，见下文"本次修改文件清单"

## 本次会话完成的工作

### 1. 安全审计报告（只读分析）
- 生成 `docs/backend_security_audit_report.md`，约 600 行
- 发现高危 7 个、中危 12 个、低危/信息 10 个
- 核心问题：设备无身份认证、数据隔离缺失、WebSocket 全局广播

### 2. storeId 数据隔离修复（16 个文件）
修复了 18 个接口的水平越权（IDOR）问题，所有用户侧接口现在强制校验 storeId 归属：

| 模块 | 修复内容 |
|------|---------|
| DeviceServiceImpl | getDevice/getDeviceList/getDeviceByChipId/updateDevice/deleteDevice 增加 storeId 校验 |
| DeviceControlServiceImpl | syncStateToDevice 增加 storeId 校验 |
| DeviceOtaServiceImpl | checkUpdate/startUpdate 增加 storeId 校验 |
| DeviceOnlineServiceImpl | getOnlineStatus 增加 storeId 校验，getOnlineStatusList 增加 storeId 过滤 |
| DurationServiceImpl | 所有查询方法增加 storeId 过滤 |
| DeviceGatewayController | arm/cloth-upload/flow-upload/locate 增加 storeId 校验 |
| LuxServiceImpl | createLuxRecord 增加日志 + storeId 推送 |
| AiServiceImpl | updateDeviceAiResult/personDetect 增加归属校验 |
| AnalyticsController | resolveStoreId 增加当前用户店铺校验 |
| WeatherServiceImpl | 原有 getOwnedStore 已做校验，无修改 |

### 3. WebSocket 按店铺推送（3 个核心文件）
- `WebSocketSessionManager`：新增 session→storeId 映射、broadcastToStore()、broadcastAll()
- `AppWebSocketHandler`：连接时从 SecurityContext 解析 storeId 并绑定
- `WebSocketPushService`：所有推送方法按 storeId 路由，LightEffect 保留全局广播

### 4. 回归检查修复（2 个文件）
- `WebSocketPushService.pushAnnounce`：未绑定设备（storeId=null）改用全局广播，保证扫描功能
- `AiServiceImpl`：补充 updateDeviceAiResult 和 personDetect 的 storeId 归属校验

## 本次修改文件清单

1. `vo/device/DeviceRespVO.java` — 新增 storeId 字段
2. `convert/device/DeviceConvert.java` — 启用 storeId 填充
3. `websocket/WebSocketSessionManager.java` — 重写，按 storeId 路由
4. `websocket/AppWebSocketHandler.java` — 新增 StoreMapper，绑定 storeId
5. `websocket/WebSocketPushService.java` — 重写推送方法，新增 broadcastToStore/broadcastAll
6. `service/device/DeviceOnlinePushService.java` — pushOnlineStatus 传入 storeId
7. `service/device/impl/DeviceServiceImpl.java` — 新增 storeId 校验方法，所有操作增加归属校验
8. `service/device/impl/DeviceControlServiceImpl.java` — 新增 StoreMapper，增加 storeId 校验
9. `service/device/impl/DeviceOtaServiceImpl.java` — 新增 StoreMapper，增加 storeId 校验
10. `service/device/impl/DeviceOnlineServiceImpl.java` — 新增 StoreMapper，过滤 storeId
11. `service/duration/impl/DurationServiceImpl.java` — 新增 StoreMapper，所有查询增加 storeId 过滤
12. `service/lux/impl/LuxServiceImpl.java` — 新增日志，pushLux 传入 storeId
13. `service/ai/impl/AiServiceImpl.java` — 新增 StoreMapper/SecurityUtils，增加归属校验
14. `controller/admin/device/DeviceGatewayController.java` — 新增 StoreMapper，增加 storeId 校验
15. `controller/admin/analytics/AnalyticsController.java` — 新增 StoreMapper，增加 storeId 校验
16. `service/device/impl/DeviceReportServiceImpl.java` — 新增上报日志

## 未修复的安全问题（H-6 设备认证）

当前阶段保留 chipId 兼容接入（不修改 ESP8266 协议）。后续完整方案：
- 设备激活时生成 deviceSecret 写入数据库 + 烧录固件
- 设备注册/上报携带 HMAC-SHA256(chipId + timestamp + nonce, deviceSecret)
- 服务端验证签名 + nonce 防重放

## 待验证/潜在问题

1. **Announce 新设备扫描**：已修复未绑定设备 broadcastAll，需真机验证扫描功能
2. **WebSocket storeId 绑定**：SecurityContextHolder 方式理论上可靠，需在浏览器端验证
3. **LightEffect 跨店铺修改**：wave 效果会修改所有店铺的 lamp 设备状态，这是产品设计，但需确认是否需要改为 per-store
4. **personDetect storeId 为 null 时静默跳过**：如 chipId 非当前用户店铺，推送被跳过（不报错），前端可能无感知

## 重要文件路径

| 用途 | 路径 |
|------|------|
| 安全配置 | `security/SecurityConfig.java` |
| JWT 过滤器 | `security/JwtAuthenticationFilter.java` |
| JWT 服务 | `security/JwtTokenService.java` |
| 配置文件 | `src/main/resources/application.yaml` |
| WebSocket 配置 | `config/WebSocketConfig.java` |
| CORS 配置 | `config/CorsConfig.java` |
| OTA 资源映射 | `config/OtaStaticResourceConfig.java` |
| 设备控制器 | `controller/admin/device/DeviceController.java` |
| 设备网关控制器 | `controller/admin/device/DeviceGatewayController.java` |
| 安全审计报告 | `docs/backend_security_audit_report.md` |

## 构建命令

```bash
cd E:/smart-light-backend
./mvnw compile          # 编译
./mvnw test             # 运行测试
./mvnw spring-boot:run  # 启动服务 (端口 3000)
```

## 注意事项和禁止事项

- ❌ **不修改单片机协议**（ESP8266/ESP32 固件不动）
- ❌ **不引入 HMAC 或设备签名**（当前阶段）
- ❌ **不修改前端接口路径**（Web 前端和 uniapp 路径不动）
- ❌ **不在 Controller 做 storeId 校验**（统一在 Service 层）
- ❌ **不信任请求参数里的 storeId**（必须从 SecurityUtils 获取当前用户再查 storeId）
- ❌ **不使用全局 broadcast**（除非是未绑定 announce 或 LightEffect 系统级消息）
- ✅ 新增 StoreMapper 依赖用 Lombok @RequiredArgsConstructor 自动注入
- ✅ 校验失败统一返回 "无权操作该设备" 或 "设备不存在"
- ✅ 编译通过再提交
