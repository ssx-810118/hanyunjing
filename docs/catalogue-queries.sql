-- 在 Navicat 中打开 hanyunjing 数据库后执行。
USE hanyunjing;

-- 注册信息（不查询密码哈希）
SELECT id AS 账号编号, username AS 用户名, display_name AS 昵称,
       role AS 角色, created_at AS 注册时间
FROM customer_account ORDER BY created_at DESC;

-- 衣服、价格、库存及款式：每行是一个颜色/尺码规格
SELECT p.id AS 商品编号,p.name AS 衣服名称,p.dynasty AS 朝代,p.form AS 衣服款式,
       p.status AS 上架状态,s.id AS 规格编号,s.color AS 颜色,s.size_label AS 尺码,
       s.price AS 单价元,s.stock AS 库存件数
FROM product p JOIN product_sku s ON s.product_id=p.id
ORDER BY p.dynasty,p.id,s.sort_order;

-- 每件商品的汇总价格与库存
SELECT p.id,p.name,p.dynasty,MIN(s.price) AS 起价,MAX(s.price) AS 最高规格价,
       SUM(s.stock) AS 总库存,COUNT(s.id) AS 规格数
FROM product p JOIN product_sku s ON s.product_id=p.id
GROUP BY p.id,p.name,p.dynasty ORDER BY p.dynasty,p.id;

-- 商品图片独立存表；每行一张图
SELECT p.id,p.name,i.sort_order AS 图片顺序,i.url AS 图片地址
FROM product p JOIN product_image i ON i.product_id=p.id ORDER BY p.id,i.sort_order;

-- 商品对应的历史资料与具体来源
SELECT p.name AS 衣服名称,a.title AS 资料标题,a.kind AS 资料性质,a.content AS 正文,
       s.citation AS 出处名称,s.url AS 原文地址
FROM product p JOIN product_knowledge pk ON pk.product_id=p.id
JOIN knowledge_article a ON a.id=pk.article_id
JOIN article_source ar ON ar.article_id=a.id JOIN historical_source s ON s.id=ar.source_id
ORDER BY p.id,a.id,ar.sort_order;

-- 订单与账号
SELECT o.order_number AS 订单号,a.username AS 用户名,o.status AS 订单状态,
       (SELECT SUM(i.quantity*i.unit_price) FROM order_item i WHERE i.order_id=o.id) AS 金额,
       o.fulfillment_status AS 发货状态,o.created_at AS 下单时间
FROM shop_order o JOIN customer_account a ON a.id=o.account_id
ORDER BY o.created_at DESC;
