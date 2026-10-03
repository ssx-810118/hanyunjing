# 汉韵镜：独立管理端与第三范式数据库

更新日期：2026-10-03。使用本机 MySQL 的 hanyunjing 数据库；原注册账号与订单已保留。

## 打开页面

- 商城：http://127.0.0.1:5173/
- 可视化管理后台：http://127.0.0.1:5174/
- 后端 API：8082 端口。
- 当前指定管理员：admin；密码：admin123。数据库仅保存随机盐和 PBKDF2-HMAC-SHA256 密码哈希（600000 次迭代）。MySQL 密码和网站管理员密码分别配置。

商城不包含后台导航、链接或后台路由；开发服务器拦截 /admin、管理应用源文件路径和 /api/admin/。两个页面分别构建为 frontend/dist 和 frontend/dist-admin。后台使用独立 Cookie HYJ_ADMIN_SESSION，商城使用 HYJ_SESSION；服务端将会话绑定到对应客户端，两个页面登录或退出互不替换。

管理端保留商品与库存、注册用户、衣冠史料、评论回复、订单与发货、客服留言、操作记录七个可视化模块。商品编辑包括图像上传、SKU 价格与库存、款式、颜色、尺码、尺码表、状态和史料关联。已移除“穿搭建议”分类和对应历史数据。

## 启动

在项目根目录执行：

~~~powershell
mvn package
java -jar target/hanyunjing-0.0.1-SNAPSHOT.jar
~~~

另外打开两个终端：

~~~powershell
npm --prefix frontend run dev
npm --prefix frontend run dev:admin
~~~

也可运行 scripts/start-project.ps1；脚本只在端口空闲时启动服务。网站端口固定为 5173 / 5174，不会自动换到其他端口。

MySQL 连接位于 .local/database.properties（已被版本控制排除）；模板为 docs/database.properties.example。不设置 MySQL 时可使用应用内文件型 H2 SQL 数据库。连接已指定的 MySQL 失败时不会悄悄回退到另一个库。

## 21 张关系表

| 分组 | 表 | 保存内容 |
|---|---|---|
| 注册 | customer_account | 用户名、昵称、注册时间、角色、加盐密码哈希 |
| 商品 | product | 名称、分类、朝代、形制、说明、状态、修订版本 |
| 商品 | product_image | 每件商品的独立图片 URL 及显示顺序 |
| 商品 | product_size | 每件商品各尺码的身高、胸围、腰围、臀围区间 |
| 商品 | product_sku | 颜色、尺码、价格、库存；引用对应商品尺码 |
| 商品 | product_tag / product_scene / product_accessory | 标签、场景代码、搭配商品关系 |
| 史料 | knowledge_article | 史料正文、性质、主题及断言字段 |
| 史料 | historical_source | 文献或馆方页面的出处名称与原文 URL |
| 史料 | article_source / article_keyword | 多来源关联及独立检索词 |
| 史料 | product_knowledge | 商品与具体史料的多对多关联 |
| 交易 | cart_item | 账号、SKU 和数量 |
| 交易 | shop_order | 账号、订单号、状态、收货资料、支付时间和物流 |
| 交易 | order_item | 下单时商品名称、颜色、尺码、成交单价与数量 |
| 服务 | product_review | 评论、评分及商家回复 |
| 服务 | support_ticket | 问题分类、内容、商品/订单关联及客服回复 |
| 审计 | admin_audit | 操作者、操作、对象、时间和摘要 |
| 系统 | app_migration / app_lock | 一次性迁移标记与初始化锁 |

图片二进制保存在项目静态资源及 .local/media；数据库存放对应 URL，不重复保存大块二进制或 JSON。Navicat 可直接查看 product_image，与 product 通过 product_id 关联。

## 第三范式说明

1. 列表型属性拆成关系行；商品、史料、订单、客服表不再保留 details_json 或 snapshot_json 镜像。
2. 商品图片、尺码、标签和关联表按其完整键保存属性。SKU 的颜色/尺码/价格/库存仅在 product_sku 中维护；商品详情中的 colors 从 SKU 派生。
3. 购物车和订单明细不再冗余保存可由 SKU 推导的当前 product_id；商品名通过查询关联得到。当前总价和订单行小计不落库，统一由数量乘成交单价计算。
4. order_item 的 *_at_purchase 是成交当时值，和当前商品名称/颜色/尺码不是同一属性。修改商品不改写历史订单；这不是复制当前商品数据的传递依赖。
5. 出处实体独立于史料；一篇史料可有多个来源，多篇史料可关联同一来源。外键、唯一键和约束防止孤立记录、重复 SKU、负库存和无效价格。

完整 SQL 定义在 src/main/resources/db/schema.sql；可读查询在 docs/catalogue-queries.sql。

## 迁移、备份与一致性

scripts/migrate-3nf.py 已先保存 .local/hanyunjing-before-3nf.json（旧建表语句及完整数据），再创建影子表、导入并核对账号数量、订单数量、明细与订单金额，通过原子 RENAME TABLE 切换。旧表仅在验收后清理。备份含账号哈希和私人收货信息，应只保存在本机。

当前 SQL 是账号、商品、购物车、订单、评论、史料和客服数据源。旧 .local/account-store.json 和 support-store.json 仅为历史备份，迁移标记保证不重复导入。

库存扣减、订单创建、购物车清空使用同一事务。并发锁防止超卖，取消订单仅回补一次库存；后台修订版本可阻止旧表单覆盖新库存。商品史料修订不会重置售价与库存。

当前保留3个原注册账号、1个管理员、26件商品（25件上架、1件草稿）、104个SKU、7笔订单、10条订单明细、2条客服留言。评论为0表示尚无评论，没有插入虚假评论。

问衣会话、身体资料、人像和试穿任务仍沿用临时运行态；未延长人像留存时间。支付沿用项目原有模拟流程，物流登记不调用快递下单。

## 验证

后端测试覆盖四种不同形制、逐款史料、规范化存储、账号迁移、历史价格、并发库存、取消回补、过期保存、评论回复、管理员角色、CSRF 和两端会话隔离。scripts/verify-live-commerce.py 验证真实 MySQL、旧密码哈希、订单、后台各接口、商品图和出处。

构建命令为 npm --prefix frontend run build 和 npm --prefix frontend run build:admin。浏览器控制入口未返回有效结果，接口验收与构建成功不等于完整自动化浏览器交互验收。

## 零售 Agent 工作流扩展

新增六张关联表：agent_run、agent_candidate、agent_evidence、agent_step、agent_selection、agent_case。保存需求约束、候选报价、查询时史料快照、执行步骤、确认操作和商家待办。评测记录标为EVALUATION，排除在用户接待统计外。详细行为见README和competition-readiness.md。前文商品及订单数量是历史验收快照，以当前SQL查询为准。
