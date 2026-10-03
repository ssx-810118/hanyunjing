-- 3NF schema, portable between H2 and MySQL 8.0.16+.
-- Atomic columns and relations; no JSON mirrors or stored totals.
CREATE TABLE IF NOT EXISTS app_migration (migration_key VARCHAR(100) PRIMARY KEY, applied_at VARCHAR(40) NOT NULL);
CREATE TABLE IF NOT EXISTS customer_account (
  id VARCHAR(64) PRIMARY KEY, username VARCHAR(32) NOT NULL UNIQUE,
  display_name VARCHAR(40) NOT NULL, salt VARCHAR(128) NOT NULL,
  password_hash VARCHAR(256) NOT NULL, iterations INT NOT NULL,
  created_at VARCHAR(40) NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER',
  CHECK (role IN ('CUSTOMER','ADMIN'))
);
CREATE TABLE IF NOT EXISTS product (
  id VARCHAR(64) PRIMARY KEY, name VARCHAR(120) NOT NULL, category VARCHAR(40) NOT NULL,
  dynasty VARCHAR(10) NOT NULL, form VARCHAR(80) NOT NULL, description TEXT NOT NULL,
  status VARCHAR(16) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
  created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL, deleted_at VARCHAR(40),
  CHECK (status IN ('ACTIVE','DRAFT','ARCHIVED'))
);
CREATE TABLE IF NOT EXISTS product_image (
  product_id VARCHAR(64) NOT NULL, sort_order INT NOT NULL, url VARCHAR(500) NOT NULL,
  PRIMARY KEY(product_id,sort_order), UNIQUE(product_id,url), FOREIGN KEY(product_id) REFERENCES product(id)
);
CREATE TABLE IF NOT EXISTS product_tag (
  product_id VARCHAR(64) NOT NULL, tag VARCHAR(100) NOT NULL,
  PRIMARY KEY(product_id,tag), FOREIGN KEY(product_id) REFERENCES product(id)
);
CREATE TABLE IF NOT EXISTS product_scene (
  product_id VARCHAR(64) NOT NULL, scene_code VARCHAR(80) NOT NULL,
  PRIMARY KEY(product_id,scene_code), FOREIGN KEY(product_id) REFERENCES product(id)
);
CREATE TABLE IF NOT EXISTS product_accessory (
  product_id VARCHAR(64) NOT NULL, accessory_id VARCHAR(64) NOT NULL,
  PRIMARY KEY(product_id,accessory_id), FOREIGN KEY(product_id) REFERENCES product(id),
  FOREIGN KEY(accessory_id) REFERENCES product(id), CHECK(product_id <> accessory_id)
);
CREATE TABLE IF NOT EXISTS product_size (
  product_id VARCHAR(64) NOT NULL, size_label VARCHAR(20) NOT NULL, sort_order INT NOT NULL,
  height_min DOUBLE PRECISION NOT NULL, height_max DOUBLE PRECISION NOT NULL,
  chest_min DOUBLE PRECISION NOT NULL, chest_max DOUBLE PRECISION NOT NULL,
  waist_min DOUBLE PRECISION NOT NULL, waist_max DOUBLE PRECISION NOT NULL,
  hip_min DOUBLE PRECISION NOT NULL, hip_max DOUBLE PRECISION NOT NULL,
  PRIMARY KEY(product_id,size_label), FOREIGN KEY(product_id) REFERENCES product(id),
  CHECK(height_min <= height_max AND chest_min <= chest_max AND waist_min <= waist_max AND hip_min <= hip_max)
);
CREATE TABLE IF NOT EXISTS product_sku (
  id VARCHAR(100) PRIMARY KEY, product_id VARCHAR(64) NOT NULL,
  color VARCHAR(40) NOT NULL, size_label VARCHAR(20) NOT NULL,
  price DECIMAL(12,2) NOT NULL, stock INT NOT NULL, sort_order INT NOT NULL,
  FOREIGN KEY(product_id,size_label) REFERENCES product_size(product_id,size_label),
  UNIQUE(product_id,color,size_label), CHECK(price > 0), CHECK(stock >= 0)
);
CREATE TABLE IF NOT EXISTS knowledge_article (
  id VARCHAR(64) PRIMARY KEY, title VARCHAR(120) NOT NULL, kind VARCHAR(16) NOT NULL,
  topic VARCHAR(120) NOT NULL, content TEXT NOT NULL,
  claim_key VARCHAR(150) NOT NULL, claim_value VARCHAR(300) NOT NULL,
  CHECK(kind IN ('FACT','COMMON'))
);
CREATE TABLE IF NOT EXISTS article_keyword (
  article_id VARCHAR(64) NOT NULL, keyword VARCHAR(100) NOT NULL,
  PRIMARY KEY(article_id,keyword), FOREIGN KEY(article_id) REFERENCES knowledge_article(id)
);
CREATE TABLE IF NOT EXISTS historical_source (
  id VARCHAR(64) PRIMARY KEY, citation VARCHAR(500) NOT NULL, url VARCHAR(700) NOT NULL
);
CREATE TABLE IF NOT EXISTS article_source (
  article_id VARCHAR(64) NOT NULL, source_id VARCHAR(64) NOT NULL, sort_order INT NOT NULL,
  PRIMARY KEY(article_id,source_id), UNIQUE(article_id,sort_order),
  FOREIGN KEY(article_id) REFERENCES knowledge_article(id), FOREIGN KEY(source_id) REFERENCES historical_source(id)
);
CREATE TABLE IF NOT EXISTS product_knowledge (
  product_id VARCHAR(64) NOT NULL, article_id VARCHAR(64) NOT NULL,
  PRIMARY KEY(product_id,article_id),
  FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(article_id) REFERENCES knowledge_article(id)
);
CREATE TABLE IF NOT EXISTS cart_item (
  id VARCHAR(64) PRIMARY KEY, account_id VARCHAR(64) NOT NULL,
  sku_id VARCHAR(100) NOT NULL, quantity INT NOT NULL,
  UNIQUE(account_id,sku_id), FOREIGN KEY(account_id) REFERENCES customer_account(id),
  FOREIGN KEY(sku_id) REFERENCES product_sku(id), CHECK(quantity BETWEEN 1 AND 99)
);
CREATE TABLE IF NOT EXISTS shop_order (
  id VARCHAR(64) PRIMARY KEY, account_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
  order_number VARCHAR(64) NOT NULL UNIQUE, status VARCHAR(30) NOT NULL, session_id VARCHAR(100) NOT NULL,
  created_at VARCHAR(40) NOT NULL, recipient VARCHAR(40) NOT NULL, phone VARCHAR(24) NOT NULL,
  region VARCHAR(100) NOT NULL, address VARCHAR(200) NOT NULL,
  payment_method VARCHAR(16), paid_at VARCHAR(40), cancelled_at VARCHAR(40),
  fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'UNSHIPPED',
  carrier VARCHAR(80), tracking_number VARCHAR(100), shipped_at VARCHAR(40),
  UNIQUE(account_id,idempotency_key), FOREIGN KEY(account_id) REFERENCES customer_account(id),
  CHECK(status IN ('PENDING_PAYMENT','DEMO_PAID','CANCELLED'))
);
-- Descriptions below are immutable purchase-time values, not current SKU attributes.
CREATE TABLE IF NOT EXISTS order_item (
  id VARCHAR(64) PRIMARY KEY, order_id VARCHAR(64) NOT NULL, sku_id VARCHAR(100) NOT NULL,
  product_name_at_purchase VARCHAR(120) NOT NULL, color_at_purchase VARCHAR(40) NOT NULL,
  size_at_purchase VARCHAR(20) NOT NULL, quantity INT NOT NULL, unit_price DECIMAL(12,2) NOT NULL,
  FOREIGN KEY(order_id) REFERENCES shop_order(id), FOREIGN KEY(sku_id) REFERENCES product_sku(id),
  CHECK(quantity > 0), CHECK(unit_price > 0)
);
CREATE TABLE IF NOT EXISTS product_review (
  id VARCHAR(64) PRIMARY KEY, product_id VARCHAR(64) NOT NULL, account_id VARCHAR(64) NOT NULL,
  rating INT NOT NULL, content VARCHAR(2000) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
  reply VARCHAR(2000) NOT NULL DEFAULT '', moderation_note VARCHAR(500) NOT NULL DEFAULT '',
  created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
  UNIQUE(product_id,account_id), FOREIGN KEY(product_id) REFERENCES product(id),
  FOREIGN KEY(account_id) REFERENCES customer_account(id), CHECK(rating BETWEEN 1 AND 5),
  CHECK(status IN ('PENDING','APPROVED','REJECTED'))
);
CREATE TABLE IF NOT EXISTS support_ticket (
  id VARCHAR(64) PRIMARY KEY, account_id VARCHAR(64) NOT NULL, client_id VARCHAR(64) NOT NULL,
  ticket_number VARCHAR(30) NOT NULL UNIQUE, category VARCHAR(40) NOT NULL,
  product_id VARCHAR(64), order_id VARCHAR(64), message VARCHAR(1000) NOT NULL,
  status VARCHAR(16) NOT NULL, reply VARCHAR(2000) NOT NULL DEFAULT '', created_at VARCHAR(40) NOT NULL,
  UNIQUE(account_id,client_id), FOREIGN KEY(account_id) REFERENCES customer_account(id),
  FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(order_id) REFERENCES shop_order(id)
);
CREATE TABLE IF NOT EXISTS admin_audit (
  id VARCHAR(64) PRIMARY KEY, account_id VARCHAR(64) NOT NULL, action VARCHAR(40) NOT NULL,
  target_id VARCHAR(100) NOT NULL, summary VARCHAR(1000) NOT NULL, created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(account_id) REFERENCES customer_account(id)
);
CREATE TABLE IF NOT EXISTS app_lock (lock_name VARCHAR(40) PRIMARY KEY);

