package com.hanyunjing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** SQL is the source of truth. Stock, order snapshots and cart clearing commit together. */
@Component
@DependsOnDatabaseInitialization
public class CommerceStore {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    private final TransactionTemplate transactions;
    private List<AccountStore.StoredOrder> pendingLegacyOrders=List.of();
    public record ProductRecord(Models.Product product, String status, long revision, List<String> articleIds) {}
    public record Review(String id,String productId,String author,int rating,String content,
                         String reply,String createdAt,boolean mine,boolean purchased) {}
    public record ReviewInput(int rating,String content) {}
    public record ReviewReply(String reply) {}
    public record Shipment(String carrier,String trackingNumber) {}
    public CommerceStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.transactions=new TransactionTemplate(manager);
        // Existing H2/MySQL installations need the additive column as well as fresh schemas.
        boolean hasDeletionColumn=Boolean.TRUE.equals(jdbc.query("SELECT * FROM product WHERE 1=0",r->{
            var metadata=r.getMetaData();
            for(int i=1;i<=metadata.getColumnCount();i++) if("deleted_at".equalsIgnoreCase(metadata.getColumnName(i)))return true;
            return false;
        }));
        if(!hasDeletionColumn)jdbc.execute("ALTER TABLE product ADD COLUMN deleted_at VARCHAR(40)");
    }
    <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("数据序列化失败"); } }
    <T> T decode(String value,Class<T> type) { try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException("数据库记录读取失败"); } }
    private String now() { return Instant.now().toString(); }
    private String id() { return UUID.randomUUID().toString(); }
    boolean migrated(String key) { return jdbc.queryForObject("SELECT COUNT(*) FROM app_migration WHERE migration_key=?",Integer.class,key)>0; }
    void migration(String key) { jdbc.update("INSERT INTO app_migration VALUES (?,?)",key,now()); }

    void importAccounts(AccountStore.Snapshot snapshot) {
        tx(() -> {
            if(!migrated("legacy-accounts-v1")) {
                for(var account:snapshot.accounts()) insertAccount(account);
                migration("legacy-accounts-v1");
            }
            if(!migrated("legacy-orders-v3")) pendingLegacyOrders=snapshot.orders();
            return null;
        });
    }
    AccountStore.Account account(String username) {
        return jdbc.query("SELECT * FROM customer_account WHERE LOWER(username)=LOWER(?)",(r,n)->new AccountStore.Account(
            r.getString("id"),r.getString("username"),r.getString("display_name"),r.getString("salt"),r.getString("password_hash"),r.getInt("iterations"),Instant.parse(r.getString("created_at"))),username).stream().findFirst().orElse(null);
    }
    void insertAccount(AccountStore.Account a) {
        try { jdbc.update("INSERT INTO customer_account(id,username,display_name,salt,password_hash,iterations,created_at) VALUES (?,?,?,?,?,?,?)",
            a.id(),a.username().toLowerCase(Locale.ROOT),a.displayName(),a.salt(),a.passwordHash(),a.iterations(),a.createdAt().toString()); }
        catch(org.springframework.dao.DuplicateKeyException e) { throw new AuthService.Failure(409,"该用户名已注册，请登录或换一个用户名"); }
    }
    List<AccountStore.StoredOrder> storedOrders() {
        var saved=jdbc.query("SELECT * FROM shop_order ORDER BY created_at DESC",(r,n)->new AccountStore.StoredOrder(r.getString("account_id"),r.getString("idempotency_key"),readOrder(r)));
        var result=new ArrayList<>(saved);result.addAll(pendingLegacyOrders);return result;
    }
    AccountStore.StoredOrder byKey(String account,String key) {
        return jdbc.query("SELECT * FROM shop_order WHERE account_id=? AND idempotency_key=?",(r,n)->new AccountStore.StoredOrder(account,key,readOrder(r)),account,key).stream().findFirst().orElse(null);
    }
    List<Models.Order> orders(String account) {
        return jdbc.query("SELECT * FROM shop_order WHERE account_id=? ORDER BY created_at DESC",(r,n)->readOrder(r),account);
    }
    private Instant instant(String value) { return value==null?null:Instant.parse(value); }
    private String instantText(Instant value) { return value==null?null:value.toString(); }
    private Models.Order readOrder(java.sql.ResultSet r) throws java.sql.SQLException {
        var lines=jdbc.query("SELECT i.*,s.product_id FROM order_item i JOIN product_sku s ON i.sku_id=s.id WHERE i.order_id=? ORDER BY i.id",(v,n)->new Models.CartLine(v.getString("id"),v.getString("product_id"),v.getString("product_name_at_purchase"),v.getString("sku_id"),v.getString("color_at_purchase"),v.getString("size_at_purchase"),v.getInt("quantity"),v.getBigDecimal("unit_price"),v.getBigDecimal("unit_price").multiply(BigDecimal.valueOf(v.getInt("quantity")))),r.getString("id"));
        return new Models.Order(r.getString("id"),r.getString("session_id"),r.getString("status"),true,cartOf(lines),instant(r.getString("created_at")),r.getString("order_number"),r.getString("recipient"),r.getString("phone"),r.getString("region"),r.getString("address"),r.getString("payment_method"),instant(r.getString("paid_at")),instant(r.getString("cancelled_at")));
    }
    void insertOrder(AccountStore.StoredOrder value) {
        var o=value.order();
        jdbc.update("INSERT INTO shop_order(id,account_id,idempotency_key,order_number,status,session_id,created_at,recipient,phone,region,address,payment_method,paid_at,cancelled_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            o.id(),value.accountId(),value.idempotencyKey(),o.orderNumber(),o.status(),o.sessionId(),o.createdAt().toString(),o.recipient(),o.phone(),o.region(),o.address(),o.paymentMethod(),instantText(o.paidAt()),instantText(o.cancelledAt()));
        for(var l:o.cart().lines()) jdbc.update("INSERT INTO order_item VALUES (?,?,?,?,?,?,?,?)",id(),o.id(),l.skuId(),l.productName(),l.color(),l.size(),l.quantity(),l.unitPrice());
    }
    void updateOrder(String account,Models.Order order) {
        if(jdbc.update("UPDATE shop_order SET status=?,payment_method=?,paid_at=?,cancelled_at=? WHERE id=? AND account_id=?",order.status(),order.paymentMethod(),instantText(order.paidAt()),instantText(order.cancelledAt()),order.id(),account)!=1) throw new NoSuchElementException();
    }

    void initialize(Collection<Models.Product> products,Collection<Models.Article> articles) {
        tx(() -> {
            if(migrated("catalogue-sql-v1")) return null;
            // Drafts are retained; existing IDs, order references and existing images stay stable.
            Set<String> published=Set.of("p1","p2","p3","p4","p5","p6","p7","p8","p9","p10","p11","p12","p13","p14","p15","p16","p18","p19","p20","p21","p22","p23","p24","p25","p26");
            for(var a:articles) saveArticle(a);
            saveArticle(new Models.Article("a17","唐代圆领袍的陶俑证据","服饰史料","中国国家博物馆馆藏《陶男俑》的说明，以陶俑所着圆领袍、幞头和靴介绍唐代服饰。本站黛青圆领袍参考此类圆领袍形象；黛青配色、纹样、裁片和商品尺码为现代设计，不能由颜色推定历史身份。",Models.Kind.FACT,"中国国家博物馆｜陶男俑｜https://www.chnmuseum.cn/zp/zpml/kgfjp/202111/t20211116_252268.shtml",List.of("唐","圆领袍","陶俑"),"tang-round-collar","museum-figurine"));
            saveArticle(new Models.Article("a18","唐代高束裙、半臂与帔帛的图像证据","服饰史料","中国国家博物馆《长安水边多丽人》介绍1957年西安土门村出土的三彩釉陶女俑，记述小袖衣、半臂、长裙束于胸前和肩后帔帛。证据载体是陶俑，不能将本站朱红齐胸襦裙或藕荷披帛套装说成一套出土丝织衣物的复原。",Models.Kind.FACT,"中国国家博物馆｜长安水边多丽人｜https://www.chnmuseum.cn/yj/xscg/xslw/201812/t20181224_33161.shtml",List.of("唐","齐胸","高腰","半臂","披帛"),"tang-high-skirt","tumen-figurine"));
            for(var p:products) {
                boolean image=new org.springframework.core.io.ClassPathResource("static/images/products/"+p.id()+".webp").exists();
                var clean=new Models.Product(p.id(),p.name(),p.id().equals("p10")?"上衣":p.category(),p.dynasty(),p.form(),p.description(),p.scenes(),p.tags(),p.colors(),image?p.images():List.of(),p.accessoryIds(),p.sizeChart(),p.skus());
                insertProduct(clean,published.contains(p.id())?"ACTIVE":"DRAFT");
                for(String articleId:seedReferences(p.id())) if(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_article WHERE id=?",Integer.class,articleId)>0) jdbc.update("INSERT INTO product_knowledge VALUES (?,?)",p.id(),articleId);
            }
            for(var p:products) saveAccessories(p);
            for(var order:pendingLegacyOrders) insertOrder(order);
            pendingLegacyOrders=List.of();
            if(!migrated("legacy-orders-v3")) migration("legacy-orders-v3");
            jdbc.update("INSERT INTO app_lock VALUES ('admin-setup')");
            migration("catalogue-sql-v1"); return null;
        });
    }
    private List<String> seedReferences(String product) {
        return switch(product) {
            case "p11","p12","p17" -> List.of("a4");
            case "p15" -> List.of("a19");
            case "p16" -> List.of("a20");
            case "p1","p10" -> List.of("a18","a1");
            case "p7" -> List.of("a27");
            case "p2" -> List.of("a17");
            case "p4","p5" -> List.of("a1");
            case "p3","p6","p9" -> List.of("a5");
            case "p18" -> List.of("a21");
            case "p19" -> List.of("a14");
            case "p20" -> List.of("a22");
            case "p13" -> List.of("a6");
            case "p21" -> List.of("a23");
            case "p22" -> List.of("a15");
            case "p23" -> List.of("a24");
            case "p8" -> List.of("a7");
            case "p14","p26" -> List.of("a26");
            case "p24" -> List.of("a16");
            case "p25" -> List.of("a25");
            default -> List.of();
        };
    }
    void reviseCatalogue(Collection<Models.Product> products) {
        tx(()->{
            if(migrated("catalogue-distinct-forms-v4"))return null;
            var revision=CatalogueRevision.load();
            for(var a:revision.articles())saveArticle(a);
            Set<String> changed=new HashSet<>();revision.products().forEach(p->changed.add(p.id()));
            for(var p:products) {
                if(changed.contains(p.id())) {
                    jdbc.update("UPDATE product SET name=?,category=?,form=?,description=?,revision=revision+1,updated_at=? WHERE id=?",p.name(),p.category(),p.form(),p.description(),now(),p.id());
                    jdbc.update("DELETE FROM product_tag WHERE product_id=?",p.id());
                    for(String tag:p.tags())jdbc.update("INSERT INTO product_tag VALUES (?,?)",p.id(),tag);
                    jdbc.update("UPDATE product_sku SET color=? WHERE product_id=?",p.colors().get(0),p.id());
                }
                jdbc.update("DELETE FROM product_knowledge WHERE product_id=?",p.id());
                for(String aid:seedReferences(p.id()))jdbc.update("INSERT INTO product_knowledge VALUES (?,?)",p.id(),aid);
            }
            migration("catalogue-distinct-forms-v4");return null;
        });
        tx(()->{
            if(migrated("catalogue-tang-collar-v4"))return null;
            var a=CatalogueRevision.load().articles().stream().filter(v->v.id().equals("a27")).findFirst().orElseThrow();
            saveArticle(a);
            var p=products.stream().filter(v->v.id().equals("p7")).findFirst().orElseThrow();
            jdbc.update("UPDATE product SET name=?,category=?,form=?,description=?,revision=revision+1,updated_at=? WHERE id=?",p.name(),p.category(),p.form(),p.description(),now(),p.id());
            jdbc.update("DELETE FROM product_tag WHERE product_id=?",p.id());
            for(String tag:p.tags())jdbc.update("INSERT INTO product_tag VALUES (?,?)",p.id(),tag);
            jdbc.update("UPDATE product_sku SET color=? WHERE product_id=?",p.colors().get(0),p.id());
            jdbc.update("DELETE FROM product_knowledge WHERE product_id=?",p.id());
            jdbc.update("INSERT INTO product_knowledge VALUES (?,?)",p.id(),a.id());
            migration("catalogue-tang-collar-v4");return null;
        });
    }
    private Models.Product readProduct(java.sql.ResultSet r) throws java.sql.SQLException {
        String pid=r.getString("id");
        List<Models.Sku> skus=jdbc.query("SELECT * FROM product_sku WHERE product_id=? ORDER BY sort_order,id",(s,n)->new Models.Sku(s.getString("id"),s.getString("color"),s.getString("size_label"),s.getInt("stock"),s.getBigDecimal("price")),pid);
        var sizes=jdbc.query("SELECT * FROM product_size WHERE product_id=? ORDER BY sort_order",(s,n)->new Models.SizeRow(s.getString("size_label"),new Models.Range(s.getDouble("height_min"),s.getDouble("height_max")),new Models.Range(s.getDouble("chest_min"),s.getDouble("chest_max")),new Models.Range(s.getDouble("waist_min"),s.getDouble("waist_max")),new Models.Range(s.getDouble("hip_min"),s.getDouble("hip_max"))),pid);
        return new Models.Product(pid,r.getString("name"),r.getString("category"),r.getString("dynasty"),r.getString("form"),r.getString("description"),strings("product_scene","scene_code",pid),strings("product_tag","tag",pid),skus.stream().map(Models.Sku::color).distinct().toList(),jdbc.queryForList("SELECT url FROM product_image WHERE product_id=? ORDER BY sort_order",String.class,pid),strings("product_accessory","accessory_id",pid),sizes,skus);
    }
    private List<String> strings(String table,String column,String pid) { return jdbc.queryForList("SELECT "+column+" FROM "+table+" WHERE product_id=? ORDER BY "+column,String.class,pid); }
    List<Models.Product> products(boolean includeHidden) {
        return jdbc.query("SELECT * FROM product WHERE deleted_at IS NULL"+(includeHidden?"":" AND status='ACTIVE'")+" ORDER BY created_at,id",(r,n)->readProduct(r));
    }
    Models.Product product(String id,boolean includeHidden) {
        return jdbc.query("SELECT * FROM product WHERE id=? AND deleted_at IS NULL"+(includeHidden?"":" AND status='ACTIVE'"),(r,n)->readProduct(r),id).stream().findFirst().orElseThrow();
    }
    ProductRecord record(String id) {
        return jdbc.query("SELECT * FROM product WHERE id=? AND deleted_at IS NULL",(r,n)->new ProductRecord(readProduct(r),r.getString("status"),r.getLong("revision"),articleIds(id)),id).stream().findFirst().orElseThrow();
    }
    List<String> articleIds(String id) { return jdbc.queryForList("SELECT article_id FROM product_knowledge WHERE product_id=? ORDER BY article_id",String.class,id); }
    List<Models.Article> references(String id) { return jdbc.query("SELECT a.* FROM knowledge_article a JOIN product_knowledge p ON a.id=p.article_id WHERE p.product_id=? ORDER BY a.kind,a.id",(r,n)->readArticle(r),id); }
    List<Models.Article> articles() { return jdbc.query("SELECT * FROM knowledge_article ORDER BY id",(r,n)->readArticle(r)); }
    private Models.Article readArticle(java.sql.ResultSet r)throws java.sql.SQLException {
        String aid=r.getString("id");
        var sources=jdbc.query("SELECT s.citation,s.url FROM historical_source s JOIN article_source a ON a.source_id=s.id WHERE a.article_id=? ORDER BY a.sort_order",(s,n)->s.getString(1)+(s.getString(2).isEmpty()?"":"｜"+s.getString(2)),aid);
        return new Models.Article(aid,r.getString("title"),r.getString("topic"),r.getString("content"),Models.Kind.valueOf(r.getString("kind")),String.join("；",sources),jdbc.queryForList("SELECT keyword FROM article_keyword WHERE article_id=? ORDER BY keyword",String.class,aid),r.getString("claim_key"),r.getString("claim_value"));
    }
    void saveArticle(Models.Article a) {
        if(jdbc.update("UPDATE knowledge_article SET title=?,kind=?,topic=?,content=?,claim_key=?,claim_value=? WHERE id=?",a.title(),a.kind().name(),a.topic(),a.content(),a.claimKey(),a.claimValue(),a.id())==0)
            jdbc.update("INSERT INTO knowledge_article VALUES (?,?,?,?,?,?,?)",a.id(),a.title(),a.kind().name(),a.topic(),a.content(),a.claimKey(),a.claimValue());
        jdbc.update("DELETE FROM article_keyword WHERE article_id=?",a.id());
        for(String keyword:new LinkedHashSet<>(a.keywords())) jdbc.update("INSERT INTO article_keyword VALUES (?,?)",a.id(),keyword);
        jdbc.update("DELETE FROM article_source WHERE article_id=?",a.id());
        int position=0;Set<String> seen=new HashSet<>();
        for(String source:a.source().split("；")) {
            var matcher=java.util.regex.Pattern.compile("https?://[^\\s｜|；]+").matcher(source);
            String url=matcher.find()?matcher.group():"",citation=source.replace(url,"").replaceAll("[｜|\\s]+$","").trim();
            String sid=UUID.nameUUIDFromBytes((citation+"|"+url).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            if(!seen.add(sid)) continue;
            if(jdbc.queryForObject("SELECT COUNT(*) FROM historical_source WHERE id=?",Integer.class,sid)==0) jdbc.update("INSERT INTO historical_source VALUES (?,?,?)",sid,citation,url);
            jdbc.update("INSERT INTO article_source VALUES (?,?,?)",a.id(),sid,position++);
        }
        jdbc.update("DELETE FROM historical_source WHERE id NOT IN (SELECT source_id FROM article_source)");
    }
    private void insertProduct(Models.Product p,String status) {
        jdbc.update("INSERT INTO product(id,name,category,dynasty,form,description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",p.id(),p.name(),p.category(),p.dynasty(),p.form(),p.description(),status,now(),now());
        saveProductDetails(p);
        int order=0;for(var s:p.skus()) jdbc.update("INSERT INTO product_sku VALUES (?,?,?,?,?,?,?)",s.id(),p.id(),s.color(),s.size(),s.price(),s.stock(),order++);
    }
    private void saveAccessories(Models.Product p) {
        jdbc.update("DELETE FROM product_accessory WHERE product_id=?",p.id());
        for(String aid:new LinkedHashSet<>(p.accessoryIds())) jdbc.update("INSERT INTO product_accessory VALUES (?,?)",p.id(),aid);
    }
    private void saveProductDetails(Models.Product p) {
        for(String table:List.of("product_image","product_tag","product_scene")) jdbc.update("DELETE FROM "+table+" WHERE product_id=?",p.id());
        int i=0;for(String url:new LinkedHashSet<>(p.images())) jdbc.update("INSERT INTO product_image VALUES (?,?,?)",p.id(),i++,url);
        for(String tag:new LinkedHashSet<>(p.tags())) jdbc.update("INSERT INTO product_tag VALUES (?,?)",p.id(),tag);
        for(String scene:new LinkedHashSet<>(p.scenes())) jdbc.update("INSERT INTO product_scene VALUES (?,?)",p.id(),scene);
        i=0;for(var s:p.sizeChart()) {
            if(jdbc.update("UPDATE product_size SET sort_order=?,height_min=?,height_max=?,chest_min=?,chest_max=?,waist_min=?,waist_max=?,hip_min=?,hip_max=? WHERE product_id=? AND size_label=?",i,s.height().min(),s.height().max(),s.chest().min(),s.chest().max(),s.waist().min(),s.waist().max(),s.hip().min(),s.hip().max(),p.id(),s.size())==0)
                jdbc.update("INSERT INTO product_size VALUES (?,?,?,?,?,?,?,?,?,?,?)",p.id(),s.size(),i,s.height().min(),s.height().max(),s.chest().min(),s.chest().max(),s.waist().min(),s.waist().max(),s.hip().min(),s.hip().max());
            i++;
        }
    }
    ProductRecord saveProduct(ProductRecord in,boolean create,String admin) {
        validateProduct(in);
        return tx(() -> {
            var p=in.product();
            for(String aid:in.articleIds()) if(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_article WHERE id=?",Integer.class,aid)==0) throw new IllegalArgumentException("史料不存在："+aid);
            if(create) {
                if(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=?",Integer.class,p.id())>0) throw new IllegalStateException("商品编号已存在");
                insertProduct(p,in.status());
            } else {
                var previous=record(p.id());
                Set<String> retained=new HashSet<>();for(var s:p.skus()) retained.add(s.id());
                if(previous.product().skus().stream().anyMatch(s->!retained.contains(s.id()))) throw new IllegalArgumentException("已有规格不能删除，请将其库存设为0，保留历史订单引用");
                if(jdbc.update("UPDATE product SET name=?,category=?,dynasty=?,form=?,description=?,status=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND deleted_at IS NULL",
                    p.name(),p.category(),p.dynasty(),p.form(),p.description(),in.status(),now(),p.id(),in.revision())!=1) throw new AuthService.Failure(409,"商品或库存已变化，请重新载入后再保存");
                saveProductDetails(p);
                int order=0;for(var s:p.skus()) {
                    if(jdbc.update("UPDATE product_sku SET color=?,size_label=?,price=?,stock=?,sort_order=? WHERE id=? AND product_id=?",s.color(),s.size(),s.price(),s.stock(),order,s.id(),p.id())==0)
                        jdbc.update("INSERT INTO product_sku VALUES (?,?,?,?,?,?,?)",s.id(),p.id(),s.color(),s.size(),s.price(),s.stock(),order);
                    order++;
                }
            }
            saveAccessories(p);
            for(String size:jdbc.queryForList("SELECT size_label FROM product_size WHERE product_id=?",String.class,p.id()))
                if(p.sizeChart().stream().noneMatch(s->s.size().equals(size))) jdbc.update("DELETE FROM product_size WHERE product_id=? AND size_label=?",p.id(),size);
            jdbc.update("DELETE FROM product_knowledge WHERE product_id=?",p.id());
            for(String aid:new LinkedHashSet<>(in.articleIds())) jdbc.update("INSERT INTO product_knowledge VALUES (?,?)",p.id(),aid);
            audit(admin,create?"PRODUCT_CREATE":"PRODUCT_UPDATE",p.id(),p.name()+" / "+in.status());
            return record(p.id());
        });
    }
    void deleteProduct(String productId,long revision,String admin) {
        if(revision<0)throw new IllegalArgumentException("商品版本无效，请刷新后重试");
        tx(()->{
            var rows=jdbc.queryForList("SELECT name,status,revision,deleted_at FROM product WHERE id=? FOR UPDATE",productId);
            if(rows.isEmpty())throw new NoSuchElementException();
            var row=rows.get(0);
            if(row.get("deleted_at")!=null)return null; // Safe to retry a lost delete response.
            if(!Set.of("DRAFT","ARCHIVED").contains(row.get("status")))throw new AuthService.Failure(409,"只能删除草稿或已下架商品，请先下架再删除");
            if(((Number)row.get("revision")).longValue()!=revision)throw new AuthService.Failure(409,"商品或库存已变化，请刷新后再确认删除");
            String deletedAt=now();
            jdbc.update("UPDATE product SET status='ARCHIVED',deleted_at=?,updated_at=?,revision=revision+1 WHERE id=?",deletedAt,deletedAt,productId);
            jdbc.update("DELETE FROM cart_item WHERE sku_id IN (SELECT id FROM product_sku WHERE product_id=?)",productId);
            jdbc.update("DELETE FROM product_accessory WHERE product_id=? OR accessory_id=?",productId,productId);
            audit(admin,"PRODUCT_DELETE",productId,Objects.toString(row.get("name"))+" / 删除商品，保留历史记录");
            return null;
        });
    }
    private void validateProduct(ProductRecord in) {
        if(in==null||in.product()==null) throw new IllegalArgumentException("请填写商品");
        var p=in.product();
        if(p.id()==null||!p.id().matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("商品编号格式无效");
        text(p.name(),1,120,"商品名称");text(p.category(),1,40,"分类");text(p.form(),1,80,"款式");text(p.description(),1,6000,"商品说明");
        if(!Dynasty.supports(p.dynasty())||!Set.of("ACTIVE","DRAFT","ARCHIVED").contains(in.status())) throw new IllegalArgumentException("朝代或上架状态无效");
        if(p.skus()==null||p.skus().isEmpty()||p.skus().size()>100||p.sizeChart()==null||p.scenes()==null||p.tags()==null||p.images()==null||p.accessoryIds()==null||in.articleIds()==null) throw new IllegalArgumentException("请填写完整规格、尺码与史料关联");
        if(in.status().equals("ACTIVE")&&(p.images().isEmpty()||in.articleIds().isEmpty()||p.sizeChart().isEmpty()))throw new IllegalArgumentException("上架前请补齐独立商品图、尺码表和史料出处");
        for(String related:p.accessoryIds())if(related.equals(p.id())||jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=? AND status='ACTIVE'",Integer.class,related)==0)throw new IllegalArgumentException("搭配商品不存在、已下架或指向自身");
        for(var sku:p.skus())if(p.sizeChart().stream().noneMatch(row->row.size().equals(sku.size())))throw new IllegalArgumentException("规格尺码在尺码表中缺少对应行："+sku.size());
        Set<String> ids=new HashSet<>(),options=new HashSet<>();
        for(var s:p.skus()) {
            if(s.id()==null||!s.id().matches("[A-Za-z0-9_-]{1,100}")||!ids.add(s.id())) throw new IllegalArgumentException("规格编号无效或重复");
            text(s.color(),1,40,"颜色");text(s.size(),1,20,"尺码");
            if(!options.add(s.color()+"|"+s.size())||s.stock()<0||s.stock()>1000000||s.price()==null||s.price().signum()<=0||s.price().compareTo(new BigDecimal("999999.99"))>0||s.price().stripTrailingZeros().scale()>2) throw new IllegalArgumentException("规格重复，或库存/价格超出范围（价格最多两位小数）");
        }
        for(String img:p.images()) if(img==null||!img.matches("/api/(?:products/[A-Za-z0-9_-]+/image|media/[A-Za-z0-9_-]+\\.png)")) throw new IllegalArgumentException("请上传本站商品图片");
        for(var row:p.sizeChart()) {
            text(row.size(),1,20,"尺码表名称");
            for(var r:Arrays.asList(row.height(),row.chest(),row.waist(),row.hip())) if(r==null||!Double.isFinite(r.min())||!Double.isFinite(r.max())||r.min()<1||r.max()>300||r.min()>r.max()) throw new IllegalArgumentException("尺码表范围无效");
        }
    }
    static void text(String value,int min,int max,String label) { if(value==null||value.trim().length()<min||value.length()>max) throw new IllegalArgumentException(label+"须为"+min+"至"+max+"字"); }
    void audit(String admin,String action,String target,String summary) { jdbc.update("INSERT INTO admin_audit VALUES (?,?,?,?,?,?)",id(),admin,action,target,summary,now()); }

    private void lockAccount(String account) { if(jdbc.queryForList("SELECT id FROM customer_account WHERE id=? FOR UPDATE",String.class,account).isEmpty()) throw new AuthService.Failure(401,"请先登录"); }
    Models.Cart cart(String account) {
        List<Models.CartLine> lines=jdbc.query("SELECT c.id,s.product_id,c.sku_id,c.quantity,p.name,s.color,s.size_label,s.price FROM cart_item c JOIN product_sku s ON c.sku_id=s.id JOIN product p ON s.product_id=p.id WHERE c.account_id=? AND p.deleted_at IS NULL ORDER BY c.id",(r,n)->new Models.CartLine(r.getString(1),r.getString(2),r.getString(5),r.getString(3),r.getString(6),r.getString(7),r.getInt(4),r.getBigDecimal(8),r.getBigDecimal(8).multiply(BigDecimal.valueOf(r.getInt(4)))),account);
        return cartOf(lines);
    }
    private Models.Cart cartOf(List<Models.CartLine> lines) { return new Models.Cart(List.copyOf(lines),lines.stream().mapToInt(Models.CartLine::quantity).sum(),lines.stream().map(Models.CartLine::subtotal).reduce(BigDecimal.ZERO,BigDecimal::add)); }
    Models.Cart addCart(String account,Models.CartAdd in) {
        return tx(() -> {
            lockAccount(account);
            jdbc.queryForList("SELECT id FROM product WHERE id=? FOR UPDATE",String.class,in.productId());
            var p=product(in.productId(),false);var s=p.skus().stream().filter(v->v.id().equals(in.skuId())).findFirst().orElseThrow();
            if(in.quantity()<1||in.quantity()>99) throw new IllegalArgumentException("数量须为1至99");
            if(s.stock()<in.quantity()) throw new IllegalStateException("库存不足");
            if(jdbc.update("UPDATE cart_item SET quantity=? WHERE account_id=? AND sku_id=?",in.quantity(),account,s.id())==0) jdbc.update("INSERT INTO cart_item VALUES (?,?,?,?)",id(),account,s.id(),in.quantity());
            return cart(account);
        });
    }
    Models.Cart updateCart(String account,String line,int quantity) {
        return tx(() -> {lockAccount(account);var l=cart(account).lines().stream().filter(v->v.id().equals(line)).findFirst().orElseThrow();return addCart(account,new Models.CartAdd(l.productId(),l.skuId(),quantity));});
    }
    void deleteCart(String account,String line) { tx(()->{lockAccount(account);jdbc.update("DELETE FROM cart_item WHERE account_id=? AND id=?",account,line);return null;}); }
    Models.Order checkout(String account,String session,Models.Checkout input) {
        return tx(() -> {
            lockAccount(account);var prior=byKey(account,input.idempotencyKey());
            if(prior!=null) {
                var o=prior.order();
                if(!o.recipient().equals(input.recipient().trim())||!o.phone().equals(input.phone().trim())||!o.region().equals(input.region().trim())||!o.address().equals(input.address().trim())) throw new IllegalStateException("相同提交标识对应了不同收货信息");
                return o;
            }
            var initial=cart(account);if(initial.lines().isEmpty()) throw new IllegalStateException("衣囊为空");
            // Product locks also serialize admin price/stock changes with checkout. Always lock in ID order.
            for(String pid:initial.lines().stream().map(Models.CartLine::productId).distinct().sorted().toList()) {
                var states=jdbc.queryForList("SELECT status FROM product WHERE id=? FOR UPDATE",String.class,pid);
                if(states.isEmpty()||!states.get(0).equals("ACTIVE")) throw new IllegalStateException("商品已下架，请移出衣囊");
            }
            var checked=cart(account);
            for(var l:checked.lines()) {
                if(jdbc.update("UPDATE product_sku SET stock=stock-? WHERE id=? AND stock>=?",l.quantity(),l.skuId(),l.quantity())!=1) throw new IllegalStateException("库存不足，请调整衣囊");
                jdbc.update("UPDATE product SET revision=revision+1 WHERE id=?",l.productId());
            }
            String oid=id();var o=new Models.Order(oid,session,"PENDING_PAYMENT",true,checked,Instant.now(),"HYJ"+oid.replace("-","").toUpperCase(Locale.ROOT),input.recipient().trim(),input.phone().trim(),input.region().trim(),input.address().trim(),null,null,null);
            insertOrder(new AccountStore.StoredOrder(account,input.idempotencyKey(),o));
            jdbc.update("DELETE FROM cart_item WHERE account_id=?",account);return o;
        });
    }
    Models.Order transitionOrder(String account,String id,String method,boolean cancel) {
        return tx(() -> {
            lockAccount(account);
            var p=jdbc.query("SELECT * FROM shop_order WHERE id=? AND account_id=? FOR UPDATE",(r,n)->readOrder(r),id,account).stream().findFirst().orElseThrow();
            String status=cancel?"CANCELLED":"DEMO_PAID";
            if(!cancel&&!Set.of("ALIPAY","WECHAT","BALANCE").contains(method==null?"":method)) throw new IllegalArgumentException("支付方式无效");
            if(p.status().equals(status)) return p;
            if(!p.status().equals("PENDING_PAYMENT")) throw new IllegalStateException("订单状态已变化，请刷新");
            if(cancel) {
                for(String pid:p.cart().lines().stream().map(Models.CartLine::productId).distinct().sorted().toList()) jdbc.queryForList("SELECT id FROM product WHERE id=? FOR UPDATE",String.class,pid);
                for(var l:p.cart().lines()) {jdbc.update("UPDATE product_sku SET stock=stock+? WHERE id=?",l.quantity(),l.skuId());jdbc.update("UPDATE product SET revision=revision+1 WHERE id=?",l.productId());}
            }
            var o=new Models.Order(p.id(),p.sessionId(),status,true,p.cart(),p.createdAt(),p.orderNumber(),p.recipient(),p.phone(),p.region(),p.address(),cancel?null:method,cancel?null:Instant.now(),cancel?Instant.now():null);
            updateOrder(account,o);return o;
        });
    }

    boolean admin(String account) { return account!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM customer_account WHERE id=? AND role='ADMIN'",Integer.class,account)>0; }
    boolean hasAdmin() { return jdbc.queryForObject("SELECT COUNT(*) FROM customer_account WHERE role='ADMIN'",Integer.class)>0; }
    void bootstrapAdmin(String account) {
        tx(()->{jdbc.queryForList("SELECT lock_name FROM app_lock WHERE lock_name='admin-setup' FOR UPDATE",String.class);if(hasAdmin())throw new AuthService.Failure(409,"管理员已初始化");if(jdbc.update("UPDATE customer_account SET role='ADMIN' WHERE id=?",account)!=1)throw new NoSuchElementException();audit(account,"ADMIN_SETUP",account,"初始化管理员");return null;});
    }
    List<Review> reviews(String product,String viewer) {
        String sql="SELECT r.*,a.display_name FROM product_review r JOIN customer_account a ON r.account_id=a.id WHERE "+(product==null?"1=1":"r.product_id=?")+" ORDER BY r.created_at DESC LIMIT 200";
        List<Object> params=new ArrayList<>();if(product!=null)params.add(product);
        return jdbc.query(sql,(r,n)->{
            String account=r.getString("account_id"),pid=r.getString("product_id");
            boolean purchased=jdbc.queryForObject("SELECT COUNT(*) FROM order_item i JOIN product_sku s ON i.sku_id=s.id JOIN shop_order o ON i.order_id=o.id WHERE o.account_id=? AND s.product_id=? AND o.status='DEMO_PAID'",Integer.class,account,pid)>0;
            return new Review(r.getString("id"),pid,r.getString("display_name"),r.getInt("rating"),r.getString("content"),r.getString("reply"),r.getString("created_at"),account.equals(viewer),purchased);
        },params.toArray());
    }
    void review(String product,String account,ReviewInput in) {
        if(in==null||in.rating()<1||in.rating()>5)throw new IllegalArgumentException("评分须为1至5星");text(in.content(),5,2000,"评论");
        tx(()->{lockAccount(account);product(product,false);
            if(jdbc.update("UPDATE product_review SET rating=?,content=?,status='APPROVED',reply='',moderation_note='',updated_at=? WHERE product_id=? AND account_id=?",in.rating(),in.content().trim(),now(),product,account)==0)
                jdbc.update("INSERT INTO product_review(id,product_id,account_id,rating,content,status,created_at,updated_at) VALUES (?,?,?,?,?,'APPROVED',?,?)",id(),product,account,in.rating(),in.content().trim(),now(),now());return null;});
    }
    void replyToReview(String review,ReviewReply in,String admin) {
        if(in==null)throw new IllegalArgumentException("请填写商家回复");text(in.reply(),1,2000,"商家回复");
        tx(()->{if(jdbc.update("UPDATE product_review SET reply=?,updated_at=? WHERE id=?",in.reply().trim(),now(),review)!=1)throw new NoSuchElementException();audit(admin,"REVIEW_REPLY",review,"回复用户评论");return null;});
    }
    Map<String,Object> dashboard() {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("products",jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE deleted_at IS NULL",Integer.class));
        data.put("activeProducts",jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE status='ACTIVE'",Integer.class));
        data.put("stock",jdbc.queryForObject("SELECT COALESCE(SUM(s.stock),0) FROM product_sku s JOIN product p ON p.id=s.product_id WHERE p.deleted_at IS NULL",Long.class));
        data.put("reviewCount",jdbc.queryForObject("SELECT COUNT(*) FROM product_review",Integer.class));
        data.put("orders",jdbc.queryForObject("SELECT COUNT(*) FROM shop_order",Integer.class));
        data.put("paidTotal",jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity*i.unit_price),0) FROM order_item i JOIN shop_order o ON i.order_id=o.id WHERE o.status='DEMO_PAID'",BigDecimal.class));
        data.put("lowStock",jdbc.queryForList("SELECT s.id,p.name,s.color,s.size_label,s.stock FROM product_sku s JOIN product p ON s.product_id=p.id WHERE s.stock<5 AND p.status='ACTIVE' ORDER BY s.stock LIMIT 30"));
        return data;
    }
    List<Map<String,Object>> adminOrders() {
        return jdbc.query("SELECT * FROM shop_order ORDER BY created_at DESC LIMIT 200",(r,n)->Map.of("order",readOrder(r),"fulfillmentStatus",r.getString("fulfillment_status"),"carrier",Objects.toString(r.getString("carrier"),""),"trackingNumber",Objects.toString(r.getString("tracking_number"),"")));
    }
    void ship(String order,Shipment in,String admin) {
        text(in.carrier(),1,80,"物流公司");text(in.trackingNumber(),1,100,"物流单号");
        tx(()->{if(jdbc.update("UPDATE shop_order SET fulfillment_status='SHIPPED',carrier=?,tracking_number=?,shipped_at=? WHERE id=? AND status='DEMO_PAID' AND fulfillment_status='UNSHIPPED'",in.carrier(),in.trackingNumber(),now(),order)!=1)throw new IllegalStateException("仅能为已模拟支付且未发货的订单登记发货");audit(admin,"ORDER_SHIP",order,"登记发货");return null;});
    }
}
