# 汉韵镜 - 云杼队

## 一、项目简介

汉韵镜面向**零售行业汉服门店的导购与售前客服部门**。它把顾客的朝代偏好、场景、预算、尺码、颜色和数量转成可核验的商品选择，帮助导购减少重复查款和库存核对，并把依据、确认结果和商家跟进记录保存到 SQL。

当前项目是可运行原型。合作门店、访谈记录、脱敏真实咨询样本和经营指标尚未提供，因此本文只描述已经实现或已经验证的系统能力，不把假设写成真实商业成果。

### 核心闭环

1. 顾客在商城输入需求，选择预算、尺码、颜色和数量。
2. 在线模型通过工具读取商品目录、库存、尺码和历史依据，形成结构化候选。
3. 服务端按具体 SKU 校验价格、库存、尺码、颜色和数量。
4. 顾客确认后加入衣囊，重复请求不会重复加购。
5. 无匹配或需要人工确认时，系统生成商家待办。
6. 商家在独立管理端回复，顾客在选购记录中看到回复。

预算只计算本款衣裳乘数量，不含另选配饰和运费；加入衣囊不是支付成交。支付目前是模拟流程，物流由商家人工录入。

## 二、技术栈

### 前端

- Vue 3.5、TypeScript 5.8、Vite 6
- Vue Router 4、Pinia 3、Axios
- Three.js：试衣结果的 3D/GLB 查看器
- 商城端口 `5173`，独立管理端口 `5174`

### 后端

- Java 17、Spring Boot 3.3.13
- Spring JDBC、Bean Validation、LangChain4j 0.35.0
- H2 文件数据库（默认，重启后保留）
- MySQL 8.0.16+ 配置模板
- Maven、JUnit 5、Spring Boot Test
- 后端端口 `8082`，管理端使用独立 cookie 和服务端 ADMIN/CSRF 校验

### 数据与 AI

- 规范化 SQL：账号、商品、SKU、图片、历史出处、购物车、订单、评论、接待工作流等关系分开存储；商品图片存文件或 `.local/media`，SQL 保存 URL。
- 在线导购 Agent：模型负责理解需求和调用工具，确定性规则负责价格、库存、尺码和数量约束。
- 火山引擎图片换装：生成试穿平面图。
- 腾讯混元生 3D Pro：根据换装图提交 GLB 生成任务并轮询结果。当前代码、签名协议和配置已就绪，真实云端生成与视觉验收仍需开通服务后完成。

## 三、部署说明

### 环境要求

- Windows、JDK 17、Maven 3.9+、Node.js 22 LTS（Node 24 也可）
- 若使用 MySQL：MySQL 8.0.16+ 和一个可写数据库
- 在线 Agent、图片换装和混元 3D 均需要各自服务凭据；不配置时，基础目录、购物车、评论和管理功能仍可运行。

### 安装与构建

```powershell
git clone https://github.com/ssx-810118/hanyunjing.git
cd hanyunjing
New-Item -ItemType Directory -Force .local | Out-Null
npm --prefix frontend ci
mvn -B package
npm --prefix frontend run build
npm --prefix frontend run build:admin
```

默认使用文件型 H2，首次启动会在 `.local/database/` 自动建库和初始化目录。`.local/` 被 `.gitignore` 忽略，仓库不会带原电脑的账号、订单、数据库或密钥。

启动三个进程：

```powershell
# 终端 1
java -jar target/hanyunjing-0.0.1-SNAPSHOT.jar

# 终端 2
npm --prefix frontend run dev

# 终端 3
npm --prefix frontend run dev:admin
```

也可以运行 `scripts/start-project.ps1`。访问：

- 商城：`http://127.0.0.1:5173/`
- 管理后台：`http://127.0.0.1:5174/`
- 后端 API：`http://127.0.0.1:8082/api`

新数据库没有发布预设管理员。先在商城注册账号，再登录独立管理端；若页面提示初始化管理员，从后端本地生成的 `.local/admin-setup-token.txt` 读取一次性口令，把当前账号授权为管理员。原开发机上的 `admin/admin123` 不会随仓库发布。

### MySQL 配置

先创建数据库，再复制 `docs/database.properties.example` 为 `.local/database.properties`，填写 JDBC 地址、用户名和密码。应用连接 MySQL 失败时不会静默切换到 H2。生产环境应把密钥放进服务器环境变量或受限的本地配置，不要提交 Git。

