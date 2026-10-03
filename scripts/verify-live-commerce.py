"""Read-only checks against the configured MySQL and live app. Never print credentials."""
import json
from pathlib import Path
import urllib.request, urllib.error, http.cookiejar
import pymysql

cfg=dict(line.split('=',1) for line in Path('.local/database.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
connection=pymysql.connect(host='localhost',port=3306,user=cfg['spring.datasource.username'],password=cfg['spring.datasource.password'],database='hanyunjing',charset='utf8mb4')
with connection.cursor() as cursor:
    for table in ['customer_account','product','product_sku','knowledge_article','product_knowledge','shop_order','order_item','support_ticket','product_review']:
        cursor.execute('SELECT COUNT(*) FROM '+table)
        print(table, cursor.fetchone()[0])
    legacy=json.loads(Path('.local/account-store.json').read_text(encoding='utf-8'))
    for account in legacy['accounts']:
        cursor.execute('SELECT COUNT(*) FROM customer_account WHERE id=%s AND password_hash=%s',(account['id'],account['passwordHash']))
        assert cursor.fetchone()[0]==1, 'Legacy account was not migrated'
    for order in legacy['orders']:
        cursor.execute('SELECT COUNT(*) FROM shop_order WHERE id=%s',(order['order']['id'],))
        assert cursor.fetchone()[0]==1, 'Legacy order was not migrated'
    print('All legacy accounts/password hashes and orders verified:',len(legacy['accounts']),len(legacy['orders']))
connection.close()

credentials=Path('.local/admin-credentials.txt').read_text(encoding='utf-8').splitlines()
username=next(x.split('：',1)[1] for x in credentials if x.startswith('用户名：'))
password=next(x.split('：',1)[1] for x in credentials if x.startswith('密码：'))
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
base='http://127.0.0.1:5174/api'
def request(path,body=None,csrf=None):
    headers={'Content-Type':'application/json','X-HYJ-Client':'admin','Origin':'http://127.0.0.1:5174'}
    if csrf: headers['X-CSRF-Token']=csrf
    req=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),headers)
    with client.open(req,timeout=15) as response:
        result=json.load(response)
        assert result['code']==0
        return result['data']
csrf=request('/auth/session')['csrfToken']
session=request('/auth/login',{'username':username,'password':password},csrf)
assert session['authenticated']
assert request('/admin/access')['admin']
for endpoint in ['/admin/products','/admin/customers','/admin/articles','/admin/reviews','/admin/orders','/admin/summary','/admin/support','/admin/audit']:
    value=request(endpoint)
    print('HTTP 200',endpoint,'records/fields',len(value))
products=request('/products?sessionId=live-verify')
for dynasty in ['汉','唐','宋','元','明']:
    subset=[p for p in products if p['dynasty']==dynasty]
    assert len(subset)>=4
    print('Dynasty',{'汉':'Han','唐':'Tang','宋':'Song','元':'Yuan','明':'Ming'}[dynasty],'products',len(subset))
for p in products:
    assert p['images'],p['id']+' missing image'
    with client.open('http://127.0.0.1:5173'+p['images'][0]) as response:
        assert response.status==200 and len(response.read())>1024
    assert request('/products/'+p['id']+'/references')['hits']
print('All published images and linked references return HTTP 200.')
request('/auth/logout',{},session['csrfToken'])
