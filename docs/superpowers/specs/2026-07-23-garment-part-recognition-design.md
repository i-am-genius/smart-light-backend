# 服装分区、面料主色识别与动态陈列模型设计

## 背景

现有面料识别链路如下：

```text
Web 上传图片
  -> POST /admin/ai/fabric-recognize
  -> Spring Boot 校验文件与设备归属
  -> Python /predict
  -> SegFormer 合并所有衣物类别为一个 mask
  -> ViT 对整块衣物做一次面料三分类
  -> Java 对整块透明 PNG 做一次主色提取
  -> 保存 device.fabric / main_color_rgb / recommended_*
  -> REST 与 WebSocket 返回单个面料、主色和灯光建议
```

Python 当前使用的 SegFormer 已能输出以下语义类别：

- `4 = Upper-clothes`
- `5 = Skirt`
- `6 = Pants`
- `7 = Dress`

但现有实现先将这些类别合并为一个布尔 mask，再保留最大连通区域，导致上装、裤子、裙子和连衣裙的类别边界在进入 Java 后端前已经丢失。Web 设备卡片只能显示一个面料和一个 RGB 色块，店铺 Three.js 布局也固定创建同一种上衣模型。

## 已确认需求

1. 服装类别只使用现有 SegFormer 可提供的四类：上装、裤子、裙子、连衣裙。
2. 上装和下装同时存在时，分别识别面料、面料置信度和主色。
3. 只有上装或只有下装时，只返回和展示实际存在的单件。
4. 连衣裙作为一件 `fullBody` 服装，不人为拆分。
5. 未检测到有效服装区域时，不返回空结果；将整张图片作为上装兜底，继续面料、主色、灯光、持久化和推送流程。
6. 设备卡片采用已确认的“A 方案”：双行明细加共享双色条。上下装同时存在时，色条左右各占一半；单件时使用整块单色。
7. 店铺灯具布局采用已确认的“A 方案”：沿用现有程序化 Three.js 风格，新增裤子、裙子和连衣裙几何体；单件独立展示，上下装在同一展示架组合。
8. 最新结构化识别结果持久化到 `device` 表，刷新页面或重新登录后仍可恢复设备卡片和 3D 模型。
9. 一盏灯最终只有一组推荐亮度和色温；上下装同时存在时，按有效 mask 面积加权合成。
10. 旧字段、旧 Web 调用、小程序、设备 WebSocket 和固件协议保持兼容。

## 目标

- Python 保留并输出四类衣物的语义结果。
- 对每件最终服装独立执行现有 ViT 面料三分类。
- Java 对每件服装独立提取主色并计算灯光建议。
- 结构化结果通过 REST、浏览器 WebSocket 和设备列表接口一致可用。
- 最新结构化结果持久化到服务器 MySQL。
- Web 设备卡片按单件或上下装组合显示类别、面料、置信度和主色。
- Three.js 店铺布局根据类别创建并更新对应服装模型。
- 不降低现有店铺隔离、上传安全和错误处理基线。

## 非目标

- 不新增 T 恤、衬衫、卫衣、夹克、牛仔裤等细粒度品类模型。
- 不重新训练 SegFormer 或 ViT。
- 不增加面料类别；仍为 `cashmere wool`、`cotton`、`polyester`。
- 不保存服装识别历史记录，只保存设备当前最新结果。
- 不修改小程序页面。
- 不修改 `/ws/device`、ESP8266/ESP32 固件或设备上报协议。
- 不在本次功能中修复现有 `duration_record.duration_value DECIMAL(10,2)` 与 Java `Long` 的历史类型差异。
- 不在本次功能中补建整个项目的数据库基线，只新增本功能所需的幂等迁移。

## 服务器数据库核对结论

已只读查询服务器 `47.250.166.127` 上 MySQL 8.0.45 的 `information_schema`：

