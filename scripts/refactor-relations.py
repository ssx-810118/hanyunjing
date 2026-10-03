from pathlib import Path
p=Path('src/main/java/com/hanyunjing/CommerceStore.java')
s=p.read_text(encoding='utf-8')
s=s.replace('SELECT c.id,c.product_id,c.sku_id,c.quantity,p.name,s.color,s.size_label,s.price FROM cart_item c JOIN product p ON c.product_id=p.id JOIN product_sku s ON c.sku_id=s.id', 'SELECT c.id,s.product_id,c.sku_id,c.quantity,p.name,s.color,s.size_label,s.price FROM cart_item c JOIN product_sku s ON c.sku_id=s.id JOIN product p ON s.product_id=p.id')
s=s.replace('"INSERT INTO cart_item VALUES (?,?,?,?,?)",id(),account,p.id(),s.id(),in.quantity()', '"INSERT INTO cart_item VALUES (?,?,?,?)",id(),account,s.id(),in.quantity()')
s=s.replace('"SELECT snapshot_json FROM shop_order WHERE id=? AND account_id=? FOR UPDATE",(r,n)->decode(r.getString(1),Models.Order.class)', '"SELECT * FROM shop_order WHERE id=? AND account_id=? FOR UPDATE",(r,n)->readOrder(r)')
s=s.replace('FROM order_item i JOIN shop_order o ON i.order_id=o.id WHERE o.account_id=? AND i.product_id=?', 'FROM order_item i JOIN product_sku s ON i.sku_id=s.id JOIN shop_order o ON i.order_id=o.id WHERE o.account_id=? AND s.product_id=?')
s=s.replace("SELECT COALESCE(SUM(total),0) FROM shop_order WHERE status='DEMO_PAID'", "SELECT COALESCE(SUM(i.quantity*i.unit_price),0) FROM order_item i JOIN shop_order o ON i.order_id=o.id WHERE o.status='DEMO_PAID'")
s=s.replace('decode(r.getString("snapshot_json"),Models.Order.class)', 'readOrder(r)')
s=s.replace('史料或设计说明','史料出处')
assert 'snapshot_json' not in s and 'details_json' not in s
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/hanyunjing/CoreService.java');s=p.read_text(encoding='utf-8')
s='\n'.join(line for line in s.splitlines() if not any('addArticle("'+a+'"' in line for a in ['a2','a3','a8']))+'\n'
start=s.index('        for (String dynasty : Dynasty.labels()) {')
end=s.index('        addArticle("a14"',start)
s=s[:start]+s[end:]
s=s.replace('以下仅为穿搭建议','以下为商品参考')
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/hanyunjing/Models.java');s=p.read_text(encoding='utf-8').replace('FACT, COMMON, ADVICE','FACT, COMMON');p.write_text(s,encoding='utf-8')
p=Path('frontend/src/types.ts');s=p.read_text(encoding='utf-8').replace(" | 'ADVICE'",'').replace(", ADVICE: '穿搭建议'",'');p.write_text(s,encoding='utf-8')
p=Path('frontend/src/views/OpsView.vue');s=p.read_text(encoding='utf-8').replace("kind:'ADVICE'", "kind:'FACT'").replace('<option value="ADVICE">穿搭建议</option>','');p.write_text(s,encoding='utf-8')
p=Path('frontend/src/views/CultureView.vue');s=p.read_text(encoding='utf-8').replace('史实有所据，通行有边界，建议供取舍。','循着实物与文献，认识历代衣冠。');p.write_text(s,encoding='utf-8')
for name in ['BackendTests.java','CatalogueTests.java']:
 p=Path('src/test/java/com/hanyunjing')/name;s=p.read_text(encoding='utf-8').replace('Models.Kind.ADVICE','Models.Kind.COMMON')
 s=s.replace('assertTrue(result.hits().stream().anyMatch(h -> h.article().kind() == Models.Kind.COMMON), dynasty);','assertTrue(result.hits().stream().allMatch(h -> h.article().kind() == Models.Kind.FACT), dynasty);')
 p.write_text(s,encoding='utf-8')
