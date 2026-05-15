# 后端安全风险审计报告

> 审计日期：2026-05-11
> 审计范围：smart-light-backend（Spring Boot 4.0.5 + MyBatis-Plus 3.5.15）
> 审计方式：代码静态分析，未修改业务代码

---

## 1. 审计范围

| 审计对象 | 路径 | 文件数 |
|---------|------|-------|
| Spring Security 配置 | `security/SecurityConfig.java` | 1 |
| JWT 认证 | `security/JwtAuthenticationFilter.java`, `security/JwtTokenService.java`, `security/SecurityUtils.java` | 3 |
| CORS 配置 | `config/CorsConfig.java` | 1 |
| WebSocket 配置与处理 | `config/WebSocketConfig.java`, `websocket/*.java` | 7 |
| Controller（admin） | `controller/admin/**/*.java` | 14 |
| Service 实现 | `service/**/impl/*.java` | 15 |
| Mapper（DAO） | `dal/mysql/*.java` | 7 |
| 数据实体 | `dal/dataobject/*.java` | 7 |
| 集成客户端（AI） | `integration/ai/*.java` | 2 |
| 定时任务 | `schedule/*.java` | 2 |
| 全局异常处理 | `common/GlobalExceptionHandler.java` | 1 |
| 配置文件 | `application.yaml`, `pom.xml` | 2 |
| 静态资源映射 | `config/OtaStaticResourceConfig.java` | 1 |

共审计约 **100 个 Java 源文件**及全部配置文件。

---

## 2. 风险总览

| 编号 | 风险类型 | 风险等级 | 位置 | 简要说明 | 是否需要立即修复 |
|------|---------|---------|------|---------|----------------|
| H-1 | 设备 WebSocket 无鉴权 | **高危** | `WebSocketConfig.java:24-26`, `SecurityConfig.java:50` | `/ws/device` 无任何鉴权，任何人可伪造设备连接并注册任意 chipId | **是** |
| H-2 | 设备上报接口无鉴权 | **高危** | `SecurityConfig.java:53-58` | `/admin/device/state-report`, `/admin/lux/create`, `/admin/duration/create` 完全放行，可伪造数据 | **是** |
| H-3 | 设备控制接口缺少归属校验 | **高危** | `DeviceGatewayController.java:82-113`, `DeviceController.java:118-158`, `DeviceControlServiceImpl.java:31-95` | 已认证用户可通过 chipId 控制任意设备，未校验设备是否属于当前用户店铺 | **是** |
| H-4 | WebSocket 全局广播数据泄露 | **高危** | `WebSocketSessionManager.java:43-45`, `WebSocketPushService.java:132-139` | 所有浏览器端 WebSocket 客户端接收全局广播，用户 A 可看到用户 B 的设备状态、光照、AI 识别结果等 | **是** |
| H-5 | 数据查询缺少店铺隔离 | **高危** | `DeviceServiceImpl.java`, `DurationServiceImpl.java`, `DeviceOnlineServiceImpl.java`, `WeatherController.java`, `AnalyticsController.java` | 多处按 chipId 查询设备/光照/停留数据未校验当前用户店铺归属 | **是** |
| H-6 | 设备无身份验证机制 | **高危** | `DeviceWebSocketHandler.java:45-53`, 所有设备上报接口 | chipId 是设备唯一标识，无密钥/签名/证书，任何人都可冒充设备 | **是** |
| H-7 | AI 面料归档接口完全公开 | **高危** | `SecurityConfig.java:34-37`, `AiController.java:65-84` | GET/DELETE `/admin/ai/fabric-archive` 均 permitAll，可被任意查看和删除 | **是** |
| H-8 | OTA 固件下载公开可访问 | **中危** | `SecurityConfig.java:62-63`, `OtaFirmwareDownloadController.java:25-49` | `/ota/**` 完全放行，固件文件无需认证即可下载 | 是 |
| H-9 | JWT Secret 硬编码且弱 | **中危** | `application.yaml:26`, `JwtTokenService.java:13-14` | 默认 secret `smart-light-secret-2026`，token 有效期 7 天，无刷新/撤销机制 | 是 |
| H-10 | 登录接口无暴力破解防护 | **中危** | `AuthServiceImpl.java:67-112` | 登录接口无速率限制、无账号锁定、无失败计数 | 是 |
| H-11 | 注册接口无滥用防护 | **中危** | `AuthServiceImpl.java:31-63` | permitAll 注册，无验证码、无邮箱验证、无速率限制 | 是 |
| H-12 | 固件上传缺少内容校验 | **中危** | `DeviceOtaFirmwareServiceImpl.java:132-141` | 仅检查 `.bin` 扩展名，未校验 Magic Bytes 或固件签名 | 是 |
| H-13 | 固件 URL 由客户端 Host 头构造 | **中危** | `DeviceOtaFirmwareServiceImpl.java:66-73`, `DeviceOtaFirmwareController.java:39-43` | 固件下载 URL 使用客户端提供的 Host 头拼接，可能被操纵 | 是 |
| H-14 | OTA 固件 MD5 非服务端计算 | **中危** | `DeviceOtaFirmwareServiceImpl.java:39-40` | MD5 由上传者提供，非服务端计算，设备无法验证固件完整性 | 是 |
| H-15 | AI 上传接口无文件类型校验 | **中危** | `AiServiceImpl.java:203-207` | 面料识别/人流检测上传仅检查空文件，未校验 MIME 类型和扩展名 | 是 |
| H-16 | WebSocket Token 经 URL 参数传递 | **中危** | `JwtAuthenticationFilter.java:58-62` | WebSocket JWT token 通过 URL query string 传递，可能被服务器日志、代理日志记录 | 是 |
| H-17 | CORS WebSocket 允许任意来源 | **中危** | `CorsConfig.java:17-21`, `WebSocketConfig.java:22-25` | `/ws` 和 `/ws/device` 的 CORS 允许 `*` 来源 | 是 |
| H-18 | 用户数据隔离不完整 | **中危** | `DeviceServiceImpl.java:187-211`, `DeviceOnlineServiceImpl.java:41-52`, `DurationServiceImpl.java:97-171` | 部分查询接口返回全部数据而不按当前用户店铺过滤 | 是 |
| H-19 | 设备在线列表无过滤 | **中危** | `DeviceOnlineServiceImpl.java:41-52`, `DeviceOnlineController.java:34-37` | `/admin/device/online-list` 返回所有设备在线状态，非仅当前用户店铺 | 是 |
| L-1 | 依赖版本信息 | **低危** | `pom.xml` | Spring Boot 4.0.5, MyBatis-Plus 3.5.15, java-jwt 4.4.0 - 无明显已知高危漏洞 | 否 |
| L-2 | BCrypt 密码哈希 | **信息** | `AuthServiceImpl.java:58`, `SecurityConfig.java:22-24` | ✅ 密码使用 BCryptPasswordEncoder 存储，实现正确 | — |
| L-3 | SQL 参数化查询 | **信息** | `DurationRecordMapper.java:15-24`, `LuxRecordMapper.java:12-17` | ✅ 所有 `@Insert` 使用 `#{}` 参数化查询，无 SQL 注入风险 | — |
| L-4 | MyBatis `.last()` 用法安全 | **信息** | `AnalyticsController.java:58`, `DeviceReportServiceImpl.java:37` 等多处 | `.last()` 仅使用常量字符串（如 `"limit 1"`），无用户输入拼接 | — |
| L-5 | LambdaQueryWrapper 防注入 | **信息** | 全局 | ✅ 所有查询使用 LambdaQueryWrapper（类型安全），无 `${}` 用法 | — |
| L-6 | 路径穿越防护 | **信息** | `OtaFirmwareDownloadController.java:36-38`, `AiController.java:154-159, 168-173` | ✅ OTA 下载和归档删除均做了路径规范化与 startsWith 检查 | — |
| L-7 | 异常信息不泄露 | **信息** | `GlobalExceptionHandler.java:31-35` | ✅ 通用异常返回统一错误信息，不暴露堆栈。但 ServiceException 透传业务错误信息 | — |
| L-8 | 日志可能泄露敏感信息 | **低危** | `WebSocketPushService.java:55-58`, `AiServiceImpl.java:37` | log.info 记录了 chipId/文件名/IP，未发现直接打印 token/密码 | 否 |
| L-9 | Swagger UI 暴露 | **低危** | `SecurityConfig.java:42-46`, `application.yaml:29-35` | Swagger UI 需要认证（除 `/v3/api-docs` permitAll），但 API 文档结构暴露 | 否 |
| L-10 | 生产/开发配置混用风险 | **低危** | `application.yaml` | 使用 Spring 默认值语法，但环境变量若未设置会使用硬编码默认值（含密码） | 否 |