### 外部 AI 配置

所有命令从仓库根目录执行。下面的值是占位符，请在本地替换，不要将真实密钥提交 GitHub。环境变量必须在启动后端的同一个终端设置；配置更新后重启后端。

```powershell
# 在线导购：服务商需支持 Chat Completions 与工具调用
$env:OPENAI_BASE_URL='你的兼容服务地址，以 /v1 结尾'
$env:OPENAI_API_KEY='你的本地 API Key'
$env:OPENAI_MODEL='供应商实际支持的模型名称'

# 图片换装：复制模板时不要覆盖已有配置
Copy-Item docs/tryon.properties.example .local/tryon.properties
$env:VOLCENGINE_ACCESS_KEY='你的本地 AccessKey'
$env:VOLCENGINE_SECRET_KEY='你的本地 SecretKey'

# 混元 3D：需在腾讯云开通相应服务及调用权限
Copy-Item docs/orbit.properties.example .local/orbit.properties
$env:TENCENTCLOUD_SECRET_ID='你的本地 SecretId'
$env:TENCENTCLOUD_SECRET_KEY='你的本地 SecretKey'

java -jar target/hanyunjing-0.0.1-SNAPSHOT.jar
```

也可把模型配置写在 `.local/agent.properties`，键为 `app.llm.enabled`、`app.llm.base-url`、`app.llm.api-key`、`app.llm.model`。只启用已经配置的服务即可。`ready=true` 仅表示配置齐全，不代表云服务连通、余额或生成效果已验证。

上述步骤提供本地运行方式。GitHub 托管源码不会自动发布网站；如需公网访问，应另外部署 Java 服务和两套前端静态构建产物，并配置反向代理、HTTPS 和访问控制。当前仓库不提供已经上线的公网 Demo。

## 四、使用说明

### 顾客端演示

1. 打开商城首页，进入导购或问衣笺页面。
2. 输入类似“第一次穿，准备去园林，想要唐制，素雅，预算 300 元”的需求，或使用页面示例。
3. 查看模型给出的候选、价格、规格、库存和史料依据。
4. 选择具体尺码、颜色和数量，确认后加入衣囊。
5. 在试衣镜选择汉服并上传人像，后端使用对应服装参考图提交换装；结果页可保存图片。配置腾讯混元 3D 后可继续提交环绕模型任务。
6. 在评论区直接发表评论；评论默认公开，商家可以回复，不设审核步骤。

### 商家端演示

1. 打开 `5174`，使用已授权管理员登录。
2. 在商品管理查看上架状态、价格、SKU、库存、图片和史料关联。
3. 在接待/待办中处理无匹配、规格确认和顾客咨询。
4. 在评论页面回复顾客；商城端会显示公开评论和商家回复。

### 现场 Demo 建议

先用一个预算足够的需求展示“候选 → SKU 校验 → 确认加购 → 管理端跟进”，再把预算改成 1 元或尺码改成 `XXXXL`，展示系统返回无匹配并生成待办。最后展示换装示例和 API 配置状态。在线模型调用失败时应保留失败记录并说明原因，不用静态文字冒充在线结果。

## 五、赞助商 API 使用清单

下表列出项目实际调用的外部 API。当前未获得赛事官方赞助商名单确认，因此“是否为本赛事赞助商”需由主办方核对；不能把供应商名称直接写成已确认赞助商。

| API/服务 | 用途 | 调用方式 | 凭据位置 | 当前状态 |
|---|---|---|---|---|
| OpenAI 兼容 Chat Completions（默认 `https://api.openai-next.com/v1`，第三方兼容网关） | 在线导购 Agent 的需求理解、工具调用和结构化选择 | 后端通过 LangChain4j 发送 `POST /chat/completions`，使用 `model`、`messages`、`tools` 和 `tool_calls`，最多 5 轮 | `.local/agent.properties` 或 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL` | 代码已接入；需评委自行提供可用凭据 |
| 火山引擎视觉智能开放平台 | 图片换装 | 后端向 `https://visual.volcengineapi.com` 调用 `CVSubmitTask`、`CVGetResult`，`req_key=dressing_diffusionV2`，AK/SK V4 签名 | `.local/tryon.properties` 或 `VOLCENGINE_ACCESS_KEY`、`VOLCENGINE_SECRET_KEY` | 代码已接入；仓库包含配置模板，不含密钥 |
| 腾讯云混元生 3D Pro | 从换装结果提交 3D 任务并获取 GLB | 后端调用 `SubmitHunyuanTo3DProJob`、`QueryHunyuanTo3DProJob`，TC3-HMAC-SHA256，区域默认 `ap-guangzhou` | `.local/orbit.properties` 或 `TENCENTCLOUD_SECRET_ID`、`TENCENTCLOUD_SECRET_KEY` | 签名、协议和轮询代码已完成；真实云端生成需开通服务并配置密钥 |
| gpt-image-2（开发阶段） | 生成静态商品图素材 | 在开发阶段根据 `docs/product-image-prompts-v4.jsonl` 生成商品图，图片进入仓库静态资源 | 仅开发环境使用，运行网站不依赖 | 不是网站运行时 API；完整外部生成请求记录未随仓库发布 |

