# 汉韵镜 · 汉服零售 AI 导购与虚拟试衣系统 - 云杼队

## 一、项目简介

汉韵镜面向零售行业中汉服门店的导购与售前客服部门，通过 AI 导购、商品史料检索、照片换装和商家协作，帮助解决选款咨询重复、价格库存核对繁琐、线上试穿不直观及咨询记录难以追踪的问题。

项目已实现“用户提出需求 → 在线模型查询商品与史料 → 服务端校验具体规格 → 用户确认加购 → 商家跟进”的工作流，并提供独立的可视化管理后台。当前为可运行原型；合作企业、真实部门访谈和经营效果数据待补充，详见 [参赛准备说明](docs/competition-readiness.md)。

## 二、技术栈

- **前端：** Vue 3.5、TypeScript 5.8、Vite 6、Vue Router 4、Pinia 3、Axios；Three.js 负责 GLB 模型加载与交互展示。商城与管理后台使用独立入口、端口和构建产物。
- **后端：** Java 17、Spring Boot 3.3.13、Spring Web、Bean Validation、Spring JDBC；提供 REST API、会话鉴权、CSRF 校验、库存事务及异步生成任务管理。
- **数据库：** 支持 MySQL 8.0.16+，未配置外部数据库时使用文件型 H2；账号、商品、SKU、图片地址、订单、评论、史料及 AI 接待记录持久化到 SQL。
- **AI/大模型：** LangChain4j 0.35.0 接入支持工具调用的 OpenAI 兼容聊天接口；火山引擎「图片换装 V2」生成换装图片；腾讯混元生 3D Pro 生成带纹理的三维模型。
- **测试与构建：** Maven、Spring Boot Test、JUnit 5、Vue 编译器组件交互检查、TypeScript 类型检查及 Vite 构建。

## 三、快速开始

### 环境要求

| 环境 | 要求 |
|---|---|
| Java | JDK 17，配置 `JAVA_HOME`，可运行 `java` |
| Maven | 建议 3.9+，可运行 `mvn` |
| Node.js | 建议 22 LTS，包含 npm |
| 数据库 | 默认 H2，无须单独安装；使用 MySQL 时需 8.0.16+ |
| 浏览器 | 支持 WebGL 2 的现代浏览器，用于 3D 查看 |
| 可选云服务 | 聊天模型接口、火山引擎图片换装、腾讯云混元生 3D，按需配置 |

以下命令均在项目根目录执行。Windows 可使用 PowerShell，当前工作目录为 `D:\hanyunjing`；其他机器请换成自己的项目目录。

### 安装步骤

**1. 安装依赖并构建**

```powershell
cd D:\hanyunjing
New-Item -ItemType Directory -Force .local | Out-Null
npm --prefix frontend ci
mvn package
npm --prefix frontend run build
npm --prefix frontend run build:admin
```

后端产物为 `target/hanyunjing-0.0.1-SNAPSHOT.jar`；前台和后台产物分别为 `frontend/dist/`、`frontend/dist-admin/`。

**2. 选择数据库**

全新环境不配置数据库连接时，应用自动使用 `.local/database/hanyunjing` 文件型 H2 数据库，重启后保留业务数据。启动时根据 `src/main/resources/db/schema.sql` 建表并执行目录初始化。

使用 MySQL 时，先创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS hanyunjing
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

将 `docs/database.properties.example` 的内容复制到 `.local/database.properties`，填写自己的连接信息：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/hanyunjing?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
spring.datasource.username=YOUR_DATABASE_USER
spring.datasource.password=YOUR_DATABASE_PASSWORD
```

以上为本机开发连接示例。已有 `.local/database.properties` 时保留原配置；指定 MySQL 后连接失败会报错，不会自动切换到另一个数据库。表结构及关联说明见 [数据库与后台说明](docs/database-admin.md)。

**3. 配置 AI 服务**

应用从以下本地文件读取配置，修改后需要重启后端：

| 文件 | 用途 | 模板或说明 |
|---|---|---|
| `.local/agent.properties` | 导购模型地址、模型 ID、API Key | 本文第五节 |
| `.local/tryon.properties` | 火山引擎图片换装 AK/SK | [配置模板](docs/tryon.properties.example) |
| `.local/orbit.properties` | 腾讯混元 3D SecretId/SecretKey | [配置模板](docs/orbit.properties.example) |

`.local/` 已被 `.gitignore` 排除。实际密钥仅保存在后端本地文件或环境变量中；前端不配置云服务密钥。未开通 AI 服务时，可先运行商品浏览、账号、购物车、订单及后台功能；对应 AI 生成功能会提示未配置。

### 运行方式

**方式一：Windows 启动脚本**

完成构建后执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-project.ps1
```

