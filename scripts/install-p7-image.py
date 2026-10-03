"""Install the approved p7 replacement through the existing admin API."""
import hashlib
import http.cookiejar
import json
from pathlib import Path
import shutil
import urllib.request
import uuid
from PIL import Image

source = Path('output/imagegen/catalogue-v5/p7-final.png')
assert source.is_file()
image_bytes = source.read_bytes()
assert len(image_bytes) < 5 * 1024 * 1024
record_path = Path('.local/p7-image-replacement-v5.json')
assert not record_path.exists(), 'Replacement already applied; inspect its receipt before retrying.'
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
base = 'http://127.0.0.1:5174/api'
def request(method, route, body=None, csrf=None, content_type='application/json'):
    headers = {'Content-Type': content_type, 'X-HYJ-Client': 'admin', 'Origin': 'http://127.0.0.1:5174'}
    if csrf: headers['X-CSRF-Token'] = csrf
    if body is not None and not isinstance(body, bytes): body = json.dumps(body).encode()
    with client.open(urllib.request.Request(base + route, body, headers, method=method), timeout=30) as response:
        result = json.load(response)
        assert result['code'] == 0, route
        return result['data']
credentials = Path('.local/admin-credentials.txt').read_text(encoding='utf-8').splitlines()
username = next(x.split('：', 1)[1] for x in credentials if x.startswith('用户名：'))
password = next(x.split('：', 1)[1] for x in credentials if x.startswith('密码：'))
session = request('GET', '/auth/session')
session = request('POST', '/auth/login', {'username': username, 'password': password}, session['csrfToken'])
try:
    current = next(r for r in request('GET', '/admin/products') if r['product']['id'] == 'p7')
    before = json.loads(json.dumps(current))
    boundary = 'HYJImage' + uuid.uuid4().hex
    body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="p7-final.png"\r\nContent-Type: image/png\r\n\r\n').encode() + image_bytes + ('\r\n--' + boundary + '--\r\n').encode()
    uploaded = request('POST', '/admin/media', body, session['csrfToken'], 'multipart/form-data; boundary=' + boundary)
    current['product']['images'] = [uploaded['url']] + current['product']['images'][1:]
    updated = request('PUT', '/admin/products/p7', current, session['csrfToken'])
    assert updated['product']['images'][0] == uploaded['url']
    record_path.write_text(json.dumps({'sourceSha256': hashlib.sha256(image_bytes).hexdigest(), 'before': before, 'after': updated}, ensure_ascii=False, indent=2), encoding='utf-8')
    for folder, suffix, options in [('products', '.webp', {'quality': 92, 'method': 6}), ('tryon-garments', '.jpg', {'quality': 94})]:
        target = Path('src/main/resources/static/images') / folder / ('p7' + suffix)
        backup = Path('output/imagegen/catalogue-v5/previous') / target.name
        backup.parent.mkdir(parents=True, exist_ok=True)
        assert not backup.exists(), 'Do not overwrite an existing asset backup'
        shutil.copy2(target, backup)
        with Image.open(source) as image:
            image.convert('RGB').resize((768, 1024), Image.Resampling.LANCZOS).save(target, **options)
        with Image.open(target) as image:
            assert image.size == (768, 1024)
            image.verify()
    with urllib.request.urlopen('http://127.0.0.1:5173' + uploaded['url'], timeout=20) as response:
        with Image.open(response) as image:
            assert image.size == (768, 1024)
    print('p7 updated through admin API; new URL is shared by storefront, admin and try-on.')
    print('Updated only p7 PNG reference, bundled WebP and try-on JPG; previous assets backed up.')
finally:
    request('POST', '/auth/logout', {}, session['csrfToken'])