-- Retail agent workflow. Prices and citations are evidence at decision time.
CREATE TABLE IF NOT EXISTS agent_run (
  id VARCHAR(64) PRIMARY KEY, account_id VARCHAR(64) NOT NULL, session_key VARCHAR(100) NOT NULL,
  origin VARCHAR(16) NOT NULL DEFAULT 'LIVE', CHECK(origin IN ('LIVE','EVALUATION')),
  status VARCHAR(24) NOT NULL, summary VARCHAR(1000) NOT NULL DEFAULT '',
  budget DECIMAL(12,2), size_label VARCHAR(20), color_label VARCHAR(20), quantity INT NOT NULL,
  scene VARCHAR(80), dynasty VARCHAR(20), created_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40),
  elapsed_ms BIGINT, model_calls INT NOT NULL DEFAULT 0, tool_calls INT NOT NULL DEFAULT 0,
  input_tokens BIGINT, output_tokens BIGINT,
  FOREIGN KEY(account_id) REFERENCES customer_account(id), CHECK(quantity BETWEEN 1 AND 99)
);
CREATE TABLE IF NOT EXISTS agent_candidate (
  run_id VARCHAR(64) NOT NULL, sku_id VARCHAR(100) NOT NULL, quoted_price DECIMAL(12,2) NOT NULL,
  PRIMARY KEY(run_id,sku_id), FOREIGN KEY(run_id) REFERENCES agent_run(id), FOREIGN KEY(sku_id) REFERENCES product_sku(id)
);
CREATE TABLE IF NOT EXISTS agent_evidence (
  run_id VARCHAR(64) NOT NULL, product_id VARCHAR(64) NOT NULL, article_id VARCHAR(64) NOT NULL,
  title_at_query VARCHAR(120) NOT NULL, excerpt_at_query TEXT NOT NULL, source_at_query TEXT NOT NULL,
  PRIMARY KEY(run_id,product_id,article_id), FOREIGN KEY(run_id) REFERENCES agent_run(id),
  FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(article_id) REFERENCES knowledge_article(id)
);
CREATE TABLE IF NOT EXISTS agent_step (
  run_id VARCHAR(64) NOT NULL, step_number INT NOT NULL, kind VARCHAR(30) NOT NULL,
  detail VARCHAR(500) NOT NULL, elapsed_ms BIGINT NOT NULL,
  PRIMARY KEY(run_id,step_number), FOREIGN KEY(run_id) REFERENCES agent_run(id)
);
CREATE TABLE IF NOT EXISTS agent_selection (
  run_id VARCHAR(64) PRIMARY KEY, sku_id VARCHAR(100) NOT NULL, confirmed_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(run_id,sku_id) REFERENCES agent_candidate(run_id,sku_id)
);
CREATE TABLE IF NOT EXISTS agent_case (
  run_id VARCHAR(64) PRIMARY KEY, state VARCHAR(20) NOT NULL, reason VARCHAR(500) NOT NULL,
  reply VARCHAR(2000) NOT NULL DEFAULT '', assigned_to VARCHAR(64), created_at VARCHAR(40) NOT NULL, resolved_at VARCHAR(40),
  FOREIGN KEY(run_id) REFERENCES agent_run(id), FOREIGN KEY(assigned_to) REFERENCES customer_account(id),
  CHECK(state IN ('OPEN','RESOLVED'))
);
