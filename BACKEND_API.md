# 汉韵镜后端契约

当前运行地址 http://127.0.0.1:8082/api。前台5173、独立后台5174通过代理访问同一后端。Java 17 / Spring Boot / SQL；MySQL配置在.local/database.properties，未覆盖配置时默认持久化H2。

## 认证与返回值

所有JSON接口返回 {code,message,data}，code=0成功，错误同时使用对应HTTP状态。图片和SSE例外。

- GET /auth/session 取得登录状态与csrfToken。POST /auth/register、/auth/login、/auth/logout 管理账号会话。
- 非公开读取接口要求登录。所有写请求必须带当前会话的 X-CSRF-Token；服务端检查来源。
- 后台请求带 X-HYJ-Client: admin，使用独立管理员Cookie，服务端检查ADMIN角色。不能用前台账号会话代替。
- sessionId 为1–64位字母、数字、下划线或短横线，是聊天、试穿和Trace的业务标识；服务端绑定当前账号，不能作为身份凭据。
- 常见状态：400参数错误，401未登录，403权限或安全令牌问题，404资源不属于当前账号或不存在，409库存/价格/重复操作冲突，413图片过大，502模型响应无效，503未配置或繁忙，504超时。
- 不返回密钥、密码哈希、供应商原始异常。账号、订单和工作流接口不缓存。

## 在线导购

POST /agent/chat

~~~json
{
  "sessionId":"selection-1",
  "message":"想买汉制汉服，预算500元，M码",
  "requirements":{"budget":500,"size":"M","color":null,"quantity":1}
}
~~~

requirements可省略，此时沿用当前会话最近成功一轮的条件。传入时视为完整替换；明确的文本金额、尺码和数量优先。预算为空表示不限，数量1–99。金额支持“预算300元”“300元以内”等明确形式；区间或无法识别的预算须改为具体金额。当前预算为本款衣裳乘数量，不包含另选配饰。场景和是否首次穿着为可选偏好。

模型实际读取目录、知识和本地校验，最后调用submitDecision提交至多三件真实商品。服务端核对用户槽位证据、商品和具体SKU的预算、尺码、颜色、库存。失败报错，不用预设答案代替模型。

返回AgentReply包括：sessionId、status、message、slots、missingFields、funnel、recommendations、knowledge、requirements、workflowId。

- status：NEED_SLOT待补充意向、DONE已选出候选、NO_MATCH本轮无匹配、HANDOFF须商家核验。
- recommendations每项含product、sizeAdvice、accessories、reasons、eligibleSkus、evidence。只有eligibleSkus中的规格可确认选购。
- evidence来自商品关联的知识条目及真实来源字段，正文为整理说明，不等同于文献逐字引文或文物复原证明。
- workflowId指向SQL持久接待；对话原文、身材、人像不写入工作流表。原始聊天历史只在进程内短期保留。
- NO_MATCH或HANDOFF生成商家待办，不代表商家已处理。正式礼仪继续要求人工核验。

GET /agent/status 返回enabled、configured、ready、mode、connection、message。ready只表示已启用且配置完整；connection是最近实际请求状态，不保证下一次成功。

POST /agent/langchain/chat 使用同一请求体、同一在线主链，通过SSE返回结果；停止浏览器等待不取消已开始的服务端请求。

每个会话串行，全局最多8个在途请求；单次模型请求45秒、整轮120秒、最多5轮模型响应和32次工具请求，不自动重试。进程内历史最多20条消息，空闲30分钟可被清理。

## 持久选购与商家处理

| 方法与路径 | 权限 | 作用 |
|---|---|---|
| GET /retail/runs | 登录用户 | 本人最近50次LIVE接待 |
| POST /retail/runs/{id}/confirm | 记录所属用户 | 请求体 {"skuId":"p1-M"}，核对并加入衣囊 |
| POST /retail/runs/{id}/help | 记录所属用户 | 为已结束接待创建商家待办，重复请求不重复创建 |
| GET /admin/retail/runs | 管理员 | 最近200次接待，包括LIVE和EVALUATION |
| GET /admin/retail/metrics | 管理员 | 全部LIVE接待指标，另给evaluations总数 |
| PUT /admin/retail/cases/{id} | 管理员 | 请求体 {"reply":"已核对库存"}，回复并完成OPEN待办 |
| POST /admin/retail/evaluate | 管理员 | 同Chat请求体，真实调用模型，来源标记EVALUATION |

确认仅接受本次候选SKU，重新读取商品状态、价格和库存。价格与接待报价不同须重新问衣。原衣囊数量与本次数量相加后仍须有库存且不超过99；事务加锁。同一接待同一SKU重复确认不重复加购，不同SKU冲突。每次接待最多确认一个SKU。评测记录禁止加购。

历史使用snake_case字段，嵌套candidates、evidence、steps、cases、selections。不返回account_id或session_key。运行状态另有RUNNING、FAILED、INTERRUPTED、CONFIRMED；重启时未完成任务标记INTERRUPTED。候选价格和引用内容为接待时快照，商品名称及上下架状态显示当前值。

执行步骤区分SERVER_TOOL服务端预查询、MODEL_TOOL模型请求工具、MODEL真实响应、SERVER_CHECK约束校验。模型调用数、Token和耗时来自实际调用；Token缺失为null，不编造费用。