脚本在端口空闲时启动对应服务；已有监听进程时会保留，因此修改配置后需先停止本项目原后端，再启动使配置生效。

**方式二：三个终端分别启动**

```powershell
# 终端一：后端，从项目根目录执行
java -jar target/hanyunjing-0.0.1-SNAPSHOT.jar
```

```powershell
# 终端二：商城
npm --prefix frontend run dev
```

```powershell
# 终端三：独立管理后台
npm --prefix frontend run dev:admin
```

| 服务 | 地址 |
|---|---|
| 用户商城 | <http://127.0.0.1:5173/> |
| 商家管理后台 | <http://127.0.0.1:5174/> |
| 后端 API | `http://127.0.0.1:8082/api` |

管理入口独立于游客商城，两端登录会话隔离。当前本机已配置管理员 `admin`；新数据库不会自动获得旧数据库账号。全新数据库需先在商城注册账号，再到管理端登录，使用后端生成的 `.local/admin-setup-token.txt` 一次性口令授权首个管理员；已有管理员时不会重复初始化。

首次体验可依次打开“问衣”“选购记录”“试衣镜”。完整接口说明见 [BACKEND_API.md](BACKEND_API.md)。

## 四、核心功能

下表中的 Java 文件均位于 `src/main/java/com/hanyunjing/`；省略目录的 Vue 文件与同一行中标出完整目录的同类文件位于同一目录。

| 已实现功能 | 功能说明 | 对应文件 / 模块 |
|---|---|---|
| 账号注册与登录 | 用户注册、密码哈希、会话管理、账号数据隔离、管理员权限与 CSRF 校验 | `AuthController.java`、`AuthService.java`、`AccountStore.java`、`AccountSecurityFilter.java`、`AdminAccess.java`；`frontend/src/stores/auth.ts` |
| 朝代商品目录 | 支持汉、唐、宋、元、明的服饰展示与筛选；目录提供每朝四种不同形制及关联资料，实际展示遵循商品上架状态 | `Dynasty.java`、`CoreService.java`、`CatalogueRevision.java`；`frontend/src/views/HomeView.vue`、`ProductView.vue` |
| 商品史料检索 | 商品关联文献或馆方来源，支持知识检索；区分史料依据与现代商品设计说明 | `CommerceStore.java`、`CoreService.java`；`frontend/src/components/DynastyReferences.vue`、`KnowledgePanel.vue`；`docs/product-history-mapping.md` |
| AI 导购 | 多轮理解购买需求，查询实际目录与史料，通过工具提交结构化推荐；服务端验证预算、尺码、颜色、数量和库存 | `OnlineAgent.java`、`RetailRules.java`、`RetailWorkflow.java`；`frontend/src/views/GuideView.vue` |
| 确认加购与接待留痕 | 用户确认具体 SKU 后加购，再次核验最新价格与库存；重复确认不重复加购，记录需求约束、候选、证据与执行步骤 | `RetailController.java`、`RetailWorkflow.java`；`frontend/src/components/RetailSelection.vue`、`RetailHistory.vue` |
| 商家协作处理 | 无匹配、礼仪核验或用户主动求助进入待办；商家回复后，用户在选购记录查看结果 | `RetailWorkflow.java`；`frontend/src/views/ReceptionView.vue`、`frontend/admin/AdminView.vue` |
| 尺码辅助 | 根据商品尺码表与用户选择填写的身体数据给出区间建议，使用本地规则计算 | `SizeAssistantController.java`、`CoreService.java`；`frontend/src/components/SizeAssistant.vue` |
| AI 照片换装 | 上传授权照片，结合选定汉服参考图异步生成换装结果；支持进度查询、取消和手动重试 | `TryOnService.java`、`VolcengineTryOnRenderer.java`、`VolcengineV4Signer.java`；`frontend/src/views/TryOnView.vue`、`ResultView.vue` |
| 换装照片保存 | 下载本次 PNG 结果，检查登录状态、响应类型及错误信息 | `frontend/src/components/SaveTryOnImage.vue`、`frontend/src/download.ts` |
| 3D 环绕试衣 | 将换装结果提交腾讯混元 3D，加载 GLB；支持前后左右视角、拖动旋转、缩放、全貌查看、自动旋转及模型下载 | `Tencent3dProvider.java`、`TencentV3Signer.java`、`OrbitService.java`、`OrbitController.java`、`GlbValidator.java`；`frontend/src/components/TryOnOrbit.vue`、`OrbitViewer.vue` |
| 衣囊与订单 | 购物车、订单预览与创建、库存事务、取消回补、模拟支付及商家物流登记 | `CommerceStore.java`、`CommerceController.java`；`frontend/src/views/CartView.vue`、`OrdersView.vue`、`PaymentView.vue` |
| 评论与客服 | 用户评分和评论立即公开；商家回复评论，无评论审核环节；支持客服留言与回复 | `CommerceStore.java`、`SupportTicketService.java`、`SupportController.java`；`frontend/src/components/ProductReviews.vue`、`frontend/src/views/SupportView.vue` |
| 可视化管理后台 | 商品样式、图片上传、SKU 价格与库存、史料、用户、订单发货、评论回复、客服、AI 接待与操作记录管理；删除仅限草稿和已下架商品并二次确认 | `CommerceController.java`、`CommerceStore.java`；`frontend/admin/AdminView.vue`、`frontend/vite.admin.config.ts` |
| 执行记录与评测 | 展示模型调用和工具执行记录；保存实际返回的 Token 用量；独立评测记录不计入用户接待统计，支持脱敏导出 | `TraceBus.java`、`RetailWorkflow.java`；`frontend/src/components/TracePanel.vue`、`RetailHistory.vue`；`scripts/evaluate-retail.mjs` |

