# 下次继续任务清单

> 更新：2026-05-12

## 优先级 P0：后端 storeId 隔离回归验证

- [ ] **Announce 新设备扫描验证**：未绑定设备的 announce 是否正常推送 `broadcastAll`，Web/uniapp 前端能否扫描到新设备
- [ ] **AI 识别 chipId 归属校验确认**：传入他人店铺的 chipId 时，`fabricRecognize` 是否拒绝写入、`personDetect` 是否静默跳过
- [ ] **WebSocket storeId 绑定稳定性**：浏览器 `/ws?token=xxx` 连接后，`SecurityContextHolder` 是否能稳定获取 LoginUser 并绑定 storeId
- [ ] **跨店铺设备控制验证**：用户 A 调用 arm/effect/locate/ota 操作用户 B 的设备，是否返回"无权操作该设备"

## 优先级 P0：数据查询回归验证

- [ ] 设备列表只返回当前店铺设备
- [ ] 通过 id 或 chipId 查其他店铺设备返回"无权操作"或"设备不存在"
- [ ] 修改/删除其他店铺设备被拒绝
- [ ] 停留时长查询/汇总只返回当前店铺数据
- [ ] 设备在线列表只显示当前店铺设备
- [ ] 光照趋势（multiTrend）只返回当前店铺数据
- [ ] 分析看板（temp-people-trend）不泄露其他店铺数据

## 优先级 P1：WebSocket 推送验证

- [ ] A 店铺浏览器只收到 A 店铺设备的状态推送
- [ ] A 店铺浏览器只收到 A 店铺的 lux/duration 推送
- [ ] A 店铺浏览器只收到 A 店铺的 AI 识别结果推送
- [ ] A 店铺浏览器只收到 A 店铺设备的上线/下线推送
- [ ] LightEffect 状态广播正常（全局）
- [ ] 未绑定设备的 announce 全局广播正常

## 优先级 P2：PPT 和文档

- [ ] PPT 优化版继续补图和截图
- [ ] 补充安全审计后的架构图

## 优先级 P3：uniapp

- [ ] uniapp 深浅主题真机检查
- [ ] 微信小程序兼容性验证

## 后续安全加固（不限本次）

- [ ] H-6 设备身份认证（deviceSecret + HMAC，需同步修改固件）
- [ ] H-9 JWT secret 改为环境变量强随机密钥
- [ ] H-10 登录失败计数和临时锁定
- [ ] H-11 注册接口验证码/速率限制
- [ ] H-12 固件上传 Magic Bytes 校验
- [ ] H-16 WebSocket token 改为首条消息传递
- [ ] H-17 WebSocket CORS 收紧