- 服务器共有 8 张业务表，与本地 8 个 MyBatis-Plus DO 一一对应。
- 本地 DO 映射的所有现有字段均已存在于服务器。
- `last_seen_at`、`self_test_json`、`self_test_time`、`person_flow_record` 和天气相关迁移均已反映在服务器结构中。
- 服务器当前没有 `garment_result_json`。
- `duration_record.duration_value` 在服务器为 `DECIMAL(10,2)`，Java 为 `Long`；当前统计 SQL 显式转整数，本功能不修改该字段。

本机 MySQL 服务虽然运行，但仓库中的数据库密码为占位值，无法对本机实际数据库做登录核对。本设计所称“本地结构”是仓库中的 Java 实体和 SQL 脚本。

## 总体架构

```text
上传图片
  -> Java 校验文件、当前用户和 chipId 所属店铺
  -> Python SegFormer 一次推理，保留类别概率与 argmax 结果
  -> Python 为上装、裤子、裙子、连衣裙分别构建候选 mask
  -> Python 归一化为最终单件、上下装或连衣裙
  -> Python 对每个最终分区分别执行 ViT 面料分类
  -> Python 返回结构化 garments 和内部透明色彩样本
  -> Java 对每个色彩样本复用 MainColorService
  -> Java 分别应用面料灯光修正
  -> Java 按 mask 面积合成设备最终推荐亮度与色温
  -> Java 一次更新旧字段和 garment_result_json
  -> Java 返回 REST，并向同店铺 Web 会话推送结构化结果
  -> Web 更新设备卡片和 Three.js 服装展示
```

职责边界：

- Python 负责图像语义、mask、候选冲突消解、服装类别和面料推理。
- Java 负责权限、文件安全、主色算法、灯光业务规则、持久化和店铺隔离推送。
- Web 只负责解析、状态合并和视觉呈现，不自行推断服装类别。

## Python 分区识别设计

### 类别映射

仅处理四个 SegFormer 标签：

| SegFormer 标签 | 对外 `position` | 对外 `category` |
|---|---|---|
| `Upper-clothes` | `upper` | `upper` |
| `Pants` | `lower` | `pants` |
| `Skirt` | `lower` | `skirt` |
| `Dress` | `fullBody` | `dress` |

`Scarf` 和其他人体解析标签不进入本次结果。

### 单次分割与候选生成

SegFormer 每张图片只执行一次推理。保留：

- 上采样后的类别 logits；
- 每个像素的 argmax 类别；
- softmax 后的类别概率。

每个目标类别独立执行：

1. 从 argmax 结果生成该类别 mask。
2. 保留该类别最大连通区域。
3. 执行现有闭运算和开运算。
4. 计算有效像素数、占整图比例、边界框和该 mask 内平均类别置信度。
5. 有效像素必须同时满足：
   - 不少于 `SMARTLIGHT_MIN_GARMENT_PIXELS`，默认 100；
   - 不少于整图面积的 `SMARTLIGHT_MIN_GARMENT_RATIO`，默认 0.001；
   - 平均类别置信度不少于 `SMARTLIGHT_MIN_CATEGORY_CONFIDENCE`，默认 0.35。

阈值通过环境变量配置，避免为调整稳定性反复修改代码。

### 候选冲突消解

每个候选的证据分数为：

```text
evidence = maskArea × categoryConfidence
```

规则：

1. `pants` 与 `skirt` 同时有效时，只保留证据分数更高者作为下装。
2. 分体装证据为有效上装和有效下装证据之和。
3. `dress` 与分体装同时有效时，比较 `dress` 证据和分体装证据：
   - `dress` 更高：只返回连衣裙；
   - 分体装更高或相等：返回有效上装和/或有效下装。
4. 最终 `garments` 最多包含两项。

### 无有效分割时的上装兜底

如果四类候选均无效：

- `clothDetected=false`；
- `segmentationFallback=true`；
- `outfitType=upper_only`；
- 使用整张缩放后的输入图作为上装裁剪；
- 使用全不透明 alpha 作为色彩样本；
- 继续执行 ViT 面料分类；
- `position=upper`、`category=upper`；
- `categoryConfidence=null`；
- `maskArea` 使用整图像素数。