数据库通过独立实体、关联表、外键及唯一约束组织账号、商品、图片、SKU 和史料等数据。图片文件存于静态资源目录或 `.local/media/`，SQL 保存图片地址；订单成交信息及接待证据保留当时快照。建表文件为 `src/main/resources/db/schema.sql`。

当前支付为模拟支付，不发生真实扣款；物流功能为商家录入物流信息。人像、换装结果及关联 3D 模型在内存临时保存，最长为上传后的 30 分钟，重启会清除；下载到用户电脑的文件不受本站缓存到期影响。

## 五、大模型使用说明

### 调用的模型与服务

| 模型 / AI 服务 | 用途 | 调用方式 |
|---|---|---|
| 导购聊天模型：当前配置 ID 为 `gpt-6.1-sol`，可按供应商支持情况替换 | 需求理解、多轮导购、调用工具并提交商品选择 | 后端 `OnlineAgent.java` 通过 LangChain4j `OpenAiChatModel` 调用配置的 OpenAI 兼容聊天接口，使用工具调用协议 |
| 火山引擎图片换装 V2：`dressing_diffusionV2` | 根据人像和服装参考图生成换装照片 | 后端向 `https://visual.volcengineapi.com` 发送 AK/SK 签名请求，`CVSubmitTask` 提交任务，`CVGetResult` 查询同一任务；接口版本 `2022-08-31` |
| 腾讯混元生 3D Pro：默认 `3.0`，代码支持 `3.1` | 根据换装图片生成带纹理 GLB 模型 | 后端向 `https://ai3d.tencentcloudapi.com` 发送 TC3-HMAC-SHA256 签名请求，调用 `SubmitHunyuanTo3DProJob` 与 `QueryHunyuanTo3DProJob`；接口版本 `2025-05-13` |

聊天模型名称是发送给所配置服务的模型 ID，以供应商实际提供的能力为准。项目不在本地训练或部署模型。商品展示图片是静态资产，浏览商品不会调用图片生成模型。

### 外部 API 与调用规格

网站运行时调用以下三类外部 AI API，均由后端发起。表内参数与当前实现对应；配置位置见下文，实际密钥不随源码提交。

