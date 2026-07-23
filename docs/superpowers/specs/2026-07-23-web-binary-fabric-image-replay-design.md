# Web 二进制分割图实时推送与上线重放设计

## 背景

面料识别接口当前使用 `multipart/form-data` 接收图片。AI 服务返回分割图 Base64，后端将完整标注图保存到：

```text
/opt/smartlight/uploads/fabric/annotated
```

识别完成后，后端通过 `fabricRecognize` 文本 WebSocket 消息广播识别数据和图片 URL，但没有广播完整图片。发起识别请求的 Web 页面可以从 HTTP 响应临时取得 `annotatedImageBase64`，其他在线 Web 页面无法取得完整图片；页面刷新或 WebSocket 重连后，设备卡片也无法恢复上次分割图。

## 目标

1. 仅向 `E:\smart-light-front` Web 前端推送完整分割图二进制数据。
2. 新识别完成后，向当前店铺所有支持该能力的在线 Web 会话实时推送图片。
3. Web 前端每次 WebSocket 连接或重连成功后，重放当前店铺每个灯具各自最近一张分割图。
4. 前端组装图片后自动更新对应设备卡片，并恢复“查看分割图”功能。
5. 保持小程序、设备 WebSocket、固件和图片上传接口不变。

## 非目标

- 不向 `E:\smart-light-mini` 小程序发送二进制分割图。
- 不修改 `/ws/device` 设备通信协议。
- 不修改 ESP8266/ESP32 固件。
- 不增加新的 REST 接口。
- 不增加数据库字段或数据库迁移。
- 不删除 HTTP 识别响应中的现有 Base64 和图片 URL 字段。
- 不在浏览器持久化图片 Blob；页面刷新后由 WebSocket 重放恢复。

## 方案选择

### 采用：扫描现有 annotated 留档

Web 会话声明二进制能力后，后端扫描 annotated 留档目录，根据文件名解析 `chipId`，按最后修改时间选择当前店铺每个灯具最近一张图片。

优点：

- 不需要数据库迁移。
- 后端重启后仍能恢复历史图片。
- 与现有图片删除和留档机制使用同一数据源。

### 不采用：数据库保存最新路径

查询速度更快，但需要新增字段、迁移历史数据，并处理图片被删除后路径失效的问题。

### 不采用：仅内存缓存

实现简单，但服务重启后无法重放历史结果，不满足恢复要求。

## 会话能力协商

Web 前端的 WebSocket 打开后立即发送：

```json
{
  "type": "capabilities",
  "data": {
    "fabricImageBinary": true,
    "version": 1
  }
}
```

后端在 `WebSocketSessionManager` 中按 `sessionId` 记录此能力。只有满足以下条件的会话才能收到图片二进制帧：

- 已通过 `/ws` JWT 握手认证；
- 已关联当前用户的 `storeId`；
- 已声明 `fabricImageBinary=true`；
- 协议版本为后端支持的版本。

小程序不发送该能力声明，因此继续只接收现有 JSON 消息和图片 URL。

会话关闭时必须同时清理能力记录，避免 session 状态泄漏。

## 二进制帧协议

完整图片使用应用层分块，每个 WebSocket 二进制帧最大图片负载为 256 KiB。每个分块都是自描述的，避免多个设备同时推送时依赖“上一条文本消息”关联图片。

帧结构：

```text
4 bytes   magic = "SLFI"
1 byte    protocolVersion = 1
4 bytes   headerLength，网络字节序
N bytes   UTF-8 JSON header
M bytes   图片分块
```

JSON header 字段：

```json
{
  "type": "fabricRecognizeImageChunk",
  "imageId": "lamp-001_20260723_120000_A1B2C3D4_annotated.jpg",
  "chipId": "lamp-001",
  "mimeType": "image/jpeg",
  "chunkIndex": 0,
  "totalChunks": 4,
  "totalBytes": 812345,
  "source": "live"
}
```

字段约束：