该兜底结果被视为一次有效识别，会继续更新数据库、WebSocket、设备卡片和 3D 上装模型。

### 分区面料推理

每个最终服装区域：

1. 按该 mask 的边界框增加现有 4% padding。
2. mask 外像素设为黑色，与现有 ViT v2 训练预处理保持一致。
3. 缩放到 224×224，执行现有 OpenVINO ViT。
4. 返回三分类中最高概率标签与真实概率。

不因面料置信度偏低而改写成其他标签或 `unknown`；前端显示真实置信度。

### 内部色彩样本

为每件服装生成透明背景 PNG，最长边限制为 512 像素，用于 Java 主色提取。字段只用于 Python 到 Java 的内部调用：

```json
{
  "colorSamplePngBase64": "..."
}
```

Java 完成主色提取后必须清空该字段，不能写入数据库、REST 响应或 WebSocket。

### 标注图

标注图使用不同颜色区分类别，并标注类别与面料：

- 上装：绿色；
- 裤子：蓝色；
- 裙子：橙色；
- 连衣裙：紫色；
- 整图上装兜底：黄色边框并标注 `upper fallback`。

现有原图、标注图、拼接图留档和二进制分割图推送机制保持不变。

### 模型文件约束

当前本地脚本引用 `vit_clothes_v2.xml`，但本地目录只观察到旧的 `vit_clothes.xml/.bin`。实现不得静默切换到旧模型。启动时若配置的 v2 OpenVINO XML/BIN 不存在，应明确失败并记录缺失路径；部署时必须提供与当前生产版本一致的 v2 模型文件。

## 数据契约

### 服装项

新增 `GarmentPartRespVO`：

```json
{
  "position": "upper",
  "category": "upper",
  "categoryConfidence": 0.91,
  "fabric": "polyester",
  "fabricConfidence": 0.86,
  "mainColorRgb": "213,215,217",
  "maskArea": 183420,
  "x": 120,
  "y": 80,
  "w": 360,
  "h": 260
}
```

约束：

- `position` 只允许 `upper`、`lower`、`fullBody`。
- `category` 只允许 `upper`、`pants`、`skirt`、`dress`。
- 正常分割的 `categoryConfidence` 范围为 0–1；整图上装兜底为 `null`。
- `fabricConfidence` 为现有 ViT softmax 最大概率。
- 坐标基于 Python 最终缩放后的输入图，与标注图一致。

### 识别响应

`FabricRecognizeRespVO` 增加：

```json
{
  "resultVersion": 1,
  "clothDetected": true,
  "segmentationFallback": false,
  "outfitType": "separates",
  "garments": [
    {
      "position": "upper",
      "category": "upper",
      "fabric": "polyester",
      "fabricConfidence": 0.86,
      "mainColorRgb": "213,215,217",
      "maskArea": 183420
    },
    {
      "position": "lower",
      "category": "pants",
      "fabric": "cotton",
      "fabricConfidence": 0.79,
      "mainColorRgb": "43,55,72",
      "maskArea": 206310
    }
  ],
  "recommendedBrightness": 74,
  "recommendedTemp": 4360
}
```

`outfitType` 只允许：

- `upper_only`
- `lower_only`
- `separates`
- `dress`

### 旧字段兼容

继续返回并保存：

- `label`
- `confidence`
- `mainColorRgb`
- `recommendedBrightness`
- `recommendedTemp`
- `clothX/Y/W/H`

旧字段映射：

- 主服装为 `maskArea` 最大的服装；
- `label` 和 `confidence` 取主服装面料结果；
- `mainColorRgb` 取主服装主色；
- `clothX/Y/W/H` 取所有最终服装 mask 的联合边界框；
- 推荐亮度和色温取最终设备级合成结果。

整图上装兜底时，该上装自然成为主服装。

## Java 后端设计

### DTO 与客户端