| API | 请求地址与方法 | 鉴权方式 | 主要输入与输出 | 实现位置 |
|---|---|---|---|---|
| 导购聊天与工具调用 | `POST {app.llm.base-url}/chat/completions`；配置文件默认 Base URL 为 `https://api.openai-next.com/v1`，可由本地配置或环境变量覆盖 | `Authorization: Bearer <API Key>`，由 LangChain4j SDK 设置 | 输入 `model`、`messages`、`tools`；读取模型 `tool_calls`，执行允许的工具并将结果送回模型；以 `submitDecision` 结构化决策完成本轮，记录供应商实际返回的 Token 用量 | [OnlineAgent.java](src/main/java/com/hanyunjing/OnlineAgent.java) |
| 火山引擎图片换装 V2 | `POST https://visual.volcengineapi.com/?Action=CVSubmitTask&Version=2022-08-31`；之后使用同一地址的 `Action=CVGetResult` 查询 | AK/SK 的 V4 HMAC-SHA256 签名；区域 `cn-north-1`，服务 `cv` | 提交 `req_key=dressing_diffusionV2`、`binary_data_base64=[人像,服装参考图]`、服装及推理参数；取得 `data.task_id` 后轮询，校验结果并转换为 PNG | [VolcengineTryOnRenderer.java](src/main/java/com/hanyunjing/VolcengineTryOnRenderer.java)、[VolcengineV4Signer.java](src/main/java/com/hanyunjing/VolcengineV4Signer.java) |
| 腾讯混元生 3D Pro | `POST https://ai3d.tencentcloudapi.com/`；请求头 `X-TC-Action` 分别为 `SubmitHunyuanTo3DProJob`、`QueryHunyuanTo3DProJob`，`X-TC-Version=2025-05-13` | SecretId/SecretKey 的 `TC3-HMAC-SHA256` 签名，区域默认 `ap-guangzhou` | 提交 `ImageBase64`、`Model=3.0`、`GenerateType=Normal`、`EnablePBR=true`、`FaceCount=100000`；取得 `JobId` 后轮询，读取 `ResultFile3Ds` 中的 GLB 地址，再下载并验证模型 | [Tencent3dProvider.java](src/main/java/com/hanyunjing/Tencent3dProvider.java)、[TencentV3Signer.java](src/main/java/com/hanyunjing/TencentV3Signer.java) |

`api.openai-next.com` 是项目默认配置的第三方兼容网关，不是 OpenAI 官方 API 域名；接口兼容协议、模型名称和实际模型供应方是不同概念。运行者需使用自己有权限访问的服务地址和凭据。

开发阶段还使用了 **`gpt-image-2` 图片生成服务**制作部分静态商品图，经 imagegen API/CLI 提交提示词后，将成品安装到商品图与试穿参考目录。用途和提示词已记录在 [图片来源说明](docs/product-history-mapping.md)、[生成提示词](docs/product-image-prompts-v4.jsonl) 中，安装脚本为 [install-product-images-v4.py](scripts/install-product-images-v4.py)。该调用不属于网站运行接口，仓库未包含当时外部生成工具及完整 HTTP 调用记录，因此不宣称这些历史请求可由当前仓库完整重放。浏览商品时不调用该服务。

### 前端如何调用后端

前端通过 `/api` 代理请求本项目后端，由后端检查登录、授权及任务归属后调用上述云服务。业务接口参数与更多示例见 [BACKEND_API.md](BACKEND_API.md)。

| 功能 | 本项目 API | 调用流程 |
|---|---|---|
| AI 导购 | `POST /api/agent/chat` | 提交 `sessionId`、`message` 和可选 `requirements`（预算、尺码、颜色、数量），返回结构化推荐、依据和接待编号；`POST /api/agent/langchain/chat` 使用同一导购服务，以 SSE 事件返回结果 |
| 照片换装 | `POST /api/tryon/portrait`、`POST /api/tryon/generate` | 先以 multipart 上传 `file`，携带 `sessionId`、`authorized=true`、`aiAuthorized=true`；再提交 `sessionId`、`portraitId`、`productId`、`skuId`。查询 `GET /api/tryon/tasks/{id}`，完成后从 `GET /api/tryon/tasks/{id}/result` 下载 PNG |
| 3D 环绕 | `POST /api/tryon/tasks/{id}/orbit?sessionId=...` | `{id}` 为已完成的换装任务；首次请求体为 `{"authorized":true,"retryOf":null}`。使用返回的 3D 任务 ID 查询 `GET /api/tryon/orbit/tasks/{id}`，完成后从 `GET /api/tryon/orbit/tasks/{id}/model` 下载 GLB |

上述私有接口需要登录 Cookie；写请求还需当前会话的 `X-CSRF-Token`。换装和 3D 的查询、下载接口须带所属业务会话 `sessionId` 查询参数，它不能代替登录凭据。网站未接入真实支付或快递下单 API，支付与物流功能的范围见第四节。

### 导购模型配置与工具

在 `.local/agent.properties` 中配置：

```properties
app.llm.enabled=true
app.llm.base-url=${OPENAI_BASE_URL:}
app.llm.api-key=${OPENAI_API_KEY:}
app.llm.model=${OPENAI_MODEL:gpt-6.1-sol}
```

