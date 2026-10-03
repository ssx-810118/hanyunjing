package com.hanyunjing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

@RestController
@RequestMapping("/api")
public class CommerceController {
    private final CommerceStore store;
    private final AdminAccess access;
    private final Path media;
    public CommerceController(CommerceStore store,AdminAccess access,@Value("${app.media.directory:.local/media}") String directory){this.store=store;this.access=access;media=Path.of(directory).toAbsolutePath().normalize();}
    public record Setup(String token) {}
    public record Reply(String reply) {}
    @GetMapping("/admin/support") public Models.Api<List<Map<String,Object>>> support(){access.require();return Models.Api.ok(store.jdbc.query("SELECT * FROM support_ticket ORDER BY created_at DESC LIMIT 200",(r,n)->{var t=SupportTicketService.read(r);return Map.<String,Object>of("id",t.id(),"number",t.number(),"category",t.category(),"message",t.message(),"status",r.getString("status"),"reply",r.getString("reply"),"createdAt",t.createdAt().toString());}));}
    @PutMapping("/admin/support/{id}") public Models.Api<Void> reply(@PathVariable String id,@RequestBody Reply in){String admin=access.require();CommerceStore.text(in.reply(),1,2000,"客服回复");store.tx(()->{if(store.jdbc.update("UPDATE support_ticket SET reply=?,status='REPLIED' WHERE id=?",in.reply(),id)!=1)throw new NoSuchElementException();store.audit(admin,"SUPPORT_REPLY",id,"回复客服留言");return null;});return Models.Api.ok(null);}
    @GetMapping("/support/tickets/{id}/reply") public Models.Api<Map<String,Object>> supportReply(@PathVariable String id){var u=AuthService.currentUser();if(u==null)throw new AuthService.Failure(401,"请先登录");return Models.Api.ok(store.jdbc.query("SELECT reply,status FROM support_ticket WHERE id=? AND account_id=?",(r,n)->Map.<String,Object>of("reply",r.getString(1),"status",r.getString(2)),id,u.id()).stream().findFirst().orElseThrow());}
    @GetMapping("/admin/access") public Models.Api<Map<String,Object>> access(){return Models.Api.ok(access.status());}
    @PostMapping("/admin/setup") public Models.Api<Void> setup(@RequestBody Setup in){access.setup(in.token());return Models.Api.ok(null);}
    @GetMapping("/admin/summary") public Models.Api<Map<String,Object>> summary(){access.require();return Models.Api.ok(store.dashboard());}
    @GetMapping("/admin/customers") public Models.Api<List<Map<String,Object>>> customers(){access.require();return Models.Api.ok(store.jdbc.query("SELECT a.id,a.username,a.display_name,a.role,a.created_at,(SELECT COUNT(*) FROM shop_order o WHERE o.account_id=a.id) AS order_count FROM customer_account a ORDER BY a.created_at DESC LIMIT 500",(r,n)->Map.of("id",r.getString(1),"username",r.getString(2),"displayName",r.getString(3),"role",r.getString(4),"createdAt",r.getString(5),"orderCount",r.getInt(6))));}
    @GetMapping("/admin/products") public Models.Api<List<CommerceStore.ProductRecord>> products(){access.require();return Models.Api.ok(store.products(true).stream().map(p->store.record(p.id())).toList());}
    @PostMapping("/admin/products") public Models.Api<CommerceStore.ProductRecord> create(@RequestBody CommerceStore.ProductRecord in){return Models.Api.ok(store.saveProduct(in,true,access.require()));}
    @PutMapping("/admin/products/{id}") public Models.Api<CommerceStore.ProductRecord> update(@PathVariable String id,@RequestBody CommerceStore.ProductRecord in){String admin=access.require();if(in.product()==null||!id.equals(in.product().id()))throw new IllegalArgumentException("商品编号不一致");return Models.Api.ok(store.saveProduct(in,false,admin));}
    @DeleteMapping("/admin/products/{id}") public Models.Api<Void> delete(@PathVariable String id,@RequestParam long revision){store.deleteProduct(id,revision,access.require());return Models.Api.ok(null);}
    @GetMapping("/admin/articles") public Models.Api<List<Models.Article>> articles(){access.require();return Models.Api.ok(store.articles());}
    @PutMapping("/admin/articles/{id}") public Models.Api<Models.Article> article(@PathVariable String id,@jakarta.validation.Valid @RequestBody Models.KnowledgeAdd in){String admin=access.require();var a=new Models.Article(id,in.title(),in.topic(),in.content(),in.kind(),in.source(),in.keywords(),in.claimKey(),in.claimValue());if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("史料编号无效");if(in.kind()==Models.Kind.FACT&&!in.source().contains("https://"))throw new IllegalArgumentException("史实条目必须附可核查的 HTTPS 来源链接");store.tx(()->{store.saveArticle(a);store.audit(admin,"ARTICLE_UPDATE",id,a.title());return null;});return Models.Api.ok(a);}
    @GetMapping("/products/{id}/references") public Models.Api<Models.KnowledgeResult> references(@PathVariable String id){store.product(id,false);var hits=store.references(id).stream().map(a->new Models.KnowledgeHit(a,1)).toList();return Models.Api.ok(new Models.KnowledgeResult(hits.isEmpty(),hits.isEmpty()?"本款尚无已关联的出处资料":"",false,hits));}
    @GetMapping("/products/{id}/reviews") public Models.Api<List<CommerceStore.Review>> reviews(@PathVariable String id){store.product(id,false);var u=AuthService.currentUser();return Models.Api.ok(store.reviews(id,u==null?null:u.id()));}
    @PostMapping("/products/{id}/reviews") public Models.Api<Void> review(@PathVariable String id,@RequestBody CommerceStore.ReviewInput in){var u=AuthService.currentUser();if(u==null)throw new AuthService.Failure(401,"请先登录后发表评论");store.review(id,u.id(),in);return Models.Api.ok(null);}
    @GetMapping("/admin/reviews") public Models.Api<List<CommerceStore.Review>> reviews(){access.require();return Models.Api.ok(store.reviews(null,null));}
    @PutMapping("/admin/reviews/{id}") public Models.Api<Void> replyToReview(@PathVariable String id,@RequestBody CommerceStore.ReviewReply in){store.replyToReview(id,in,access.require());return Models.Api.ok(null);}
    @GetMapping("/admin/orders") public Models.Api<List<Map<String,Object>>> orders(){access.require();return Models.Api.ok(store.adminOrders());}
    @PostMapping("/admin/orders/{id}/ship") public Models.Api<Void> ship(@PathVariable String id,@RequestBody CommerceStore.Shipment in){store.ship(id,in,access.require());return Models.Api.ok(null);}
    @GetMapping("/orders/{id}/shipment") public Models.Api<Map<String,Object>> shipment(@PathVariable String id){var u=AuthService.currentUser();if(u==null)throw new AuthService.Failure(401,"请先登录");return Models.Api.ok(store.jdbc.query("SELECT fulfillment_status,carrier,tracking_number,shipped_at FROM shop_order WHERE id=? AND account_id=?",(r,n)->Map.<String,Object>of("status",r.getString(1),"carrier",Objects.toString(r.getString(2),""),"trackingNumber",Objects.toString(r.getString(3),""),"shippedAt",Objects.toString(r.getString(4),"")),id,u.id()).stream().findFirst().orElseThrow());}
    @GetMapping("/admin/audit") public Models.Api<List<Map<String,Object>>> audit(){access.require();return Models.Api.ok(store.jdbc.queryForList("SELECT action,target_id,summary,created_at FROM admin_audit ORDER BY created_at DESC LIMIT 100"));}
    @PostMapping("/admin/media") public Models.Api<Map<String,String>> upload(@RequestPart MultipartFile file)throws Exception {
        String admin=access.require();if(file.isEmpty()||file.getSize()>5*1024*1024)throw new IllegalArgumentException("请选择不超过5MB的PNG或JPEG图片");
        java.awt.image.BufferedImage image;
        try(var stream=ImageIO.createImageInputStream(file.getInputStream())){
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IllegalArgumentException("图片无法解码");var reader=readers.next();
            try{reader.setInput(stream);String format=reader.getFormatName();if(!Set.of("JPEG","JPG","PNG").contains(format.toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("仅支持PNG和JPEG");int w=reader.getWidth(0),h=reader.getHeight(0);if(w<512||h<512||w>4096||h>4096)throw new IllegalArgumentException("图片宽高须为512至4096像素");image=reader.read(0);}finally{reader.dispose();}
        }
        String name=UUID.randomUUID()+".png";Files.createDirectories(media);Path target=media.resolve(name);ImageIO.write(image,"png",target.toFile());store.audit(admin,"MEDIA_UPLOAD",name,"上传商品图片");return Models.Api.ok(Map.of("url","/api/media/"+name));
    }
    @GetMapping("/media/{name}") public ResponseEntity<byte[]> image(@PathVariable String name)throws Exception {if(!name.matches("[A-Za-z0-9_-]+\\.png"))throw new NoSuchElementException();Path file=media.resolve(name).normalize();if(!file.startsWith(media)||!Files.isRegularFile(file))throw new NoSuchElementException();return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1))).body(Files.readAllBytes(file));}
}
