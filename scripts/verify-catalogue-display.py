"""Verify live public/admin catalogue parity without changing business records."""
import argparse
import copy
import http.cookiejar
import json
from pathlib import Path
import urllib.request
import urllib.error
from collections import Counter

args = argparse.ArgumentParser()
args.add_argument('--snapshot', action='store_true')
args = args.parse_args()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
base = 'http://127.0.0.1:5174/api'

def request(path, body=None, csrf=None):
    headers = {'Content-Type': 'application/json', 'X-HYJ-Client': 'admin', 'Origin': 'http://127.0.0.1:5174'}
    if csrf: headers['X-CSRF-Token'] = csrf
    req = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(), headers)
    with client.open(req, timeout=20) as response:
        result = json.load(response)
        assert result['code'] == 0, path
        return result['data']

credentials = Path('.local/admin-credentials.txt').read_text(encoding='utf-8').splitlines()
username = next(x.split('：', 1)[1] for x in credentials if x.startswith('用户名：'))
password = next(x.split('：', 1)[1] for x in credentials if x.startswith('密码：'))
session = request('/auth/session')
session = request('/auth/login', {'username': username, 'password': password}, session['csrfToken'])
try:
    records = request('/admin/products')
    public = request('/products?sessionId=catalogue-display-check')
    active = {r['product']['id']: r['product'] for r in records if r['status'] == 'ACTIVE'}
    assert {p['id'] for p in public} == set(active), 'Public/admin ACTIVE product IDs differ'
    for product in public:
        assert product == active[product['id']], 'Public/admin product data differs: ' + product['id']
        assert product['images'], 'Missing image: ' + product['id']
        for url in product['images']:
            with urllib.request.urlopen('http://127.0.0.1:5173' + url, timeout=20) as response:
                assert response.status == 200 and len(response.read()) > 1024, url
        assert request('/products/' + product['id'] + '/references')['hits'], 'Missing references: ' + product['id']
    for record in records:
        if record['status'] == 'ACTIVE': continue
        try:
            urllib.request.urlopen('http://127.0.0.1:5173/api/products/' + record['product']['id'] + '?sessionId=visibility-check', timeout=10)
            raise AssertionError('Unpublished product is publicly accessible')
        except urllib.error.HTTPError as error:
            assert error.code == 404, error.code
    print('Catalogue statuses:', dict(Counter(r['status'] for r in records)))
    print('Public dynasty counts:', dict(Counter(p['dynasty'] for p in public)))
    snapshot = Path('.local/catalogue-repair-before.json')
    if args.snapshot:
        assert not snapshot.exists(), 'Snapshot already exists; do not overwrite'
        snapshot.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Saved pre-repair product data snapshot.')
    elif snapshot.exists():
        previous = json.loads(snapshot.read_text(encoding='utf-8'))
        def business_state(values):
            clean = copy.deepcopy(values)
            for item in clean:
                item.pop('revision', None)
                if item['product']['id'] == 'p7': item['product'].pop('images', None)
            return sorted(clean, key=lambda r: r['product']['id'])
        assert business_state(previous) == business_state(records), 'Unexpected business data change since snapshot'
        print('Names, statuses, SKUs, prices, stock, sizes, and history associations preserved.')
    print('PASS: all published products, images, history links and visibility rules agree.')
finally:
    request('/auth/logout', {}, session['csrfToken'])
