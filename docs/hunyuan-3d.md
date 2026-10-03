# 腾讯混元3D环绕与图片保存

试衣镜完成换装后，“保存图片到本地”下载本次PNG。结果页新增“3D环绕试衣”，使用本次换装结果图生成带纹理GLB。模型可拖动旋转、缩放、自动环绕，提供正面、左面、背面、右面和全貌按钮，并支持下载GLB。页面首次显示的正面基于模型坐标；如果朝向不对，转至正面后点击“设当前为正面”。

## 开通与配置

1. 在腾讯云开通混元生3D（AI3D），确认账号有可用额度和调用权限。
2. 在腾讯云访问管理中准备SecretId、SecretKey，将凭据填写在本机 `.local/orbit.properties`。已准备无密钥的文件；公开模板为 `docs/orbit.properties.example`。
3. 配置文件可以直接填写密钥，也可保持占位符并设置后端进程环境变量 `TENCENTCLOUD_SECRET_ID`、`TENCENTCLOUD_SECRET_KEY`。密钥不要写入前端、聊天、截图或提交到仓库。
4. 重启后端，在已登录的结果页点击“刷新状态”。`ready=true`仅表示配置满足调用条件，实际可用性取决于权限、余额和腾讯云响应。

~~~properties
app.orbit.enabled=true
app.orbit.tencent.secret-id=${TENCENTCLOUD_SECRET_ID:}
app.orbit.tencent.secret-key=${TENCENTCLOUD_SECRET_KEY:}
app.orbit.tencent.region=ap-guangzhou
app.orbit.tencent.model=3.0
app.orbit.timeout-seconds=900
~~~

模型版本支持3.0、3.1。本次默认3.0、Normal模式、开启PBR纹理，100000面。费用以腾讯云账户实际计费为准。

## 使用流程

上传本人或已授权的正常穿搭照片 → 选择汉服 → 火山引擎完成换装 → 打开结果页 → 保存PNG，或勾选将本次换装效果图发送腾讯云的授权 → 点击“生成3D环绕” → 等待完成后旋转查看并保存GLB。

腾讯任务只提交一次，之后查询同一个JobId。刷新页面会读取现有任务，不会自动重复生成。失败/取消后点击“重新生成”才创建新的付费请求；一次请求网络结果未知时，先刷新状态，避免重复付费。

## 真实接口与资源边界

- 固定服务域名：ai3d.tencentcloudapi.com；版本：2025-05-13。
- 提交：SubmitHunyuanTo3DProJob；图片使用ImageBase64，不把输入图公开部署到URL。
- 查询：QueryHunyuanTo3DProJob；读取ResultFile3Ds中Type=GLB的文件。Normal模式默认返回GLB，不传只支持其他格式的ResultFormat值。
- 鉴权：TC3-HMAC-SHA256；前端不接触SecretId、SecretKey或云端模型URL。
- 结果在后端内存暂存，最多60MB/模型；请求超时、失败不会展示预置模型代替。
- 后端验证GLB头、版本、自包含资源；下载仅接受腾讯COS HTTPS域名，不跟随重定向、不向下载地址发送签名密钥。不支持外部纹理或Draco/Basis压缩扩展。

协议依据为腾讯云官方SDK的 [类型定义](https://github.com/TencentCloud/tencentcloud-sdk-nodejs/blob/master/src/services/ai3d/v20250513/ai3d_models.ts) 和 [客户端](https://github.com/TencentCloud/tencentcloud-sdk-nodejs/blob/master/src/services/ai3d/v20250513/ai3d_client.ts)。

## 隐私、有效期与效果

模型与原换装任务使用相同的有效期，最长为上传照片后的30分钟，不会因为生成3D而延长。原人像删除、换装结果取消或到期，本站关联模型同时不可访问并被清理。服务重启会清除内存中的人像、换装和3D缓存。下载到本人电脑的文件不受本站到期清理影响。

本站取消只停止本地等待和展示；腾讯云已经收到的请求可能继续处理或计费，本站无法保证清除供应商副本。

单张正面照没有真实背面信息，侧后方由AI推测补全。可360度旋转查看生成的几何模型，但不能保证人物、衣服背面、纹样、袖口内侧或衣褶与实物一致，不代表真实服装结构、尺码或合身测量。

本机已在 `.local/orbit.properties` 配置腾讯云密钥，重启后登录查询状态接口返回 `provider=tencent-hunyuan-3d-pro`、`ready=true`。这仅确认程序已读取配置，尚未进行真实云端生成与效果验收；协议测试使用本地受控响应，不代表腾讯云实际生成成功。

## 本次检查（2026-10-03）

- Maven全套测试和打包通过；新增8项协议/生命周期/签名检查，以及HTTP权限与CSRF检查。
- 前端TypeScript检查与构建通过；图片下载拒绝错误响应，3D配置/授权、轮询、取消、明确重试和模型下载交互检查通过。
- Three.js真实GLB解析器成功解析测试几何文件；该测试文件只用于自动化检查，没有作为用户模型或云端生成结果展示。
- 新版已在5173/5174/8082运行，配置后的3D接口返回 `ready=true`，匿名请求被拒绝；没有发起腾讯付费生成。可运行 `node .local/verify-orbit-start.mjs --configured` 复查本机状态。
- Tabbit浏览器自动化连接不可用，未完成WebGL真实浏览器视觉验收。腾讯云权限、额度、生成质量及侧后方效果待使用授权照片实测。

重复检查命令：`mvn test`、`npm --prefix frontend run build`、`node frontend/scripts/verify-tryon-orbit.mjs`。