---

## 3. 高危问题详情

### H-1 设备 WebSocket 无鉴权，可冒充任意设备

- **位置**：`config/WebSocketConfig.java:24-26`, `security/SecurityConfig.java:50`
- **相关代码**：
  ```java
  // SecurityConfig.java:50
  .requestMatchers("/ws/device").permitAll()

  // WebSocketConfig.java:24-26
  registry.addHandler(deviceWebSocketHandler, "/ws/device")
          .setAllowedOriginPatterns("*");
  ```
- **风险说明**：`/ws/device` 在 Spring Security 中完全放行，WebSocket 层也允许任意来源连接。设备 WebSocket Handler (`DeviceWebSocketHandler.java:45-53`) 在收到 `{"type":"register","chipId":"xxx"}` 消息时直接注册设备，不校验任何凭证。
- **可能后果**：
  - 攻击者可连接 WebSocket 并以任意 chipId 注册，冒充合法设备
  - 冒充后可接收服务端下发的所有指令（灯光控制、机械臂控制、OTA 固件 URL、定位指令等）
  - 可上报虚假固件版本信息，干扰 OTA 升级
  - 可通过 ping 维持心跳，使设备保持"在线"状态
- **修复建议**：
  1. 为设备颁发预共享密钥（PSK）或设备 Token，存储在设备固件中
  2. 设备注册时要求提供 chipId + deviceSecret 签名
  3. 或在数据库中为每个设备生成唯一 token，设备首次激活时获取
  4. WebSocket 连接建立后要求设备先发送认证消息
- **建议优先级**：**立即修复（P0）**

### H-2 设备上报接口无鉴权，可伪造传感器数据

- **位置**：`security/SecurityConfig.java:53-58`
- **相关代码**：
  ```java
  .requestMatchers(HttpMethod.POST,
          "/admin/device/announce",
          "/admin/device/state-report",
          "/admin/lux/create",
          "/admin/duration/create"
  ).permitAll()
  ```
- **风险说明**：这 4 个设备上报接口完全不需要认证。任何人只要知道一个有效的 chipId（chipId 在设备固件、前端、URL 中广泛暴露），就可以：
  - 伪造设备上线通告 (`announce`)
  - 伪造设备状态上报 (`state-report`)
  - 伪造光照数据 (`lux/create`)
  - 伪造停留时长数据 (`duration/create`)
- **可能后果**：
  - 传感器数据污染，影响 AI 分析、看板统计
  - 伪造设备上线/下线状态，干扰运维
  - 伪造停留时长数据影响商业决策
- **修复建议**：
  1. 短期：要求设备上报时携带基于共享密钥的 HMAC 签名
  2. 长期：引入设备证书或 Token 机制
  3. 在 SecurityConfig 中移除这些接口的 permitAll，改为设备专用的认证过滤器
- **建议优先级**：**立即修复（P0）**

### H-3 设备控制接口缺少归属校验

- **位置**：`DeviceGatewayController.java:82-113`, `DeviceController.java:118-158`, `DeviceControlServiceImpl.java:31-95`
- **相关代码**（以 arm 控制为例）：
  ```java
  // DeviceGatewayController.java:82-86
  @PostMapping("/arm/{chipId}")
  public CommonResult<Boolean> armControl(@PathVariable String chipId, ...) {
      DeviceDO device = deviceMapper.selectOne(
              new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getChipId, chipId)
      );
      if (device == null) { throw new ServiceException("设备不存在"); }
      // 仅校验设备是否存在，未校验是否属于当前用户店铺
  ```
- **风险说明**：以下接口都需要认证，但**只校验设备是否存在，不校验设备是否属于当前用户的店铺**：
  - `/admin/device/arm/{chipId}` — 云台/机械臂控制
  - `/admin/device/effect/{chipId}` — 灯光效果下发
  - `/admin/device/locate/{chipId}` — 设备定位
  - `/admin/device/cloth-upload/{chipId}` — 触发上传
  - `/admin/device/flow-upload/{chipId}` — 人流上传开关
  - `/admin/device/state-sync/{chipId}` — 状态同步
  - `/admin/device/{chipId}/ota/check` — OTA 检查
  - `/admin/device/{chipId}/ota/update` — OTA 下发
