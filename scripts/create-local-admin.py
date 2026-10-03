"""Create a dedicated initial administrator, saving its random password only locally."""
from pathlib import Path
import base64, hashlib, secrets, uuid
from datetime import datetime, timezone
import pymysql

cfg = dict(line.split('=',1) for line in Path('.local/database.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
db = pymysql.connect(host='localhost',port=3306,user=cfg['spring.datasource.username'],password=cfg['spring.datasource.password'],database='hanyunjing',charset='utf8mb4')
try:
    with db.cursor() as cur:
        cur.execute("SELECT lock_name FROM app_lock WHERE lock_name='admin-setup' FOR UPDATE")
        cur.execute("SELECT COUNT(*) FROM customer_account WHERE role='ADMIN'")
        if cur.fetchone()[0]:
            print('An administrator already exists; credentials unchanged.')
        else:
            username='hyj_admin'
            cur.execute('SELECT COUNT(*) FROM customer_account WHERE username=%s',(username,))
            if cur.fetchone()[0]: username='hyj_admin_'+secrets.token_hex(3)
            password=secrets.token_urlsafe(18)
            salt=secrets.token_bytes(16)
            digest=hashlib.pbkdf2_hmac('sha256',password.encode(),salt,600000,32)
            account='u_'+uuid.uuid4().hex
            now=datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
            cur.execute('INSERT INTO customer_account(id,username,display_name,salt,password_hash,iterations,created_at,role) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)',(account,username,'汉韵镜掌柜',base64.b64encode(salt).decode(),base64.b64encode(digest).decode(),600000,now,'ADMIN'))
            cur.execute('INSERT INTO admin_audit VALUES (%s,%s,%s,%s,%s,%s)',(str(uuid.uuid4()),account,'ADMIN_SETUP',account,'本地初始化独立管理员',now))
            Path('.local/admin-credentials.txt').write_text('管理后台：http://127.0.0.1:5173/admin\n用户名：'+username+'\n密码：'+password+'\n\n此文件仅保存在本机，不要提交到版本库。\n',encoding='utf-8')
            db.commit()
            Path('.local/admin-setup-token.txt').unlink(missing_ok=True)
            print('Administrator created; login saved in .local/admin-credentials.txt (password not printed).')
finally:
    db.close()