- `FabricRecognizeRespVO` 增加结构化字段。
- 新增 `GarmentPartRespVO`。
- `FabricAiClient` 继续使用现有 multipart `/predict`，不新增接口。
- 对 Python 返回的 `position/category/outfitType` 做允许值校验。
- `garments` 为 null、超过两项、重复位置或包含非法类别时，视为 AI 响应非法，本次识别失败。

### 逐件主色

`AiServiceImpl` 遍历 `garments`：

1. 解码每项内部 `colorSamplePngBase64`。
2. 调用现有 `MainColorService.extract`。
3. 将 `mainColorRgb` 写回对应服装项。
4. 使用该项面料调用现有 `applyFabricAdjustment`。
5. 保存该项临时灯光建议供设备级合成。
6. 清空 Base64。

如果单件主色提取异常，沿用现有灰色默认值 `128,128,128` 和对应默认灯光建议，不让单个颜色异常破坏整个识别流程。

### 灯光合成

单件服装直接使用该项建议。多件服装按 `maskArea` 加权：

```text
recommendedBrightness =
  round(sum(partBrightness × maskArea) / sum(maskArea))

recommendedTemp =
  round(sum(partTemp × maskArea) / sum(maskArea))
```

最终范围继续限制为：

- 亮度 30–95；
- 色温 2700–6500 K。

不将混合 RGB 保存为设备主色；旧 `mainColorRgb` 始终取主服装，避免产生不存在于实物上的合成颜色。

### 持久化

`device` 新增：

```sql
garment_result_json JSON NULL
```

JSON 内容包含：

- `resultVersion`
- `clothDetected`
- `segmentationFallback`
- `outfitType`
- `recognizedAt`
- 已清除内部 Base64 的 `garments`

不保存标注图 Base64、内部色彩样本或原始图片。

新增 `GarmentResultCodec` 统一负责 JSON 序列化和反序列化。序列化成功后，使用一次 `deviceMapper.updateById` 同时更新：

- `fabric`
- `main_color_rgb`
- `recommended_brightness`
- `recommended_temp`
- `garment_result_json`
- `update_time`

避免旧字段和新 JSON 处于不同识别批次。

设备查询时，后端将 JSON 解析为结构化响应字段；JSON 为 null 或解析失败时使用旧字段构造单上装兼容结果，并记录不包含 JSON 内容的告警。

### SQL 迁移

新增：

```text
src/main/resources/sql/device_garment_result_schema.sql
```

脚本使用 `information_schema.columns + PREPARE`，保持幂等：

```sql
ALTER TABLE device
  ADD COLUMN garment_result_json JSON NULL
  COMMENT 'Latest structured garment recognition result'
  AFTER main_color_rgb
```

本地只补迁移文件，不在开发过程中自动连接或修改服务器。部署时先人工执行迁移，再部署读取该字段的新后端。

### WebSocket

`fabricRecognize` 消息新增：

- `resultVersion`
- `segmentationFallback`
- `outfitType`
- `garments`

旧字段继续存在。广播仍使用后端已经验证的 `deviceStoreId`，不接受客户端提供的 `storeId`。

向设备 WebSocket 推送的 `state` 消息保持现有字段，不发送 `garments`，避免修改固件协议。

## Web 前端设计

修改范围为 `E:\smart-light-front`，不修改小程序。

### 类型与状态归一化

新增 TypeScript 类型：

```ts
type GarmentPosition = 'upper' | 'lower' | 'fullBody'
type GarmentCategory = 'upper' | 'pants' | 'skirt' | 'dress'
type OutfitType = 'upper_only' | 'lower_only' | 'separates' | 'dress'

interface GarmentPart {
  position: GarmentPosition
  category: GarmentCategory
  categoryConfidence?: number | null
  fabric: string
  fabricConfidence: number
  mainColorRgb: string
  maskArea: number
}
```

`FabricRecognizeRespVO`、`DeviceItem` 和实时消息归一化逻辑增加结构化字段。REST 上传响应、`fabricRecognize` WebSocket 和设备列表刷新都必须更新同一份设备状态。