启动后端前设置 `OPENAI_BASE_URL`、`OPENAI_API_KEY`，必要时设置 `OPENAI_MODEL`；也可在本地文件中填入实际值。Base URL 应指向支持聊天工具调用的兼容接口，通常以 `/v1` 结尾。变量名为兼容配置名称，不表示只能连接某一家供应商。

模型可使用 `scenes`、`products`、`knowledge`、`localSize`、`outfits`、`submitDecision` 六个受限工具。服务端可先查询目录与史料提供上下文，额外工具请求由模型发出，最终提交结构化决策；原始身体数据不发送给导购模型。每轮最多进行五次模型调用，失败或超时明确返回错误。

预算、价格、库存、尺码校验属于后端确定性逻辑；史料来自数据库关联资料；商家回复由人工填写。这些能力与在线模型调用分别记录，不作为模型生成结果冒充展示。

### 换装与 3D 配置

- **火山引擎：** 按模板创建 `.local/tryon.properties`，设置 `VOLCENGINE_ACCESS_KEY`、`VOLCENGINE_SECRET_KEY`；启用项为 `app.tryon.enabled=true`。详见 [图片换装说明](docs/volcengine-tryon.md)。
- **腾讯混元 3D：** 按模板创建 `.local/orbit.properties`，设置 `TENCENTCLOUD_SECRET_ID`、`TENCENTCLOUD_SECRET_KEY`；启用项为 `app.orbit.enabled=true`，默认区域 `ap-guangzhou`、模型 `3.0`。详见 [3D 环绕说明](docs/hunyuan-3d.md)。
- 状态接口：`GET /api/agent/status`、`GET /api/tryon/status`、`GET /api/tryon/orbit/status`；3D 状态接口需要登录。`ready=true` 表示配置满足调用条件，实际权限、额度和服务可用性以云端返回为准。
- 用户上传并授权后才发送图片到相应服务。任务提交后查询同一任务编号，取消或失败不会自动再次提交付费任务；手动重新生成可能再次计费。

本机混元 3D 配置已读取并返回 `ready=true`，尚未完成真实云端生成与视觉效果验收。单张图片生成的侧面和背面由 AI 推测补全，可用于观察生成模型，不代表真实拍摄或精确服装结构。

## 六、项目结构

```text
hanyunjing/
├── src/
│   ├── main/
│   │   ├── java/com/hanyunjing/    # API、鉴权、导购、交易、换装与 3D 服务
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── catalogue-v4.json  # 服饰形制与史料修订数据
│   │       ├── db/schema.sql      # SQL 表、关联与约束
│   │       └── static/images/     # 商品图、试穿参考图等静态资源
│   └── test/java/com/hanyunjing/  # 后端单元与集成测试
├── frontend/
│   ├── src/
│   │   ├── views/                # 商城、问衣、试衣、订单等页面
│   │   ├── components/           # 商品、评论、试衣与 3D 组件
│   │   ├── stores/               # 登录与会话状态
│   │   └── router.ts             # 用户端路由
│   ├── admin/                    # 独立管理端入口与页面
│   ├── scripts/                  # 前端组件交互检查
│   ├── vite.config.ts            # 商城开发服务器与 API 代理
│   ├── vite.admin.config.ts      # 管理端开发服务器与构建配置
│   └── package.json
├── docs/                         # 数据库、史料、AI 配置及参赛说明
├── scripts/                      # 启动、评测和数据维护脚本
├── output/                       # 本地生成资产与评测报告
├── .local/                       # 本地凭据、数据库及媒体，不提交版本控制
├── pom.xml
├── BACKEND_API.md
└── README.md
```

项目采用 Maven 测试目录 `src/test/` 与前端检查目录 `frontend/scripts/`，没有单独的根目录 `tests/`。

## 七、测试说明

### 自动化测试与构建

安装依赖后，在项目根目录执行：

```powershell
# 后端单元与集成测试
mvn test

# 前台、后台类型检查与构建
npm --prefix frontend run build
npm --prefix frontend run build:admin

# 图片保存与 3D 组件交互检查
node frontend/scripts/verify-tryon-orbit.mjs
```

后端测试报告位于 `target/surefire-reports/`。测试中的外部模型协议采用本地受控响应，不需要真实云服务密钥。