- `imageId` 使用留档文件名，在实时推送和上线重放之间保持稳定。
- `source` 为 `live` 或 `replay`，仅用于日志和诊断，不影响组装。
- `chunkIndex` 从 0 开始，必须小于 `totalChunks`。
- `totalBytes` 最大为 10 MiB，与现有 AI 图片大小上限一致。
- MIME 类型只允许 `image/jpeg`、`image/png` 和 `image/webp`。

后端在发送前验证图片文件位于 annotated 目录、为普通文件、扩展名和文件魔数合法且大小不超过 10 MiB。

## 实时推送流程

```text
AI 识别完成
  -> 保存 original / annotated / combined 留档
  -> 更新设备的面料、主色和推荐灯光参数
  -> 广播现有 fabricRecognize JSON 数据
  -> 读取本次 annotated 留档
  -> 向同店铺所有支持能力的 Web 会话发送二进制分块
  -> 清理仅供后端使用的 Base64 字段
  -> 返回现有 HTTP 响应
```

文本 `fabricRecognize` 消息保持现有字段和广播范围，确保小程序及旧版 Web 前端兼容。

如果本次 annotated 留档不存在或校验失败：

- 不发送二进制帧；
- 保留现有 `annotatedImageUrl` / `combinedImageUrl` 兜底；
- 记录不包含图片内容的告警日志；
- 不让图片推送失败影响识别接口成功响应。

## Web 前端上线重放流程

```text
Web /ws 连接成功
  -> Web 发送 capabilities
  -> 后端登记该 session 的二进制能力
  -> 异步扫描当前店铺允许访问的灯具及 annotated 留档
  -> 每个灯具选择最近一张图片
  -> 仅向刚上线的 session 依次发送图片分块
```

重放不是店铺广播，避免一个新页面上线导致其他在线页面重复接收所有历史图片。

重放只发生在：

- WebSocket 首次连接后的能力声明；
- WebSocket 断线重连后的能力声明。

灯具设备 `onlineStatus` 变化不触发分割图重放。

## 最新图片选择

`FabricArchiveService` 增加内部查询能力：

1. 从数据库查询指定 `storeId` 下的灯具 `chipId` 集合。
2. 只扫描 `/opt/smartlight/uploads/fabric/annotated`。
3. 只接受符合现有留档文件名规范的图片。
4. 按解析出的 `chipId` 过滤当前店铺设备，保证店铺隔离。
5. 每个 `chipId` 选择最后修改时间最大的文件。
6. 返回稳定排序的结果，避免重放顺序随机。

该内部查询不依赖 `SecurityUtils` 的请求线程上下文，而是显式接收握手阶段已经验证的 `storeId`。

## 后端并发与发送

历史目录扫描和图片读取不得阻塞 WebSocket 握手或设备 WebSocket 线程。重放通过有界后台执行器运行：

- 队列满时拒绝新的重放任务并记录告警，不关闭 WebSocket；
- 会话关闭后停止继续向该会话发送剩余图片；
- 每次发送前再次确认 session 仍打开且仍具备对应能力；
- `ConcurrentWebSocketSessionDecorator` 继续负责同一 session 的并发发送保护；
- 分块大小控制在现有 512 KiB 发送缓冲以内。

实时推送与重放可能同时发生。因为每个分块自带 `imageId`、`chipId` 和序号，前端可以独立组装；相同 `imageId` 完成后不重复替换。

## Web 前端处理

### WebSocket 封装

`useWebSocket.ts`：

- 创建连接后设置 `ws.binaryType = "arraybuffer"`。
- `onopen` 发送能力声明。
- 文本消息继续执行现有 JSON 解析。
- `ArrayBuffer` 消息交给独立的分割图二进制解析器。
- 解析错误只丢弃当前帧，不影响普通 JSON 消息和自动重连。

### 图片组装器

新增独立工具模块，职责为：

