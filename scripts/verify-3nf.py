"""Read-only comparison of live 3NF data to the migration backup and both web ports."""
from pathlib import Path
from decimal import Decimal
import base64, hashlib, http.cookiejar, json, sys, urllib.request, urllib.error
import pymysql
sys.stdout.reconfigure(encoding='utf-8')
cfg=dict(line.split('=',1) for line in Path('.local/database.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
db=pymysql.connect(host='localhost',port=3306,user=cfg['spring.datasource.username'],password=cfg['spring.datasource.password'],database='hanyunjing',charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor)
backup=json.loads(Path('.local/hanyunjing-before-3nf.json').read_text(encoding='utf-8'))['tables']
with db.cursor() as cur:
    cur.execute("SELECT COUNT(*) AS n FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name NOT LIKE 'legacy_v2_%' AND (column_name LIKE '%_json' OR data_type='json')")
    assert cur.fetchone()['n']==0
    for sku in backup['product_sku']['rows']:
        cur.execute('SELECT price,stock FROM product_sku WHERE id=%s',(sku['id'],));row=cur.fetchone()
        assert row['price']==Decimal(str(sku['price'])) and row['stock']==sku['stock'],sku['id']
    for order in backup['shop_order']['rows']:
        cur.execute('SELECT SUM(unit_price*quantity) AS total FROM order_item WHERE order_id=%s',(order['id'],))
        assert cur.fetchone()['total']==Decimal(str(order['total']))
    for line in backup['order_item']['rows']:
        cur.execute('SELECT * FROM order_item WHERE id=%s',(line['id'],));row=cur.fetchone()
        assert (row['product_name_at_purchase'],row['color_at_purchase'],row['size_at_purchase'])==(line['product_name'],line['color'],line['size_label'])
    cur.execute("SELECT salt,password_hash,iterations FROM customer_account WHERE username='admin' AND role='ADMIN'");admin=cur.fetchone();assert admin
    digest=hashlib.pbkdf2_hmac('sha256',b'admin123',base64.b64decode(admin['salt']),admin['iterations'],32)
    assert base64.b64encode(digest).decode()==admin['password_hash']
    cur.execute('SELECT COUNT(*) AS n FROM knowledge_article WHERE kind<>%s',('FACT',));assert cur.fetchone()['n']==0
    cur.execute('SELECT COUNT(*) AS n FROM product_image');assert cur.fetchone()['n']==25
    cur.execute('SELECT COUNT(*) AS n FROM product_size');assert cur.fetchone()['n']==104
    cur.execute("SELECT COUNT(*) AS n FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name NOT LIKE 'legacy_v2_%'")
    print('已核验 SQL 外键',cur.fetchone()['n'],'项；价格、库存、历史订单金额及成交描述与备份一致。')
db.close()
jar=http.cookiejar.CookieJar();client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def call(port,path,method='GET',body=None,csrf=None,admin=True):
    headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{port}'}
    if admin:headers['X-HYJ-Client']='admin'
    if csrf:headers['X-CSRF-Token']=csrf
    req=urllib.request.Request(f'http://127.0.0.1:{port}/api'+path,None if body is None else json.dumps(body).encode(),headers,method=method)
    with client.open(req,timeout=15) as r:
        result=json.load(r);assert result['code']==0,result;return result['data']
anon=call(5174,'/auth/session')
session=call(5174,'/auth/login','POST',{'username':'admin','password':'admin123'},anon['csrfToken']);assert session['authenticated']
assert any(c.name=='HYJ_ADMIN_SESSION' for c in jar)
assert not call(5173,'/auth/session',admin=False)['authenticated']
assert call(5174,'/auth/session')['authenticated']
assert call(5174,'/admin/access')['admin']
for path in ['/admin','/admin/index.html','/dist-admin/index.html','/api/admin/summary','/admin/AdminView.vue']:
    try:
        client.open('http://127.0.0.1:5173'+path,timeout=10)
        raise AssertionError('商城端暴露后台路径：'+path)
    except urllib.error.HTTPError as e:assert e.code==404,(path,e.code)
groups={'汉':['p11','p12','p15','p16'],'唐':['p1','p2','p7','p10'],'宋':['p3','p18','p19','p20'],'元':['p13','p21','p22','p23'],'明':['p8','p24','p25','p26']}
products={p['id']:p for p in call(5173,'/products?sessionId=acceptance',admin=False)}
for expected in json.loads(Path('src/main/resources/catalogue-v4.json').read_text(encoding='utf-8'))['products']:
    assert products[expected['id']]['name']==expected['name'],expected['id']+' catalog revision missing'
    assert products[expected['id']]['form']==expected['form']
for dynasty,ids in groups.items():
    assert len({products[id]['form'] for id in ids})==4
    for id in ids:
        assert products[id]['dynasty']==dynasty and products[id]['images']
        facts=call(5173,'/products/'+id+'/references',admin=False)['hits']
        assert facts and all(h['article']['kind']=='FACT' and 'https://' in h['article']['source'] for h in facts)
    print(dynasty+'：'+'、'.join(products[id]['form'] for id in ids))
call(5174,'/auth/logout','POST',{},session['csrfToken'])
for port in [5173,5174]:
    with client.open(f'http://127.0.0.1:{port}/',timeout=10) as r:assert r.status==200
print('两端入口、后台账号、会话隔离、商城后台路径拦截、每朝四款与逐款史实验证通过。')
