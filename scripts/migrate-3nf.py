"""Back up, populate shadow tables, validate and atomically switch to the 3NF schema.

Run while the application is stopped. --finalize drops only verified legacy_v2_
tables after application acceptance; --rollback reverses the switch before that.
The backup contains account hashes and private order data; keep it in .local.
"""
from pathlib import Path
import argparse, base64, hashlib, json, re, secrets, uuid
from datetime import datetime, timezone
from decimal import Decimal
import pymysql

ROOT=Path(__file__).resolve().parents[1]
cfg=dict(line.split('=',1) for line in (ROOT/'.local/database.properties').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
db=pymysql.connect(host='localhost',port=3306,user=cfg['spring.datasource.username'],password=cfg['spring.datasource.password'],database='hanyunjing',charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor)
schema=(ROOT/'src/main/resources/db/schema.sql').read_text(encoding='utf-8')
tables=re.findall(r'CREATE TABLE IF NOT EXISTS (\w+)',schema)
backup=ROOT/'.local/hanyunjing-before-3nf.json'
now=lambda:datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
parser=argparse.ArgumentParser();parser.add_argument('--finalize',action='store_true');parser.add_argument('--rollback',action='store_true');args=parser.parse_args()
def rows(table):
 with db.cursor() as cur:
  cur.execute('SELECT * FROM `'+table+'`');return cur.fetchall()
def insert(table,row):
 with db.cursor() as cur:
  cur.execute('SHOW COLUMNS FROM `n3_'+table+'`');cols={r['Field'] for r in cur.fetchall()}
  row={k:v for k,v in row.items() if k in cols}
  cur.execute('INSERT INTO `n3_'+table+'` ('+','.join('`'+k+'`' for k in row)+') VALUES ('+','.join(['%s']*len(row))+')',tuple(row.values()))
def count(table):
 with db.cursor() as cur:
  cur.execute('SELECT COUNT(*) AS n FROM `'+table+'`');return cur.fetchone()['n']
try:
 with db.cursor() as cur:
  cur.execute('SHOW TABLES');existing=[next(iter(r.values())) for r in cur.fetchall()]
  if args.finalize or args.rollback:
   saved=json.loads(backup.read_text(encoding='utf-8'));old=list(saved['tables'])
   assert all('legacy_v2_'+t in existing for t in old),'Legacy tables missing; stop for manual inspection.'
   if args.rollback:
    assert not any('n3_'+t in existing for t in tables)
    cur.execute('RENAME TABLE '+','.join('`'+t+'` TO `n3_'+t+'`' for t in tables)+','+','.join('`legacy_v2_'+t+'` TO `'+t+'`' for t in old))
    print('Rolled back; shadow tables retained for inspection.')
   else:
    assert count('customer_account')==len(saved['tables']['customer_account']['rows'])
    assert count('shop_order')==len(saved['tables']['shop_order']['rows'])
    assert count('order_item')==len(saved['tables']['order_item']['rows'])
    cur.execute('SET FOREIGN_KEY_CHECKS=0')
    for t in old:cur.execute('DROP TABLE `legacy_v2_'+t+'`')
    cur.execute('SET FOREIGN_KEY_CHECKS=1')
    print('Validated legacy tables removed; backup retained at',backup)
   raise SystemExit(0)
  assert not any(t.startswith(('n3_','legacy_v2_')) for t in existing),'A prior migration needs inspection.'
  cur.execute("SHOW COLUMNS FROM product LIKE 'details_json'")
  assert cur.fetchone(),'Already normalized; no migration performed.'
  assert not backup.exists(),'Backup already exists; do not overwrite it.'
  saved={'createdAt':now(),'tables':{}}
  for t in existing:
   cur.execute('SHOW CREATE TABLE `'+t+'`');ddl=cur.fetchone()['Create Table']
   saved['tables'][t]={'ddl':ddl,'rows':rows(t)}
  backup.write_text(json.dumps(saved,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
  assert json.loads(backup.read_text(encoding='utf-8'))['tables'].keys()==saved['tables'].keys()
  shadow=re.sub(r'\b('+ '|'.join(sorted(tables,key=len,reverse=True))+r')\b',lambda m:'n3_'+m.group(),schema)
  shadow=re.sub(r'--[^\n]*','',shadow)
  for statement in shadow.split(';'):
   if statement.strip():cur.execute(statement)
  data={k:v['rows'] for k,v in saved['tables'].items()}
  for t in ['app_migration','customer_account','product','app_lock']:
   for row in data[t]:insert(t,row)
  for row in data['product']:
   p=json.loads(row['details_json']);pid=p['id']
   for i,url in enumerate(p['images']):insert('product_image',dict(product_id=pid,sort_order=i,url=url))
   for tag in dict.fromkeys(p['tags']):insert('product_tag',dict(product_id=pid,tag=tag))
   for scene in dict.fromkeys(p['scenes']):insert('product_scene',dict(product_id=pid,scene_code=scene))
   for accessory in dict.fromkeys(p['accessoryIds']):insert('product_accessory',dict(product_id=pid,accessory_id=accessory))
   for i,s in enumerate(p['sizeChart']):
    values=dict(product_id=pid,size_label=s['size'],sort_order=i)
    for dim in ['height','chest','waist','hip']:
     for bound in ['min','max']:values[dim+'_'+bound]=s[dim][bound]
    insert('product_size',values)
  for row in data['product_sku']:insert('product_sku',row)
  sources=set();article_ids=set()
  for row in data['knowledge_article']:
   if row['kind']=='ADVICE' or row['id']=='a2':continue
   a=json.loads(row['details_json']);article_ids.add(a['id'])
   insert('knowledge_article',dict(id=a['id'],title=a['title'],kind=a['kind'],topic=a['topic'],content=a['content'],claim_key=a['claimKey'],claim_value=a['claimValue']))
   for word in dict.fromkeys(a['keywords']):insert('article_keyword',dict(article_id=a['id'],keyword=word))
   linked=set()
   for i,src in enumerate(a['source'].split('；')):
    match=re.search(r'https?://[^\s｜|；]+',src);url=match.group() if match else '';label=src.replace(url,'').rstrip('｜| ').strip()
    sid=str(uuid.UUID(bytes=hashlib.md5((label+'|'+url).encode()).digest(),version=3))
    if sid not in sources:insert('historical_source',dict(id=sid,citation=label,url=url));sources.add(sid)
    if sid not in linked:insert('article_source',dict(article_id=a['id'],source_id=sid,sort_order=i));linked.add(sid)
  for row in data['product_knowledge']:
   if row['article_id'] in article_ids:insert('product_knowledge',row)
  for row in data['shop_order']:
   o=json.loads(row['snapshot_json']);values=dict(row)
   for sql,key in [('session_id','sessionId'),('recipient','recipient'),('phone','phone'),('region','region'),('address','address'),('payment_method','paymentMethod'),('paid_at','paidAt'),('cancelled_at','cancelledAt')]:values[sql]=o.get(key)
   insert('shop_order',values)
  for row in data['order_item']:
   values=dict(row)
   for old,new in [('product_name','product_name_at_purchase'),('color','color_at_purchase'),('size_label','size_at_purchase')]:values[new]=row[old]
   insert('order_item',values)
  for t in ['cart_item','product_review','admin_audit']:
   for row in data[t]:insert(t,row)
  for row in data['support_ticket']:
   t=json.loads(row['snapshot_json']);values=dict(row)
   for sql,key in [('ticket_number','number'),('category','category'),('product_id','productId'),('order_id','orderId'),('message','message')]:values[sql]=t.get(key) or None
   insert('support_ticket',values)
  if not any(r['migration_key']=='legacy-orders-v3' for r in data['app_migration']):insert('app_migration',dict(migration_key='legacy-orders-v3',applied_at=now()))
  insert('app_migration',dict(migration_key='schema-3nf-v3',applied_at=now()))
  # Set precisely the administrator requested by the owner; never print credentials.
  cur.execute("SELECT id,username FROM n3_customer_account WHERE username IN ('admin','hyj_admin') ORDER BY CASE username WHEN 'admin' THEN 0 ELSE 1 END")
  account=cur.fetchone();assert account,'Expected existing local administrator.'
  salt=secrets.token_bytes(16);digest=hashlib.pbkdf2_hmac('sha256',b'admin123',salt,600000,32)
  cur.execute("UPDATE n3_customer_account SET username='admin',display_name='系统管理员',role='ADMIN',salt=%s,password_hash=%s,iterations=600000 WHERE id=%s",(base64.b64encode(salt).decode(),base64.b64encode(digest).decode(),account['id']))
  for t in ['customer_account','product','product_sku','shop_order','order_item','support_ticket','cart_item','product_review']:
   assert count('n3_'+t)==len(data[t]),t+' count changed'
  for order in data['shop_order']:
   cur.execute('SELECT SUM(quantity*unit_price) AS total FROM n3_order_item WHERE order_id=%s',(order['id'],))
   assert cur.fetchone()['total']==Decimal(str(order['total'])),'Order total changed'
  db.commit()
  cur.execute('RENAME TABLE '+','.join('`'+t+'` TO `legacy_v2_'+t+'`' for t in existing)+','+','.join('`n3_'+t+'` TO `'+t+'`' for t in tables))
  (ROOT/'.local/admin-credentials.txt').write_text('管理后台：http://127.0.0.1:5174\n用户名：admin\n密码：admin123\n',encoding='utf-8')
  print('3NF migration switched atomically:',len(tables),'tables. Accounts, orders, totals and inventories preserved.')
  print('Backup:',backup)
finally:db.close()