- 校验 magic、版本、header 长度和 JSON header；
- 按 `imageId` 保存分块；
- 接受乱序分块；
- 拒绝重复序号、越界序号和总大小不一致的数据；
- 单次传输最大 10 MiB；
- 30 秒未完成则清理；
- 完成后输出 `{chipId, imageId, mimeType, blob}`。

### 设备状态和显示

`DeviceItem` 增加仅前端使用的字段：

```ts
annotatedImageBlobUrl?: string
annotatedImageId?: string
```

组装完成后：

1. 使用 `URL.createObjectURL(blob)` 创建预览地址。
2. 根据 `chipId` 更新对应灯具的设备状态。
3. 如果旧地址是 Blob URL，先调用 `URL.revokeObjectURL`。
4. `LampDeviceCard` 优先使用 `annotatedImageBlobUrl`，其次使用本地 HTTP 响应的 Base64，最后使用 `annotatedImageUrl`。
5. 图片替换、设备删除或页面卸载时释放 Blob URL。

设备列表的全量刷新必须保留这些仅前端存在的字段；现有合并逻辑已保留服务端未返回的本地字段。

## 错误处理

- 无留档：跳过该灯具，不显示错误弹窗。
- 文件读取失败：记录 `chipId`、文件名和错误类型，不记录图片内容。
- session 已关闭：停止该 session 的重放。
- 分块缺失或超时：前端清理临时缓冲，继续使用已有图片或图片 URL。
- 协议版本不支持：后端不登记能力，前端仍可使用文本消息和 URL。
- 图片类型、大小或文件魔数非法：后端拒绝发送。
- Web 前端收到未知二进制帧：忽略并记录受限预览日志。

## 安全要求

- `storeId` 只取自已认证 WebSocket 握手属性，不取客户端消息。
- 历史图片只从当前店铺设备 `chipId` 白名单中选择。
- 所有留档路径必须规范化，并确认仍位于 annotated 根目录。
- 不在日志中输出 Base64、二进制内容、WiFi 密码、JWT 或其他敏感数据。
- 小程序和未声明能力的会话不接收二进制帧。

## 测试策略

### 后端单元测试

- 能力声明只登记当前 session，不信任客户端提供的 `storeId`。
- session 关闭后能力记录被清除。
- 二进制广播只匹配同店铺且声明能力的 session。
- 历史重放只发送给刚上线的目标 session。
- 每个灯具只选择最近一张 annotated 图片。
- 不返回其他店铺设备图片。
- 非法路径、超限文件和非法图片格式不发送。
- 大图被拆成多个不超过限定大小的分块。
- 帧头字段、网络字节序和图片字节可以正确还原。
- 无留档和单个文件失败不影响其他灯具重放。

### Web 前端测试

- 文本 JSON 消息行为保持不变。
- `ArrayBuffer` 帧可以解析和组装。
- 乱序分块可以组装。
- 重复、越界、超限和超时分块被拒绝或清理。
- 完成后正确更新对应 `chipId` 的灯具。
- 相同 `imageId` 不重复创建 Blob URL。
- 替换、删除和卸载时释放旧 Blob URL。
- 页面刷新或模拟重连后发送能力声明。

### 验证

- 后端运行 `.\mvnw.cmd compile` 和相关测试。
- Web 前端运行 `npm run build`。
- 使用两个同店铺 Web 页面验证实时广播。
- 刷新其中一个页面，确认只有新页面收到每个灯具最近一张图片的重放。
- 确认小程序连接期间不会收到二进制分割图。
- 验证无分割历史的灯具不显示“查看分割图”。

## 完成标准

- 新识别完成后，所有支持能力的同店铺 Web 页面都能显示完整分割图。
- Web 页面首次连接、刷新或断线重连后，每个灯具恢复各自最近一张分割图。
- 小程序、旧版 Web、设备 WebSocket 和固件协议不受影响。
- 图片传输不依赖 Base64 WebSocket 消息，且没有 Blob URL 或未完成分块缓存泄漏。