- **可能后果**：任何已登录用户（包括注册的恶意用户）都可以控制系统中任意设备——开关灯、移动机械臂、下发 OTA、改变灯光参数。这是典型的**水平越权（IDOR）**。
- **修复建议**：
  1. 在每个 chipId 相关操作中增加 `storeId` 校验：查询设备后比对 `device.getStoreId()` 是否等于当前用户的 storeId
  2. 建议在 Service 层抽取 `getDeviceByChipIdAndCurrentStore(chipId)` 公共方法
- **建议优先级**：**立即修复（P0）**

### H-4 WebSocket 全局广播，跨用户数据泄露

- **位置**：`WebSocketSessionManager.java:43-45`, `WebSocketPushService.java:132-139`
- **相关代码**：
  ```java
  // WebSocketSessionManager.java:43-45
  public void broadcast(String payload) {
      sessions.values().forEach(session -> send(session, payload));
  }
  // 所有 push 方法最终都调用 broadcast()，无任何 storeId 过滤
  ```
- **风险说明**：`WebSocketSessionManager` 维护所有浏览器 WebSocket 连接，`broadcast()` 向所有连接发送消息。`WebSocketPushService` 的所有 push 方法（`pushState`, `pushLux`, `pushDuration`, `pushFabricRecognize`, `pushPersonDetect`, `pushAnnounce`, `pushOnlineStatus`, `pushLightEffectState`）都通过 `broadcast()` 全局推送。
- **可能后果**：用户 A 的浏览器 WebSocket 会接收到用户 B 的设备状态更新、光照数据、停留时长、AI 面料识别结果、人体检测结果、设备上线通告等。这是严重的数据隔离问题。
- **修复建议**：
  1. `WebSocketSessionManager` 需要维护 session → userId → storeId 的映射
  2. `broadcast()` 改为按 storeId 分组推送
  3. 或改为点对点推送（按 storeId 路由）
  4. AppWebSocketHandler 在连接建立时从 SecurityContext 获取用户信息并关联 session
- **建议优先级**：**立即修复（P0）**

### H-5 数据查询缺少店铺隔离

- **位置**：多处
- **相关代码**：
  1. **设备列表全量返回** — `DeviceServiceImpl.java:197-199`：
     ```java
     public List<DeviceRespVO> getDeviceList() {
         List<DeviceDO> list = deviceMapper.selectList(null);  // 无条件查询全部设备
     ```
  2. **按 chipId 查设备不验归属** — `DeviceServiceImpl.java:202-211`：
     ```java
     public DeviceRespVO getDeviceByChipId(String chipId) {
         DeviceDO device = deviceMapper.selectOne(
                 new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getChipId, chipId)
         );
     ```
  3. **停留时长查询无 storeId 过滤** — `DurationServiceImpl.java:84-171`：`getByChipIdAndDate`, `getListByChipId`, `getListByDateRange`, `getSumByDateRange` 均只按 chipId 查询，未加 storeId 条件
  4. **设备更新/删除无归属校验** — `DeviceServiceImpl.java:103-149`：`updateDevice(id)` 和 `deleteDevice(id)` 只按主键查询，不校验 storeId
  5. **天气接口不限制 storeId** — `WeatherController.java:26-33`：可传入任意 storeId 查询/采集天气
  6. **设备在线列表全量返回** — `DeviceOnlineServiceImpl.java:41-52`
- **可能后果**：已认证用户可以：
  - 查看所有店铺的设备列表和详情
  - 修改/删除其他店铺的设备
  - 查询其他店铺的停留时长数据和汇总
  - 获取任何店铺的天气数据
- **修复建议**：
  1. 在 `getDeviceList()` 等查询中增加 `storeId` 过滤
  2. 在 `updateDevice()`/`deleteDevice()` 中校验设备是否属于当前用户店铺
  3. Duration 查询统一加上 storeId 条件
  4. Weather 接口改为从 SecurityContext 获取 storeId，不接受外部传入
- **建议优先级**：**立即修复（P0）**

### H-6 设备无身份验证机制（chipId 即身份）

- **位置**：全局
- **相关代码**：
  - `DeviceWebSocketHandler.java:45-53`：设备注册仅凭 chipId
  - `DeviceReportServiceImpl.java:30-93`：设备上报仅凭 chipId
  - `DeviceGatewayController.java:58-79`：设备宣告仅凭 chipId
- **风险说明**：系统设计中，chipId 是设备唯一标识也是设备"身份"。但 chipId 是公开信息（出现在前端界面、URL、日志、固件中），且没有任何密钥、签名、证书或白名单机制来验证通信方确实是该 chipId 对应的真实设备。
- **可能后果**：
  - 任何人知道 chipId 就能冒充设备
  - 全链路：设备 WebSocket 注册 → HTTP 上报 → 状态同步 → 接收控制指令 → OTA 升级，全部可被冒充
- **修复建议**：
  1. 为每个设备生成预共享密钥（PSK），烧录在固件中，同时存储在数据库
  2. 设备注册时要求发送 `HMAC(chipId + timestamp, PSK)` 签名
  3. HTTP 上报接口要求携带相同签名
  4. 引入 nonce/timestamp 防重放
- **建议优先级**：**立即修复（P0）**

### H-7 AI 面料归档接口完全公开

- **位置**：`SecurityConfig.java:34-37`, `AiController.java:65-84`
- **相关代码**：
  ```java
  // SecurityConfig.java:34-37
  .requestMatchers(HttpMethod.GET, "/admin/ai/fabric-archive").permitAll()
  .requestMatchers(HttpMethod.DELETE, "/admin/ai/fabric-archive").permitAll()
  ```
- **风险说明**：GET 和 DELETE `/admin/ai/fabric-archive` 都无需认证。任何人可以：
  - 浏览所有上传的面料识别归档图片（GET）
  - 删除任意归档图片组（DELETE）
- **可能后果**：
  - 敏感商业数据（服装款式、面料、店铺陈列）被任意查看
  - 归档图片被恶意删除
  - 代码注释已注明 "production should add login or an admin secret"，说明开发者已知但未修复
- **修复建议**：
  1. GET 和 DELETE 都应要求认证
  2. GET 应只返回当前用户店铺的归档图片
  3. 考虑移除 DELETE 的 permitAll，增加归属校验