当服务端没有结构化字段时，使用旧 `fabric/mainColorRgb` 构造单上装兼容视图。

### 设备卡片

使用已确认的双行明细加共享色条：

- 上装显示“上装”。
- 裤子显示“裤子”。
- 裙子显示“裙子”。
- 连衣裙显示“连衣裙”。
- 每行显示面料和面料置信度。
- 上下装同时存在：色条左右各 50%，左侧上装、右侧下装，并在各半区域显示 RGB。
- 单件：色条占满宽度。
- 文字颜色分别按每个色块亮度计算，确保可读。
- `segmentationFallback=true` 不在主卡片制造错误状态；只在“查看分割图”弹窗中提示“未检测到明确区域，已按上装整图识别”。

AI 推荐理由对上下装分别描述面料与主色，再说明最终灯光是按服装区域加权得到。旧结果继续使用现有单面料说明。

### Three.js 店铺布局

将当前固定 `shirt` 对象重构为 `garmentDisplay: THREE.Group`。

新增程序化模型工厂：

- `createUpperGarment(color)`
- `createPantsGarment(color)`
- `createSkirtGarment(color)`
- `createDressGarment(color)`
- `createGarmentDisplay(garments)`

展示规则：

| 识别结果 | 3D 展示 |
|---|---|
| 仅上装 | 单独上衣，沿用现有衣架高度 |
| 仅裤子 | 单独裤子，使用裤夹横杆并居中于光束 |
| 仅裙子 | 单独裙子，使用裤夹横杆并居中于光束 |
| 上装 + 裤子 | 同一展示架上下组合，分别使用各自主色 |
| 上装 + 裙子 | 同一展示架上下组合，分别使用各自主色 |
| 连衣裙 | 单独连衣裙 |
| 无结构化旧数据 | 继续显示现有单上衣模型 |
| 分割兜底 | 单独上衣模型 |

模型材质继续复用 `createFabricMaterial`，保持粗糙度、阴影和店铺场景风格一致，不引入 GLB 或外部模型文件。

当类别或组合变化时，释放旧 `garmentDisplay` 的非共享 geometry/material，再创建新组合；仅颜色变化时更新现有材质，避免每次设备状态更新都重建几何体。模型签名包含类别组合，颜色不进入签名。

## 错误处理

- Python 无法加载 SegFormer 或 ViT：健康检查失败，`/predict` 返回受控 500；Java 不更新设备。
- Python 超时或连接失败：沿用现有 `ServiceException`，不更新数据库、不推送新识别状态。
- Python 返回非法结构：Java 拒绝本次结果，不尝试猜测修复。
- 四类均未得到有效 mask：按整图上装兜底，属于成功流程。
- 单件颜色提取失败：使用现有灰色默认结果，其他服装仍正常处理。
- JSON 序列化失败：不执行设备更新；本次接口失败。
- 数据库更新失败：不发送成功 WebSocket 结果。
- Web 收到未知版本：优先使用已知旧字段，忽略无法理解的结构化字段。
- 数据库历史 JSON 损坏：记录设备 ID 和错误类型，不记录 JSON 正文；前端退回旧字段单上装。
- Three.js 创建模型异常：释放本次已创建对象，并保留或恢复旧上衣模型，不影响其他设备。

异常日志不得包含图片 Base64、原始 JSON、JWT、数据库密码或用户上传图片内容。

## 安全与兼容

- `storeId` 仍从已认证用户和设备归属关系取得，不信任请求参数或 Python 返回。
- `chipId` 权限校验继续在 Service 层执行。
- 上传格式、大小和文件魔数校验保持不变。
- Python 内部 Base64 在对外返回前清除。
- `garment_result_json` 不接受前端保存接口直接写入，只由 AI 识别服务更新。
- 设备上报接口、`/ws/device` 和设备消息字段不变。
- 小程序会忽略新增字段，并继续使用旧字段。
- 旧 Web 前端继续使用旧字段，新 Web 前端能兼容旧后端。