所有外部服务请求由后端发起，浏览器不会接触密钥。未配置服务时，页面会显示“未配置”或返回明确错误，不会用预设答案冒充 AI。

导购默认模型 ID 是 `gpt-6.1-sol`，最终可用模型以所选兼容服务商为准，默认网关不是 OpenAI 官方地址。火山换装 API 版本为 `2022-08-31`，上传人像和衣物参考的 Base64 内容。腾讯混元 3D API 版本为 `2025-05-13`，默认模型版本 `3.0`，通过 `ImageBase64` 提交后获得 `JobId`，轮询下载 GLB。调用会产生供应商规定的费用。

详细配置与源码可查阅 [换装说明](docs/volcengine-tryon.md)、[混元 3D 说明](docs/hunyuan-3d.md)、[后端接口说明](BACKEND_API.md)。

## 六、已验证结果与限制

- 干净 clone 后：`npm ci`、Maven 打包、商城构建、管理端构建均通过。
- Java 测试：156 项通过，0 failures、0 errors、0 skipped。
- 在线导购构造用例：3/3 通过，覆盖汉制预算与 M 码、低预算无匹配、不存在尺码；这是小规模构造冒烟测试，不代表真实客户效果。
- 未提供合作企业、人工基线、真实用户样本、转化率或 ROI 数值。商业价值需要门店试点后按“人工处理耗时 - 使用系统处理耗时”等指标计算。
- 图片换装示例标记为虚构成年 AI 示例人物；混元 3D 的侧面和背面属于模型推测，不等于真实服装结构或精确合身结果。

### 运行测试

```powershell
mvn -B test
npm --prefix frontend run build
npm --prefix frontend run build:admin
node frontend/scripts/verify-tryon-orbit.mjs
```

后端测试覆盖账号权限、商品与购物车、SKU 约束、确认幂等、商家跟进、换装任务和 3D 协议等场景。自动化测试包含协议桩，不能替代外部云服务验收。

`node scripts/evaluate-retail.mjs` 默认只列出构造用例。后端和凭据就绪后，运行 `node scripts/evaluate-retail.mjs --online --limit=3` 才会真实调用模型并可能计费。现有在线验证报告见 [3 条构造用例记录](output/evaluation/retail-1791027749458.json)。

人像、换装和 3D 任务为临时数据，最长保留至人像上传后 30 分钟，服务重启后清除。生成结果请及时下载。历史示例始终标注为示例，不能代替评委的现场真实调用。

## 七、项目结构

```text
├── frontend/                 # Vue 商城与独立管理后台
├── src/main/java/            # Spring Boot API、Agent、规则与外部服务适配
├── src/main/resources/db/    # SQL schema、种子目录与历史依据
├── docs/                     # 配置模板、接口说明、赛事准备说明
├── scripts/                  # 启动和验证脚本
├── output/evaluation/        # 构造用例验证记录
├── PROJECT_DESCRIPTION.md    # 本说明
└── README.md                 # 仓库总览
```

## 八、团队

队伍：**云杼队**

| 成员 | 角色 | 主要工作 |
|---|---|---|
| 孙石祥 | 队长 | 后端、数据库和 AI 接口 |
| 陈博涛 | 成员 | 前端、管理后台、行业调研、测试和资料 |

## 九、源码与提交说明

仓库地址：<https://github.com/ssx-810118/hanyunjing>

项目包含当前仓库可见的 Git 提交记录。赛事规则要求完整提交历史证明代码在比赛期间原创；本项目缺少更早的历史备份，无法用后来提交补造该证据。评委应按主办方规则核验并接受必要报备。