- **建议优先级**：**立即修复（P0）**

---

## 4. 中危问题详情

### H-8 OTA 固件下载公开可访问

- **位置**：`SecurityConfig.java:62-63`, `OtaFirmwareDownloadController.java:25-49`
- **相关代码**：
  ```java
  .requestMatchers("/ota/**").permitAll()
  ```
- **风险说明**：`/ota/**` 路径在 SecurityConfig 中完全放行，任何人都可以下载固件文件。如果固件中包含专有算法或配置，可能被竞争对手获取。
- **可能后果**：固件文件被未授权下载、逆向分析。
- **修复建议**：设备下载固件时可要求携带设备 token 参数进行鉴权。
- **建议优先级**：近期修复（P1）

### H-9 JWT Secret 硬编码且弱密码，Token 超长有效期

- **位置**：`application.yaml:26`, `JwtTokenService.java:13-17`
- **相关代码**：
  ```yaml
  jwt:
    secret: ${JWT_SECRET:smart-light-secret-2026}
    expire-millis: ${JWT_EXPIRE_MILLIS:604800000}  # 7 天
  ```
- **风险说明**：
  1. 默认 secret `smart-light-secret-2026` 是可预测的弱密钥
  2. Token 有效期 7 天过长，一旦泄露可长时间被利用
  3. 没有 Token 刷新机制，也没有 Token 撤销/黑名单机制
- **可能后果**：
  - 弱 secret 容易被暴力破解（特别是使用 HS256 算法）
  - 无法在用户登出或账户禁用后撤销已签发的 token
- **修复建议**：
  1. 生产环境必须通过环境变量设置强随机 secret（至少 256 位）
  2. 缩短 token 有效期（例如 30 分钟），并引入 refresh token 机制
  3. 考虑使用 Redis 维护 token 黑名单，支持主动撤销
  4. 使用 RS256（非对称）代替 HS256
- **建议优先级**：近期修复（P1）

### H-10 登录接口无暴力破解防护

- **位置**：`AuthServiceImpl.java:67-112`
- **相关代码**：
  ```java
  public LoginRespVO login(LoginReqVO reqVO) {
      // 直接查询数据库比对密码，无任何速率限制或锁定机制
  }
  ```
- **风险说明**：登录接口无失败次数计数、无账号临时锁定、无验证码、无速率限制。
- **可能后果**：攻击者可通过字典攻击或暴力枚举尝试密码。
- **修复建议**：
  1. 引入连续登录失败计数（例如 5 次失败锁定 15 分钟）
  2. 增加登录速率限制（例如每 IP 每分钟最多 10 次）
  3. 考虑增加验证码（特别是在多次失败后）
- **建议优先级**：近期修复（P1）

### H-11 注册接口无滥用防护

- **位置**：`AuthServiceImpl.java:31-63`, `SecurityConfig.java:39-40`
- **相关代码**：
  ```java
  .requestMatchers("/api/auth/register").permitAll()
  ```
- **风险说明**：注册接口 permitAll，无验证码、无邮箱/手机验证、无速率限制。攻击者可以批量注册大量账号，消耗数据库资源。
- **可能后果**：批量注册垃圾账号、数据库膨胀、后续可用于越权攻击。
- **修复建议**：
  1. 增加验证码或邮箱/短信验证
  2. 增加注册速率限制（每 IP 每小时有限次数）
  3. 密码复杂度校验（当前无最小长度或复杂度要求）
- **建议优先级**：近期修复（P1）

### H-12 固件上传缺少内容校验

- **位置**：`DeviceOtaFirmwareServiceImpl.java:132-141`
- **相关代码**：
  ```java
  private void validateFile(MultipartFile file) {
      if (file == null || file.isEmpty()) {
          throw new ServiceException("固件文件不能为空");
      }
      String originalFilename = file.getOriginalFilename();
      if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".bin")) {
          throw new ServiceException("固件文件必须是 .bin 文件");
      }
  }
  ```
- **风险说明**：仅检查文件扩展名为 `.bin`，未校验文件内容是否为合法的 ESP8266 固件格式（Magic Bytes、Header 结构等）。攻击者可以上传重命名为 `.bin` 的任意文件。
- **可能后果**：恶意固件被推送到 ESP8266 设备，设备变砖或被植入恶意代码。
- **修复建议**：
  1. 校验 ESP8266 固件的 Magic Bytes（ESP8266 固件通常以 `0xE9` 开头）
  2. 校验固件 Header 结构完整性
  3. 服务端计算并存储 MD5/SHA256，而非信任用户提供的
- **建议优先级**：近期修复（P1）

### H-13 固件 URL 由客户端 Host 头构造

- **位置**：`DeviceOtaFirmwareServiceImpl.java:66-73`, `DeviceOtaFirmwareController.java:39-43`
- **相关代码**：
  ```java
  // DeviceOtaFirmwareController.java:43
  String requestHost = host != null && !host.isBlank() ? host : request.getServerName() + ":" + request.getServerPort();

  // DeviceOtaFirmwareServiceImpl.java:66-73
  String fileUrl = "http://" + normalizedHost + "/ota/" + deviceType + "/" + channel + "/" + versionCode + "/firmware.bin";
  ```
- **风险说明**：固件下载 URL 由客户端传入的 Host 头拼接。虽然服务端对 localhost 做了拒绝检查，但攻击者可以指定任意外部主机名，导致设备从非预期的地址下载固件。
- **可能后果**：如果服务端 IP 被劫持或 DNS 被污染，设备可能从恶意服务器下载固件。
- **修复建议**：
  1. 固件 URL 应使用配置文件中的服务端基础 URL，而非客户端 Host 头
  2. 在 `application.yaml` 中配置 `app.base-url`，固件 URL 由此构造
- **建议优先级**：近期修复（P1）

### H-14 OTA 固件 MD5 非服务端计算

- **位置**：`DeviceOtaFirmwareServiceImpl.java:39-40`, `DeviceOtaServiceImpl.java:85-87`
- **相关代码**：
  ```java
  // 上传时 md5 由请求参数提供（可选）
  @RequestParam(required = false) String md5

  // 下发时原样传给设备
  if (firmware.getMd5() != null && !firmware.getMd5().isBlank()) {
      msg.put("md5", firmware.getMd5());
  }
  ```