| 测试模块 | 主要覆盖场景 |
|---|---|
| `AccountSecurityTests`、`AccountStoreTests` | 注册登录、密码存储、账号隔离、CSRF、商城与管理端会话隔离 |
| `CatalogueTests`、`ProductImageTests`、`CommerceDatabaseTests` | 朝代形制与史料、商品图片、SQL 持久化、SKU 价格、并发库存、历史订单、评论即时公开、商品删除与管理员权限 |
| `OnlineAgentTests`、`RetailWorkflowTests` | 工具协议、真实目录 ID 校验、多轮约束、模型异常、确认加购幂等、价格库存复核、接待证据持久化、商家待办及评测统计隔离 |
| `SizeAssistantTests` | 尺码区间、不完整数据、无匹配尺码和用户资料隔离 |
| `OrderPaymentTests`、`SupportTicketServiceTests` | 模拟支付幂等、取消与库存回补、订单归属、客服持久化及重复提交 |
| `TryOnServiceTests`、`VolcengineTryOnRendererTests`、`VolcengineV4SignerTests` | 图片授权与格式、签名、提交与轮询、超时取消、删除清理、防重复生成及错误脱敏 |
| `OrbitTests` | 腾讯签名、单次任务提交、GLB 校验、账号隔离、取消重试、关联资源清理与异常处理 |
| `verify-tryon-orbit.mjs` | 保存图片成功与失败、错误响应拦截、3D 配置与授权、轮询、取消、重试及模型下载交互 |

本机另可执行 `node frontend/scripts/verify-catalogue-ui.mjs`，检查商品、评论、删除确认和导购组件交互；该脚本依赖未提交的 `.local/catalogue-repair-before.json` 本地数据快照，不属于全新环境的默认测试命令。

### 在线模型评测

```powershell
# 输出构造用例数量与运行提示，不调用模型
node scripts/evaluate-retail.mjs

# 使用已配置模型实际运行前三个用例，可能产生费用
node scripts/evaluate-retail.mjs --online --limit=3
```

在线评测需先运行后端，配置导购模型，并准备 `.local/admin-credentials.txt`；该本地文件使用 `用户名：...`、`密码：...` 两行保存有权限的管理员账号。脚本包含 30 个构造用例，覆盖朝代、预算、尺码、颜色、数量和商家协助，报告保存至 `output/evaluation/`。评测不加购或下单，其记录标记为 `EVALUATION` 并排除在用户接待统计之外。

### 手动验收流程

1. 在商城注册并登录，查看不同朝代的商品、价格、库存、图片及史料出处。
2. 在“问衣”输入“想买明制汉服，预算 500 元，M 码”，核对推荐规格与预算；再尝试 1 元预算或不存在的尺码，查看无匹配处理。
3. 选择推荐规格并确认加购，在“选购记录”查看接待过程，提交商家协助；从独立后台回复，返回用户页面核对回复。
4. 进入试衣镜，上传本人或已授权照片并完成换装；在结果页保存 PNG。开通云服务后，可继续授权生成 3D，检查旋转、各面视角、全貌及 GLB 下载。
5. 发布商品评论，使用另一用户或游客查看是否立即公开；后台填写商家回复并核对前台展示。
6. 创建订单并模拟支付；在后台核对库存、订单和物流登记。测试草稿或已下架商品删除时，检查二次确认弹窗。

自动化检查不等于云端生成成功或浏览器视觉验收，构造用例也不能作为真实企业经营成果。现有验收范围见 [参赛准备说明](docs/competition-readiness.md) 和 [3D 环绕说明](docs/hunyuan-3d.md)。本项目包含此前已存在的代码，赛期原创范围及既有代码使用资格需如实向主办方确认，保留真实提交历史。

### 提交历史与赛期证明

- 本仓库从 `de04689`（`Prepare Hanyunjing prototype for review`）开始记录，提交时间为 **2026-10-03 23:17:15（UTC+08:00）**。
- 后续真实修改正常提交并推送，保留原有历史。当前完整可用历史见 [GitHub 提交记录](https://github.com/ssx-810118/hanyunjing/commits/main/)，本地可使用 `git log --all --date=iso-strict --format=fuller --stat` 核对。
  
## 八、团队成员

队伍名称：**云杼队**。

成员信息尚未提供，以下为填写模板；请按实际 1–5 人团队替换，删除未使用行。同一成员可承担多个职责。

| 姓名 | 团队角色 | 实际承担工作 |
|---|---|---|
| 孙石祥 | 队长 | 负责的后端、数据库和AI接口工作 |
| 陈博涛 | 成员 | 负责的前端、后台、行业调研、测试、资料工作 |

