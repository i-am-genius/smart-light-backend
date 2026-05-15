# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 沟通

始终使用中文沟通。

## 构建与运行

```bash
./mvnw compile              # 编译
./mvnw spring-boot:run      # 启动 (默认端口 3000)
```

## 项目概况

智慧服装店照明系统后端。Spring Boot 4.0.5 + MyBatis-Plus 3.5.15 + MySQL + WebSocket + JWT。

### 三层系统

| 子项目 | 路径 | 技术栈 |
|--------|------|--------|
| 后端 (本项目) | `E:\smart-light-backend` | Java 17, Spring Boot 4 |
| Web 前端 | `E:\smart-light-front` | Vue 3, TypeScript, Vite |
| 小程序前端 | `E:\smart-light-mini` | uni-app, Vue 3 |
| ESP8266 固件 | `E:\8266_OTA` | PlatformIO, Arduino, C++ |

## 技术栈

- **框架**: Spring Boot 4.0.5, Spring Security, Spring WebSocket
- **ORM**: MyBatis-Plus 3.5.15 (注解驱动, 无 XML mapper, 用 `LambdaQueryWrapper`)
- **数据库**: MySQL (`smart_light` 库, 用户 `smartlight`)
- **认证**: JWT (HMAC256, 7天过期), BCrypt 密码存储
- **API 文档**: Swagger UI (`/swagger-ui.html`)
- **AI 集成**: 两个独立 Python 服务 — 面料识别 (5011端口), 人流检测 (5000端口)

### 外部 AI 服务

| 服务 | 默认地址 | 用途 |
|------|---------|------|
| 面料识别 | `http://127.0.0.1:5011/predict` | 服装面料材质识别 |
| 人流检测 | `http://127.0.0.1:5000/detect_binary` | 人体检测/计数 |

## 包结构

```
com.genius.smartlight
  ├── controller/admin/   REST 接口
  │   ├── auth/           登录/注册
  │   ├── device/         设备 CRUD, OTA, 在线状态, 上报, 固件管理
  │   ├── ai/             面料识别
  │   ├── analytics/      人流分析
  │   ├── lux/            光照数据
  │   ├── duration/       停留时长
  │   ├── store/          店铺管理
  │   ├── weather/        天气数据
  │   └── lighteffect/    灯效状态
  ├── service/            业务逻辑 (接口 + impl/)
  ├── dal/
  │   ├── dataobject/     DO 实体 (DeviceDO, StoreDO, UserAccountDO 等)
  │   └── mysql/          MyBatis-Plus Mapper 接口
  ├── websocket/          WS 连接管理、设备会话、浏览器会话、推送服务
  ├── security/           JWT 过滤器、SecurityConfig、SecurityUtils
  ├── schedule/           定时任务 (设备在线检测, 天气采集)
  ├── config/             CORS, Jackson, Swagger, WebSocket 配置
  ├── integration/ai/     AI 服务 HTTP 客户端
  ├── vo/                 请求/响应 DTO
  └── convert/            实体转换工具
```

## 两个 WebSocket 端点

| 端点 | 路径 | 连接方 | 鉴权 |
|------|------|--------|------|
| 浏览器 WS | `/ws` | Vue 前端 | JWT (Sec-WebSocket-Protocol 头) |
| 设备 WS | `/ws/device` | ESP8266/ESP32 | 无 (permitAll) |

### 浏览器 WS 消息类型 (服务端→前端)

`state`, `lightEffectState`, `onlineStatus`, `fabricRecognize`, `deviceDeleted`, `personDetection`, `durationUpdate`, `lux`, `announce`

广播按 `storeId` 隔离。`broadcastToStore(storeId, payload)` 确保数据隔离。

### 设备 WS 消息流程

1. 设备连接后发送 `{"type":"register","chipId":"lamp-XXX"}` 
2. 服务端通过 `DeviceSessionManager` 管理会话 (15秒在线超时)
3. 设备心跳: `{"type":"ping"}`
4. 服务端→设备: `state` (灯光控制), `effect` (灯效), `locate` (定位), `arm` (云台), `ota_update` (固件升级)

## 安全基线

- **storeId 绝不信任请求参数**, 必须从 `SecurityUtils.getCurrentUserId()` 查 `StoreDO` 获取
- 数据库查询用 `LambdaQueryWrapper` (类型安全)
- 密码 BCrypt 存储
- 设备上报接口 (`/admin/device/announce`, `/admin/device/state-report`, `/admin/lux/create`, `/admin/duration/create`) 保持 `permitAll`，兼容旧设备
- `/ws/device` 连接参数不变

## 设备协议约束

- **不随意修改单片机通信协议**
- 不修改 ESP8266/ESP32 固件
- 不引入 HMAC 签名、设备密钥等需固件配合的机制
- 设备上报接口保持 permitAll

## 后端修改规范

- 不要凭空新增接口 (除非明确要求)
- storeId 校验在 Service 层做, 不在 Controller 做
- 编译通过 (`mvnw compile`) 再提交
- 异常不泄露堆栈

## 相关文件

- `E:\smart-light-backend\src\main\resources\application.yaml` — 所有配置 (端口, 数据库, JWT, AI 地址)
- `E:\smart-light-backend\src\main\java\com\genius\smartlight\websocket\WebSocketPushService.java` — 推送中枢
- `E:\smart-light-backend\src\main\java\com\genius\smartlight\websocket\WebSocketSessionManager.java` — 浏览器会话管理
- `E:\smart-light-backend\src\main\java\com\genius\smartlight\websocket\DeviceSessionManager.java` — 设备会话管理
- `E:\smart-light-backend\src\main\java\com\genius\smartlight\service\lighteffect\impl\LightEffectServiceImpl.java` — 灯效 Wave 调度逻辑