- **风险说明**：固件 MD5 由上传者可选提供，非服务端计算。如果攻击者上传了恶意固件并提供一个假的 MD5 值，设备收到后将使用这个假 MD5 做完整性校验，校验将无效。
- **可能后果**：设备无法有效验证固件完整性。
- **修复建议**：服务端在接收到固件文件后自动计算 SHA256 哈希并存储，下发时使用服务端计算的哈希。
- **建议优先级**：近期修复（P1）

### H-15 AI 上传接口无文件类型校验

- **位置**：`AiServiceImpl.java:203-207`
- **相关代码**：
  ```java
  private void validateFile(MultipartFile file) {
      if (file == null || file.isEmpty()) {
          throw new ServiceException("上传文件不能为空");
      }
  }
  // 无 MIME 类型检查、无扩展名检查、无 Magic Bytes 校验
  ```
- **风险说明**：面料识别 (`/admin/ai/fabric-recognize`) 和人流检测 (`/admin/ai/person-detect`) 接口仅检查文件是否为空，然后直接将文件内容转发给 AI 服务。攻击者可以上传任意文件类型（可执行文件、脚本、压缩包等）。
- **可能后果**：
  - AI 服务被投毒攻击
  - 上传非图片文件消耗 AI 服务资源
  - 若 AI 服务有漏洞，可能被利用
- **修复建议**：
  1. 校验 MIME 类型为 `image/jpeg` 或 `image/png`
  2. 校验文件扩展名为 `.jpg`、`.jpeg`、`.png`
  3. 校验文件 Magic Bytes（JPEG: `FF D8 FF`, PNG: `89 50 4E 47`）
  4. 可考虑设置更小的文件大小限制（如 5MB）
- **建议优先级**：近期修复（P1）

### H-16 WebSocket Token 经 URL 参数传递

- **位置**：`JwtAuthenticationFilter.java:58-62`
- **相关代码**：
  ```java
  if (requestUri != null && requestUri.startsWith("/ws")) {
      String token = request.getParameter("token");
  ```
- **风险说明**：WebSocket 握手时的 JWT token 通过 URL query string 传递 (`ws://host/ws?token=xxx`)。这种方式的 token 会被记录在：
  - 服务器访问日志（Nginx、Tomcat access log）
  - 反向代理日志
  - 浏览器历史记录
  - Referer 头（如果页面有外链）
- **可能后果**：Token 通过日志泄露。
- **修复建议**：WebSocket 连接建立后，在首次消息中传递 token，而非 URL 参数。或将 token 放在 WebSocket 子协议中。
- **建议优先级**：近期修复（P1）

### H-17 CORS WebSocket 允许任意来源

- **位置**：`CorsConfig.java:17-21`, `WebSocketConfig.java:22-25`
- **相关代码**：
  ```java
  webSocketConfig.setAllowedOriginPatterns(List.of("*"));
  registry.addHandler(deviceWebSocketHandler, "/ws/device").setAllowedOriginPatterns("*");
  ```
- **风险说明**：WebSocket 端点的 CORS 允许任意来源，结合 `/ws/device` 无鉴权，任意网站都可以通过 JavaScript 发起 WebSocket 连接冒充设备。
- **可能后果**：扩大了设备冒充的攻击面。
- **修复建议**：
  1. `/ws` 浏览器端限制为前端域名
  2. `/ws/device` 不需要 CORS（设备不是浏览器），建议移除 CORS 宽松配置或拒绝所有浏览器来源
- **建议优先级**：近期修复（P1）

### H-18 设备 CRUD 的归属校验不完整

- **位置**：`DeviceServiceImpl.java:187-211`, `DeviceOnlineServiceImpl.java:41-52`
- **相关代码**：
  ```java
  // getDevice(id) - 未校验 storeId
  public DeviceRespVO getDevice(Long id) {
      DeviceDO device = deviceMapper.selectById(id);
      ...

  // getDeviceByChipId(chipId) - 未校验 storeId
  public DeviceRespVO getDeviceByChipId(String chipId) {
      DeviceDO device = deviceMapper.selectOne(
              new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getChipId, chipId)
      );
  ```
- **风险说明**：`getDevice(id)`, `getDeviceByChipId(chipId)`, `getDeviceList()` (全量), `updateDevice(id)`, `deleteDevice(id)` 均未校验设备是否属于当前用户店铺。
- **可能后果**：用户可查看/修改/删除其他店铺的设备。
- **修复建议**：所有设备查询/修改/删除操作都应校验 `storeId` 归属。
- **建议优先级**：近期修复（P1）

### H-19 设备在线列表无过滤

- **位置**：`DeviceOnlineServiceImpl.java:41-52`
- **相关代码**：
  ```java
  public List<DeviceOnlineStatusRespVO> getOnlineStatusList() {
      List<DeviceDO> devices = deviceMapper.selectList(null);  // 全量查询
  ```
- **风险说明**：查询全部设备的在线状态，未按当前用户店铺过滤。
- **可能后果**：用户可以看到所有店铺的设备在线列表。
- **修复建议**：过滤为当前用户 storeId 的设备。
- **建议优先级**：近期修复（P1）

---

## 5. 低危与加固建议

### L-1 依赖版本
- **位置**：`pom.xml`
- **说明**：Spring Boot 4.0.5（截至审计日最新为 4.0.x 系列）、MyBatis-Plus 3.5.15（较新）、java-jwt 4.4.0（最新）。未发现已知高危 CVE。建议定期 `mvn versions:display-dependency-updates` 检查更新。
- **建议优先级**：后续加固（P2）

### L-4 日志中可能包含敏感信息
- **位置**：`WebSocketPushService.java:55-58`, `AiServiceImpl.java:37-38`
- **相关代码**：
  ```java
  log.info("fabricRecognize start chipId={} filename={} fileSize={}", chipId, filename, fileSize);
  log.info("设备状态已下发，chipId={}, payload={}", chipId, json);  // payload 包含完整设备状态
  ```
- **说明**：日志中记录了 chipId、文件名、设备状态 JSON。当前未发现直接打印 token 或密码。但设备状态消息记录了完整 payload（包括亮度、色温等信息），生产环境应降低此类日志级别到 DEBUG。
- **建议优先级**：后续加固（P2）

