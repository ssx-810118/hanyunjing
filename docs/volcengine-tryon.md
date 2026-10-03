# 火山引擎图片换装 V2 本地配置

试穿服务现使用火山引擎智能视觉的「图片换装 V2」。它接收人物照片和服装参考图，提交异步换装任务，再查询同一个任务的结果。问衣聊天服务使用独立配置，此次迁移不修改 `.local/agent.properties`。

## 1. 开通服务与获取凭据

- [图片换装服务开通页面](https://console.volcengine.com/ai/ability/info/102)
- [图片换装 V2 官方接口文档](https://docs.volcengine.com/docs/Intelligentvisionservices/ImageChangeV2InterfaceDocumentation-1)

使用火山引擎账号开通对应服务，在控制台的「访问控制 → 访问密钥」管理 Access Key ID 和 Secret Access Key，并确认用于调用的身份具备该服务权限。具体入口名称、可用额度和收费规则以控制台实际显示为准。

本接口使用火山引擎 AK/SK 请求签名，不使用原图片服务的 API Key，也不是聊天服务的模型 API Key。生成可能产生费用。2026 年 10 月 3 日的本地验收使用虚构成年 AI 示例人物，完成了 1 次真实换装提交，结果成功返回；这只代表本次请求成功，不保证后续权限、额度或生成速度。

## 2. 本地配置

项目根目录下的 `.local/tryon.properties` 使用本地凭据配置；以下为不含真实密钥的模板，也可复制 `docs/tryon.properties.example` 重新建立该文件：

```properties
app.tryon.enabled=true
app.tryon.volcengine.access-key=${VOLCENGINE_ACCESS_KEY:}
app.tryon.volcengine.secret-key=${VOLCENGINE_SECRET_KEY:}
```

启动后端的进程需要读取 `VOLCENGINE_ACCESS_KEY` 和 `VOLCENGINE_SECRET_KEY` 两个环境变量。也可以仅在本机 `.local/tryon.properties` 中将两个占位值替换为对应凭据；该目录已在 `.gitignore` 中排除。不要把真实凭据复制到聊天、截图或公开代码。

全局默认项位于 `src/main/resources/application.properties`：

```properties
app.tryon.enabled=${TRYON_ENABLED:false}
app.tryon.volcengine.access-key=${VOLCENGINE_ACCESS_KEY:}
app.tryon.volcengine.secret-key=${VOLCENGINE_SECRET_KEY:}
app.tryon.timeout-seconds=600
```

本地配置启用服务，但缺少任一访问密钥时仍返回未配置完成，页面不能发起生成。移除本地配置后，默认不会启用试穿服务；可通过 `TRYON_ENABLED=true` 显式启用。

## 3. 重启与验证

配置或环境变量修改后，需要重新启动后端 Java 进程。本次已将迁移后的后端独立构建至下列路径，避免覆盖旧进程正在读取的 JAR。先停止本项目原后端进程，再从项目根目录启动：

```text
java -Djava.io.tmpdir=target/runtime-temp -jar target/flow-build/hanyunjing-flow-fixes.jar --server.address=127.0.0.1 --server.port=8082
```

Java 需要使用项目支持的 Java 17 或相应运行环境。前端保持在 `http://127.0.0.1:5173`，试衣镜路径为 `/tryon`。如前端未运行，从 `frontend` 目录执行 `npm run dev -- --host 127.0.0.1 --port 5173 --strictPort`。

打开试衣镜后刷新服务状态，或读取 `GET /api/tryon/status`。正常配置应显示 `provider=volcengine-virtual-tryon-v2`、`mode=virtual-tryon-v2`。`ready=true` 和页面「已配置」表示本地配置满足启动条件，不表示已经通过火山引擎权限、额度或真实生成验证。

实际验证时，上传本人或已获授权的人像，确认将人像与服装发送至火山引擎的授权，再手动提交一次生成。没有真实成功结果之前，不应把示例页标为已完成。

## 4. 接口与资产

- API 主机：`https://visual.volcengineapi.com`
- 服务标识：`dressing_diffusionV2`
- 提交动作：`CVSubmitTask`；查询动作：`CVGetResult`；接口版本：`2022-08-31`
- 签名区域：`cn-north-1`；签名服务：`cv`
- 后端使用 Base64 提交人物图与服装图，不要求把人像部署为公网 URL。

火山官方文档要求输入图小于 5 MB、尺寸小于 4096×4096，建议使用 JPG。项目新增 `src/main/resources/static/images/tryon-garments/p1.jpg` 至 `p14.jpg` 作为 API 服装参考图：全部为 768×1152 RGB JPEG，保留原尺寸，每张低于 5 MB。原来的 `static/images/products/*.webp` 继续用于商品展示，未替换或修改；服装名称、朝代和商品 ID 也不因转码改变。

提交成功后只轮询已获得的任务 ID。网络失败、超时或生成失败不会自动再次提交换装任务；用户主动点击重新生成才会发起新的提交，并可能再次计费。取消本地任务不能保证已经发送的火山任务停止或不再计费。

## 5. 人像与示例状态

本站在后端内存中临时保存人像及关联结果，最长 30 分钟，可通过页面删除。删除只清除本站副本，不代表火山引擎副本也已删除；火山引擎的处理和保留规则以其服务条款及隐私政策为准。照片式换装只提供搭配预览，不测量身材，也不保证衣服尺码合身或精确保留全部服装细节。

工作区的 `output/imagegen/tryon-preview/status.json` 已标为 `completed`，`resultReady=true`。示例人物为虚构成年 AI 人物，示例服饰为明制月白袄马面裙（p14）。`/result/example` 展示人物原图与火山引擎生成结果，明确标注“AI 示例 · 非真实用户照片”；不把真实用户上传的照片保存为公开示例。

本次验收只提交了 1 次生成，上传和归整约 0.27 秒，从提交到完成约 52 秒；等待时间受远端排队等因素影响。公开示例复用这一次已完成的结果，没有再次调用生成。验收记录位于 `target/flow-provider-usage.json`，本次任务的调用上限为 5 次。