## 测试策略

### Python

- 上装：返回一项 `upper`。
- 裤子：返回一项 `lower/pants`。
- 裙子：返回一项 `lower/skirt`。
- 连衣裙：返回一项 `fullBody/dress`。
- 上装加裤子：返回两项并分别执行 ViT。
- 上装加裙子：返回两项并分别执行 ViT。
- 裤子与裙子冲突：保留证据更强者。
- 连衣裙与分体装冲突：只保留证据更强的一套。
- 四类都无效：返回整图上装兜底，`clothDetected=false`、`segmentationFallback=true`。
- 内部色彩样本最长边不超过 512，alpha 与 mask 一致。
- 最终结果最多两项，不返回 scarf。
- 执行 Python 语法检查；模型文件齐备的环境再执行端到端推理样例。

### Java

- Python JSON 正确反序列化为结构化 DTO。
- 非法 `position/category/outfitType`、重复位置和超过两项被拒绝。
- 每件服装分别调用主色提取与面料修正。
- 单件直接使用该项灯光建议。
- 上下装按 mask 面积正确加权并执行边界限制。
- 主服装旧字段映射正确。
- 上装兜底会持久化并覆盖旧结果。
- AI 调用、JSON 序列化或数据库更新失败时不覆盖旧数据。
- JSON 和旧字段在同一次更新中写入。
- 设备列表可以恢复结构化结果；无 JSON 和坏 JSON 使用旧字段兜底。
- `fabricRecognize` WebSocket 包含新旧字段并保持店铺隔离。
- 设备 `state` 消息不增加 garments。
- 运行相关单元测试和 `.\mvnw.cmd compile`。

### Web

- 类型检查覆盖四种类别和四种 `outfitType`。
- REST 上传响应可以更新设备结构化状态。
- `fabricRecognize` WebSocket 可以更新同一设备。
- 设备列表刷新后结构化状态仍存在。
- 上装、裤子、裙子和连衣裙单件卡片显示正确。
- 上下装卡片显示两行，双色各占 50%。
- 每个色块文字颜色独立计算。
- 上装兜底在卡片正常显示，在分割预览显示提示。
- 旧后端响应仍能显示单面料和单色块。
- Three.js 为四类创建不同几何体。
- 单件不会额外创建不存在的上装或下装。
- 上下装分别着色并组合。
- 类别变化释放旧模型，颜色变化不重建 geometry。
- 运行现有测试与 `npm run build`。

## 部署顺序

1. 备份服务器 `device` 表结构。
2. 在服务器执行幂等 `device_garment_result_schema.sql`。
3. 验证字段为 `JSON NULL`，旧后端仍正常运行。
4. 部署修改后的 Python 服务，并确认 v2 XML/BIN 文件存在。
5. 使用四类样例和无有效分割样例验证 `/predict`。
6. 部署 Spring Boot 后端。
7. 验证 REST、数据库 JSON、旧字段和 WebSocket。
8. 部署 Web 前端。
9. 验证设备卡片的单件、上下装和连衣裙状态。
10. 验证店铺布局模型、颜色、灯光和模型释放。
11. 确认小程序和设备固件通信未受影响。

## 完成标准

- 四个现有服装类别均可形成结构化识别结果。
- 上下装面料和主色独立返回、持久化和显示。
- 单件结果不会创建不存在的另一件服装。
- 无有效分割时稳定进入整图上装兜底并完成全流程。
- 设备卡片按确认的双行明细和共享色条设计呈现。
- 店铺布局按识别类别显示程序化 3D 单件或组合模型。
- 上下装灯光建议按 mask 面积加权，单件直接使用自身建议。
- 页面刷新后从设备数据恢复结构化结果。
- 新旧 REST/WebSocket 字段兼容，小程序和设备协议不变。
- SQL 迁移幂等，后端编译通过，Web 构建通过，相关测试通过。