### L-8 默认配置包含明文凭据
- **位置**：`application.yaml:16, 26`
- **说明**：
  ```yaml
  password: ${MYSQL_PASSWORD:SmartLight@123456}
  secret: ${JWT_SECRET:smart-light-secret-2026}
  ```
  虽然使用了 Spring 占位符语法支持环境变量覆盖，但如果部署时忘记设置环境变量，将使用硬编码的默认值。建议生产环境的配置文件 (`application-prod.yaml`) 不提供默认值，强制通过环境变量设置。
- **建议优先级**：后续加固（P2）

### L-9 Swagger UI 暴露
- **位置**：`SecurityConfig.java:42-46`
- **说明**：Swagger UI 路径需要认证后才能访问（`/swagger-ui/**` 被 `.anyRequest().authenticated()` 覆盖），但 `/v3/api-docs` 被 permitAll。建议生产环境完全禁用 Swagger UI。
- **建议优先级**：后续加固（P2）

### L-3 密码存储正确
- **位置**：`AuthServiceImpl.java:58`
- **说明**：✅ 密码使用 `BCryptPasswordEncoder` 进行哈希存储，没有明文存储或比较。`passwordEncoder.matches()` 用于登录验证，实现安全。

### L-5 SQL 注入防护有效
- **位置**：全局
- **说明**：✅ 所有数据库查询使用 MyBatis-Plus `LambdaQueryWrapper`（类型安全）或 `@Insert` 注解中的 `#{}` 参数化查询。无 MyBatis XML 文件，无 `${}` 用法。`.last()` 仅用于常量字符串（如 `"limit 1"`），无用户输入拼接。**未发现 SQL 注入风险**。

### L-6 路径穿越防护有效
- **位置**：`OtaFirmwareDownloadController.java:36-38`, `AiController.java:154-167`
- **说明**：✅ OTA 固件下载和 AI 归档删除都做了 `path.normalize()` + `path.startsWith(baseDir)` 检查，有效防止了路径穿越攻击。

---

## 6. 设备通信安全专项检查

### 6.1 当前设备通信架构

```
设备 (ESP8266)
  ├── HTTP POST /admin/device/announce        ← permitAll, 设备上线通告
  ├── HTTP POST /admin/device/state-report    ← permitAll, 状态上报
  ├── HTTP POST /admin/lux/create             ← permitAll, 光照上报
  ├── HTTP POST /admin/duration/create        ← permitAll, 停留时长上报
  ├── WebSocket /ws/device                    ← permitAll, 双向通信
  └── HTTP GET  /ota/**                       ← permitAll, 固件下载

浏览器/App
  ├── HTTP POST /api/auth/login               ← permitAll
  ├── HTTP POST /api/auth/register            ← permitAll
  ├── WebSocket /ws?token=xxx                 ← 需要认证
  └── HTTP /admin/**                          ← 需要认证
```

### 6.2 核心问题：chipId 即身份

系统设计中 chipId 是设备唯一标识，但 chipId 同时出现在：
- 前端 Vue/React 页面的设备列表
- REST API 返回的 `DeviceRespVO` 中
- WebSocket 通信的 JSON payload 中
- 设备固件代码中（可能被提取）
- URL 路径中（如 `/admin/device/arm/{chipId}`）

**chipId 是公开信息，不是秘密。** 系统没有任何第二因素来验证通信方的真实性。

### 6.3 攻击场景分析

| 攻击场景 | 可行性 | 危害 |
|---------|--------|------|
| 伪造设备 WebSocket 连接 | **高** — 仅需已知 chipId，WebSocket 无鉴权 | 接收设备控制指令、伪造在线状态 |
| 伪造设备状态上报 | **高** — HTTP POST permitAll，无签名验证 | 污染光照/停留/状态数据 |
| 伪造设备上线通告 | **高** — /announce permitAll | 欺骗前端显示虚假设备上线 |
| 重放攻击（Replay） | **中** — 无 timestamp/nonce 机制 | 重放有效的设备上报数据 |
| 设备被恶意下发控制指令 | **高** — arm/effect/locate/ota 仅校验登录但未校验设备归属 | 已认证用户可控制任何设备 |
| 设备被下发恶意固件 | **中** — 需先绕过固件上传的内容校验，然后利用归属校验缺失下发 | 设备变砖或被控制 |

### 6.4 设备通信对比业界最佳实践

| 安全措施 | 当前状态 | 业界标准 |
|---------|---------|---------|
| 设备身份认证 | ❌ 无，仅靠 chipId | 预共享密钥 / X.509 证书 / Token |
| 通信加密 | ⚠️ HTTP 明文（应使用 HTTPS） | TLS/SSL |
| 消息签名 | ❌ 无 | HMAC-SHA256 |
| 防重放 | ❌ 无 | nonce + timestamp |
| 设备白名单 | ❌ 无 | 设备激活审批 |
| 固件签名 | ❌ 无 | 固件数字签名 |
| 设备心跳认证 | ❌ 仅 WebSocket ping/pong，无认证 | 心跳需携带签名 |

---

## 7. 文件上传与 OTA 专项检查

### 7.1 文件上传接口清单

| 接口 | Controller | 认证要求 | 文件类型校验 | 大小限制 |
|------|-----------|---------|------------|---------|
| `/admin/ai/fabric-recognize` | AiController | authenticated | ❌ 仅 check empty | 20MB（全局） |
| `/admin/ai/person-detect` | AiController | authenticated | ❌ 仅 check empty | 20MB（全局） |
| `/admin/device/ota/firmware/upload` | DeviceOtaFirmwareController | authenticated | ⚠️ 仅 `.bin` 扩展名 | 20MB（全局） |

### 7.2 OTA 固件上传安全评估

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 文件扩展名限制 | ✅ `.bin` | 仅允许 `.bin` |
| MIME 类型限制 | ❌ 未校验 | 没有校验 Content-Type |
| 文件 Magic Bytes 校验 | ❌ 未校验 | 未验证是否为合法固件格式 |
| 文件大小限制 | ⚠️ 20MB（全局） | 建议为固件单独设置限制（如 4MB） |
| 文件名注入 | ✅ 安全 | 使用固定文件名 `firmware.bin` |
| 路径穿越 | ✅ 安全 | 使用 `Path.of(...)` 构造路径，无用户输入作为路径组件 |
| 文件覆盖 | ⚠️ 设计如此 | 同 deviceType/channel/versionCode 的固件会被覆盖 |
| 内容安全扫描 | ❌ 无 | 未集成 ClamAV 或其他扫描 |

