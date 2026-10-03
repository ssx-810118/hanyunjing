"""Initialize only this application's database; credentials stay in .local/database.properties."""
from pathlib import Path
import pymysql

values = {}
for line in Path('.local/database.properties').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip()
try:
    connection = pymysql.connect(host='localhost', port=3306,
        user=values['spring.datasource.username'], password=values['spring.datasource.password'],
        connect_timeout=8, charset='utf8mb4')
    with connection.cursor() as cursor:
        cursor.execute('SELECT VERSION()')
        print('MySQL connected:', cursor.fetchone()[0])
        cursor.execute('CREATE DATABASE IF NOT EXISTS hanyunjing CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
        cursor.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='hanyunjing'")
        print('hanyunjing database ready; existing table count:', cursor.fetchone()[0])
    connection.close()
except pymysql.MySQLError as error:
    print('MySQL connection failed; error code:', error.args[0])
    raise SystemExit(1)