metrics字段：total、failed、confirmed、running、openCases、resolvedCases、evaluations、p95Ms、averageMs、cost、costNote、scope。确认数表示加购，不是成交或转化率；EVALUATION不进入LIVE指标。无耗时样本时平均值/P95为null，计费未核验时cost为null。JSON导出范围是最近200条，不冒充全量原始数据。

## 商品、史料和评论

GET /products 支持dynasty、form、scene、size、q筛选，只展示已上架商品；GET /products/{id}、/categories、/scenes提供目录。商品包含images、skus、sizeChart、tags等；价格库存来自SKU。GET /products/{id}/image读取商品图片，GET /media/{name}读取上传图片，无图返回404，不伪造成功。

GET /products/{id}/references读取逐款关联史料。GET /knowledge/search、/knowledge/articles/{id}、/knowledge/forms、/knowledge/dynasties提供知识检索。证据不足或冲突时abstained为true，正式礼仪humanRequired为true。POST /knowledge/add仅管理员可用。

GET /products/{id}/reviews匿名可读；POST同路径由登录用户提交rating（1–5）与content。评论立即公开，无审核等待。GET /admin/reviews读取评论，PUT /admin/reviews/{id}仅提交reply，不提供审核功能。

## 衣囊、订单与客服

- GET /cart、POST /cart、PUT /cart/{lineId}、DELETE /cart/{lineId}管理本人衣囊，需要sessionId。直接POST请求体为productId、skuId、quantity；同SKU覆盖数量。上述选购confirm接口则以独立幂等记录累加本次数量。
- POST /orders/preview返回本人衣囊预览；POST /orders请求recipient、phone、region、address、idempotencyKey。订单以账号和幂等键去重，事务核查库存。GET /orders、/orders/{id}读取本人订单。
- POST /orders/{id}/pay接收paymentMethod（ALIPAY/WECHAT/BALANCE），仅模拟支付，状态DEMO_PAID，不真实扣款。POST /orders/{id}/cancel取消未支付订单并恢复库存。
- GET /orders/{id}/shipment读取本人订单物流状态。
- GET/POST /support/tickets读取或提交本人客服留言；GET /support/tickets/{id}/reply读取回复。这是独立于AI接待的客服入口。

## 独立管理后台

GET /admin/access检查当前权限；POST /admin/setup仅用于首次本地令牌授权。已有管理员不重置。

GET /admin/summary、/admin/customers、/admin/orders、/admin/audit读取经营记录；POST /admin/orders/{id}/ship提交物流信息。GET /admin/support和PUT /admin/support/{id}用于客服回复。

GET/POST /admin/products及PUT /admin/products/{id}管理商品、SKU价格库存、图片链接与史料关联。写入须校验revision。DELETE /admin/products/{id}?revision=...仅允许草稿或已下架商品，界面二次确认，软删除保留历史关联。

GET /admin/articles、PUT /admin/articles/{id}维护知识条目，FACT必须附HTTPS来源；POST /admin/media使用multipart file上传商品图片。图片文件存本地媒体目录，SQL存图片URL，不存Base64。

## 资料、试穿和Trace

GET/PUT/DELETE /user/body-profile按账号管理本地身材资料；POST /products/{productId}/size-advice进行本地尺码建议。导购文本不接受身材、人像或联系方式，原始身材不发送给在线导购模型。

GET /tryon/status读取供应商配置状态。POST /tryon/portrait以multipart上传file，包含sessionId、authorized、aiAuthorized，需用户明确授权图片换装。POST /tryon/generate请求sessionId、portraitId、productId、skuId。

GET /tryon/orbit/status读取腾讯混元3D配置状态。POST /tryon/tasks/{tryOnId}/orbit?sessionId=...请求体为 {"authorized":true,"retryOf":null}，只接受已完成、未过期的本人换装任务。同一源任务重复提交返回现有任务；显式重试失败/已取消任务时retryOf填写旧3D任务ID。GET同路径读取最新3D任务（无任务为null）。GET /tryon/orbit/tasks/{orbitId}轮询，POST /tryon/orbit/tasks/{orbitId}/cancel取消本站任务，GET /tryon/orbit/tasks/{orbitId}/model读取GLB。均要求登录，写入要求CSRF。状态QUEUED/RUNNING/DONE/FAILED/CANCELLED，删除原人像同步删除关联模型。详见docs/hunyuan-3d.md。

GET /tryon/tasks/{id}读取QUEUED/RUNNING/DONE/FAILED/CANCELLED状态；GET /tryon/tasks/{id}/result或/tryon/result/{id}读取结果；POST /tryon/tasks/{id}/cancel取消。GET /tryon/portrait/{id}/image、DELETE /tryon/portrait/{id}访问或删除本人原图。图片换装使用实际供应商调用；失败不返回示意图冒充结果。详见docs/volcengine-tryon.md。

GET /trace/events、/trace/stream、/trace/stream/{sessionId}读取当前账号会话事件；DELETE /trace/events清理会话Trace。Trace只含工具名、计数、安全摘要，无原始聊天或凭据，进程重启会丢失。SQL接待记录独立保存。GET /ops/summary是当前会话事件计数；旧POST /ops/handoff只记事件，实际商家待办使用/retail/runs/{id}/help。

## 验证说明

自动化协议桩用于验证程序边界，不代表实际供应商效果。scripts/evaluate-retail.mjs默认只列出30个构造用例；加--online --limit=3才真实调用模型。实际报告保存output/evaluation。参赛范围、企业访谈和经营成效见docs/competition-readiness.md，不把构造测试解释为客户试点。