### 7.3 OTA 固件下载安全评估

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 下载鉴权 | ❌ permitAll | 任何人可下载固件 |
| 路径穿越 | ✅ 安全 | `normalize()` + `startsWith()` 检查 |
| 文件存在性 | ✅ 安全 | `Files.isRegularFile(target)` 检查 |
| Content-Disposition | ✅ 安全 | 使用 `ContentDisposition.attachment()` API |
| 文件名编码 | ✅ 安全 | `StandardCharsets.UTF_8` |

### 7.4 AI 上传安全评估

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 文件类型校验 | ❌ | 无 MIME/扩展名/Magic Bytes 校验 |
| 文件大小建议 | ⚠️ | 20MB 对图片来说过大，建议 5MB |
| SSRF 风险 | ✅ 低 | AI URL 来自配置文件 `${AI_FABRIC_URL}`，非用户输入 |
| 文件内容转发 | ⚠️ | 不做检查直接转发给 AI 服务 |

---

## 8. 数据权限专项检查

### 8.1 功能与数据隔离矩阵

| 功能 | 接口 | 是否按 storeId 隔离 | 归属校验 |
|------|------|-------------------|---------|
| 创建设备 | `POST /admin/device/create` | ✅ 自动绑定当前用户店铺 | ✅ |
| 更新设备 | `PUT /admin/device/update/{id}` | ❌ 未校验 storeId | ❌ |
| 删除设备 | `DELETE /admin/device/delete/{id}` | ❌ 未校验 storeId | ❌ |
| 查询单个设备 | `GET /admin/device/get/{id}` | ❌ 未校验 storeId | ❌ |
| 查询设备列表 | `GET /admin/device/list` | ❌ 全量返回 | ❌ |
| 按 chipId 查设备 | `GET /admin/device/by-chip-id` | ❌ 未校验 storeId | ❌ |
| 查询我的设备 | `GET /admin/device/my-list` | ✅ 按当前用户店铺 | ✅ |
| 绑定设备 | `POST /admin/device/bind-current-store` | ✅ 绑定到当前店铺 | ✅ |
| 设备定位 | `POST /admin/device/locate/{chipId}` | ❌ 未校验 storeId | ❌ |
| 灯光效果 | `POST /admin/device/effect/{chipId}` | ❌ 未校验 storeId | ❌ |
| 机械臂控制 | `POST /admin/device/arm/{chipId}` | ❌ 仅查设备是否存在 | ❌ |
| OTA 检查 | `GET /admin/device/{chipId}/ota/check` | ❌ 未校验 storeId | ❌ |
| OTA 下发 | `POST /admin/device/{chipId}/ota/update` | ❌ 未校验 storeId | ❌ |
| 状态同步 | `POST /admin/device/state-sync/{chipId}` | ❌ 未校验 storeId | ❌ |
| 光照上报 | `POST /admin/lux/create` | ✅ 自动关联设备 storeId | —（permitAll） |
| 光照查询 | `GET /admin/lux/{get-latest,list}` | ✅ 校验 storeId | ✅ |
| 多设备光照 | `GET /admin/lux/multi-trend` | ✅ 按当前用户店铺 | ✅ |
| 停留上报 | `POST /admin/duration/create` | ✅ 自动关联设备 storeId | —（permitAll） |
| 停留查询 | `GET /admin/duration/{get,list,range,sum}` | ❌ 仅按 chipId，无 storeId | ❌ |
| 停留汇总 | `GET /admin/duration/summary` | ❌ 全量汇总，无 storeId | ❌ |
| 设备在线状态 | `GET /admin/device/online-status/{chipId}` | ❌ 未校验 storeId | ❌ |
| 在线设备列表 | `GET /admin/device/online-list` | ❌ 全量返回 | ❌ |
| 天气查询 | `GET /admin/weather/current?storeId=...` | ❌ 可传任意 storeId | ❌ |
| 天气采集 | `POST /admin/weather/collect/{storeId}` | ❌ 可传任意 storeId | ❌ |
| 分析趋势 | `GET /admin/analytics/temp-people-trend` | ⚠️ 按查询到的设备 storeId | ⚠️ |
| 店铺管理 | `/api/store/*` | ✅ 按 SecurityUtils 获取当前用户 | ✅ |
| AI 归档浏览 | `GET /admin/ai/fabric-archive` | ❌ 全量（且 permitAll） | ❌ |
| AI 归档删除 | `DELETE /admin/ai/fabric-archive` | ❌ 全量（且 permitAll） | ❌ |

### 8.2 小结

- **18 个接口缺乏 storeId 隔离**，其中 2 个还是 permitAll
- `LuxService` 是唯一**正确实现了** storeId 查询过滤的服务（`LuxServiceImpl.java:60-121`），可作为参考实现
- 建议将 storeId 过滤逻辑抽取为 AOP 切面或公共方法

---

## 9. SQL 注入专项检查

### 9.1 检查结果

| 检查项 | 状态 | 详情 |
|--------|------|------|
| MyBatis XML mapper 文件 | ✅ 不存在 | 项目无任何 XML mapper 文件 |
| `${}` 字符串替换 | ✅ 不存在 | 全局搜索 `${` 未在使用 MyBatis 的 SQL 上下文中发现 |
| `@Insert` / `@Select` 注解 | ✅ 安全 | 全部使用 `#{}` 参数化查询 |
| LambdaQueryWrapper | ✅ 类型安全 | 所有动态查询使用 Lambda 表达式，编译期类型检查 |
| `.last()` 拼接 | ✅ 常量 | 所有 `.last()` 参数为硬编码字符串，如 `"limit 1"` |
| `orderBy` 动态字段 | ✅ 不存在 | 未发现从请求参数动态拼接 `orderBy` 字段名的代码 |
| `groupBy` 动态字段 | ✅ 不存在 | 未发现动态 `groupBy` |
| 字符串拼接 SQL | ✅ 不存在 | 未发现使用 `+` 或 `StringBuilder` 拼接 SQL 语句 |

### 9.2 结论

**未发现 SQL 注入风险。** 项目严格使用 MyBatis-Plus 的类型安全 API 和参数化查询，没有引入 SQL 注入向量。

---

## 10. WebSocket 专项检查

### 10.1 浏览器 WebSocket (`/ws`)

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 连接鉴权 | ✅ JWT | 通过 URL query param `?token=xxx` 传递 JWT |
| Token 传输安全 | ⚠️ URL 参数 | token 可能被日志记录 |
| CORS 限制 | ❌ `*` | 允许任意来源连接 |
| 用户绑定 | ❌ 无 | Session 未关联 userId/storeId |
| 广播范围 | ❌ 全局 | 所有连接收到所有推送消息 |
| 消息类型校验 | ⚠️ 部分 | 仅处理 `ping` 类型，忽略其他 |
| 消息大小限制 | ❌ 无 | 未限制单条消息大小 |
| 连接数量限制 | ❌ 无 | 无连接上限 |
| 心跳超时 | ❌ 无 | 无服务端主动超时断开 |

### 10.2 设备 WebSocket (`/ws/device`)

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 连接鉴权 | ❌ 无 | 完全无鉴权 |
| CORS 限制 | ❌ `*` | 允许任意来源 |
| chipId 绑定 | ⚠️ 声明式 | 设备发送 `{"type":"register","chipId":"xxx"}` 即注册，未验证 |
| 消息类型处理 | ✅ register/ping | 处理 `register` 和 `ping` 两种类型 |
| 固件信息同步 | ⚠️ | 注册时从消息体提取 fwVersion/fwVersionCode/channel/otaStatus 直接写入数据库 |
| 旧连接清理 | ✅ | 同一 chipId 新连接会关闭旧连接 |
| 连接超时 | ✅ 15 秒 | `ONLINE_TIMEOUT_MS = 15_000L`，基于 `lastSeen` 判断 |
| 消息大小限制 | ❌ 无 | 未限制 |
| 最大连接数 | ❌ 无 | 无限制 |

### 10.3 广播推送范围

`WebSocketPushService` 中的 `broadcast()` 方法（`WebSocketPushService.java:132-139`）向**所有浏览器 WebSocket 连接**推送以下类型消息：

- `state` — 设备状态更新
- `onlineStatus` — 设备在线状态变更
- `deviceDeleted` — 设备删除通知
- `lux` — 光照记录
- `durationUpdate` — 停留时长更新
- `fabricRecognize` — AI 面料识别结果
- `personDetection` — AI 人体检测结果
- `announce` — 设备上线通告
- `lightEffectState` — 灯光效果状态

**所有这些消息都跨店铺广播，用户 A 可以接收到用户 B 的业务数据。**

---

## 11. 建议修复顺序

### 第一阶段：立即修复（P0）

| 序号 | 问题编号 | 修复内容 | 影响范围 |
|------|---------|---------|---------|
| 1 | H-6 | 引入设备密钥/签名机制 | 所有设备通信 |
| 2 | H-1 | `/ws/device` 增加设备认证 | 设备 WebSocket |
| 3 | H-2 | 设备上报接口增加签名验证 | HTTP 设备上报 |
| 4 | H-3 | 所有 chipId 操作增加 storeId 归属校验 | 设备控制接口 |
| 5 | H-4 | WebSocket 广播改为按 storeId 分组推送 | 浏览器 WebSocket |
| 6 | H-5 | 修复所有缺少 storeId 过滤的查询 | 设备/Duration/Weather/Online 查询 |
| 7 | H-7 | AI 归档接口增加认证和归属校验 | AI 归档 |

### 第二阶段：近期修复（P1）

| 序号 | 问题编号 | 修复内容 |
|------|---------|---------|
| 8 | H-9 | 生产环境 JWT secret 改为强随机密钥，缩短有效期 |
| 9 | H-10 | 登录接口增加暴力破解防护（失败计数+锁定） |
| 10 | H-11 | 注册接口增加验证码和速率限制 |
| 11 | H-12 | 固件上传增加 Magic Bytes 和格式校验 |
| 12 | H-13 | 固件 URL 改为配置驱动，不依赖 Host 头 |
| 13 | H-14 | 服务端自动计算固件 SHA256 |
| 14 | H-15 | AI 上传增加图片类型校验 |
| 15 | H-16 | WebSocket token 从 URL 参数迁移到首条消息 |
| 16 | H-17 | 收紧 WebSocket CORS 配置 |
| 17 | H-18 | 补充设备 CRUD 的所有归属校验 |
| 18 | H-19 | 设备在线列表增加 storeId 过滤 |

### 第三阶段：后续加固（P2）

| 序号 | 问题编号 | 修复内容 |
|------|---------|---------|
| 19 | L-1 | 定期检查依赖更新 |
| 20 | L-8 | 生产环境移除默认凭据 |
| 21 | L-9 | 生产环境禁用 Swagger |
| 22 | L-4 | 降低生产环境日志级别 |
| 23 | — | 引入 HTTPS/TLS |
| 24 | — | 引入 API 版本化 |
| 25 | — | 增加 WebSocket 连接数限制和消息大小限制 |

---

## 12. 结论

### 整体安全水平评估

当前后端项目的**整体安全水平为"中高风险"**。存在以下结构性问题：

1. **设备身份认证机制完全缺失**：chipId 作为设备唯一标识但无配套的密钥/签名/证书机制，这是整个设备通信安全的基石缺陷。

2. **权限模型不完整**：Spring Security 认证框架已正确集成，但 Service 层的数据隔离校验严重不足。大量接口仅验证"用户已登录"而未验证"用户有权访问该资源"，存在广泛的水平越权（IDOR）风险。

3. **WebSocket 广播无隔离**：所有浏览器客户端接收全局广播，跨店铺数据泄露问题严重影响多租户场景。

4. **优点**：密码存储正确（BCrypt）、SQL 注入防护到位（LambdaQueryWrapper + `#{}`）、路径穿越防护有效、异常处理不泄露堆栈。

### 最需要优先修复的 3 个问题

1. **H-6 设备身份认证** — 这是所有设备通信安全的基础，修复后可连带解决 H-1（设备 WebSocket）和 H-2（设备上报）的大部分风险。
2. **H-3 + H-5 数据隔离和归属校验** — 在 Service 层增加 storeId 过滤和归属校验，覆盖设备 CRUD、设备控制、Duration 查询、Weather 查询等所有接口。
3. **H-4 WebSocket 广播隔离** — 将 WebSocket 推送从全局广播改为按 storeId 分组推送，防止跨店铺数据泄露。

---

> **审计声明**：本次审计仅通过代码静态分析完成（人工审查约 100 个源文件），未执行渗透测试或动态扫描。建议在修复 P0 问题后安排一次渗透测试以验证修复效果。本次审计未修改任何业务代码、配置或数据。
