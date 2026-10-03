package com.hanyunjing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.function.Predicate;

@Service
public class CoreService {
    private final Map<String, Models.Product> products = new ConcurrentHashMap<>();
    private final Map<String, Models.Article> articles = new ConcurrentHashMap<>();
    private final Map<String, Models.Scene> scenes = new LinkedHashMap<>();
    private final Map<String, Models.Slots> slots = new ConcurrentHashMap<>();
    private final Map<String, List<Models.CartLine>> carts = new ConcurrentHashMap<>();
    private final Map<String, String> profileOwners = new ConcurrentHashMap<>();
    private final AccountStore accountStore;
    private CommerceStore database;

    private final TraceBus bus;
    public CoreService(TraceBus bus) { this(bus, AccountStore.inMemory()); }
    public CoreService(TraceBus bus, AccountStore accountStore) { this(bus, accountStore, false); }
    public CoreService(TraceBus bus, AccountStore accountStore,
            @Value("${app.catalogue.expansion-enabled:false}") boolean expansionEnabled) {
        this(bus, accountStore, expansionEnabled, catalogueAssetCheck());
    }
    @Autowired public CoreService(TraceBus bus, AccountStore accountStore, CommerceStore database) {
        this(bus, accountStore, true, id -> true);
        database.initialize(products.values(),articles.values());
        database.reviseCatalogue(products.values());
        this.database=database;
    }
    CoreService(TraceBus bus, AccountStore accountStore, boolean expansionEnabled, Predicate<String> assetsReady) {
        this.bus=bus; this.accountStore=accountStore; seed(expansionEnabled, assetsReady); CatalogueRevision.apply(products,articles);
        for (var stored : accountStore.allOrders()) if (!"CANCELLED".equals(stored.order().status())) for (var line : stored.order().cart().lines()) {
            var product = products.get(line.productId());
            if (product != null) replaceStock(product, line.skuId(), line.quantity());
        }
    }
    void bindProfile(String scopedSession, String accountId) { profileOwners.put(scopedSession, accountId); }

    private static Predicate<String> catalogueAssetCheck() {
        Set<String> productImages = new HashSet<>(), garmentImages = new HashSet<>();
        return id -> {
            try {
                byte[] productBytes, garmentBytes;
                try (var image = new ClassPathResource("static/images/products/" + id + ".webp").getInputStream();
                     var garment = new ClassPathResource("static/images/tryon-garments/" + id + ".jpg").getInputStream()) {
                    productBytes = image.readAllBytes(); garmentBytes = garment.readAllBytes();
                }
                if (productBytes.length <= 1024 || !new String(productBytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                        || !new String(productBytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return false;
                var garmentImage = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(garmentBytes));
                if (garmentImage == null || garmentImage.getWidth() < 512 || garmentImage.getHeight() < 512) return false;
                var hash = java.security.MessageDigest.getInstance("SHA-256");
                return productImages.add(HexFormat.of().formatHex(hash.digest(productBytes)))
                    && garmentImages.add(HexFormat.of().formatHex(hash.digest(garmentBytes)));
            } catch (IOException | java.security.GeneralSecurityException e) { return false; }
        };
    }

    private void seed(boolean expansionEnabled, Predicate<String> assetsReady) {
        scenes.put("tang-furong", new Models.Scene("tang-furong", "芙蓉园唐", "唐", "适合盛唐纹样与暖色，正式游园可用披帛增加仪式感"));
        scenes.put("wall-dark", new Models.Scene("wall-dark", "城墙深色", "唐", "夜游城墙优先深色、利落轮廓与防风层次"));
        scenes.put("huaqing", new Models.Scene("huaqing", "华清宫端庄唐", "唐", "端庄、低饱和、合体但不紧身，减少过度装饰"));
        scenes.put("shuyuan", new Models.Scene("shuyuan", "书院门便利", "宋", "步行便利、轻量、袖口不拖地，宋制更克制"));
        scenes.put("dayanta", new Models.Scene("dayanta", "大雁塔大气唐", "唐", "大气轮廓与稳重色彩适合建筑背景"));
        String[] specs = {
            "唐·朱红齐胸襦裙|女装|唐|齐胸襦裙|盛唐纹样、朱红、游园|199",
            "唐·黛青圆领袍|中性|唐|圆领袍|深色、利落、防风|239",
            "宋·月白褙子|女装|宋|褙子|清雅、便利、轻量|169",
            "唐·玄黑大袖衫|女装|唐|大袖衫|显瘦、端庄、建筑|219",
            "唐·石榴红交领襦裙|女装|唐|交领襦裙|喜庆、层次、仪式|189",
            "宋风·青灰对襟长衫|男装|宋|对襟长衫|书院、克制、舒适|179",
            "唐·藕荷披帛套装|女装|唐|披帛|柔和、上镜、轻盈|209",
            "明·苍青曳撒|中性|明|曳撒|大气、稳重、利落|259",
            "宋风·茶白长衫|男装|宋|长衫|便利、素雅、透气|149",
            "唐·金棕半臂|配饰|唐|半臂|叠穿、暖色、点睛|89",
            "汉·绛红曲裾袍|女装|汉|曲裾袍|曲裾、交领、层次|249",
            "汉·玄色直裾袍|中性|汉|直裾袍|直裾、深色、稳重|239",
            "元·赭金辫线袍|男装|元|辫线袍|蒙古族服饰设计参考、窄袖、辫线|269",
            "明·月白袄马面裙|女装|明|袄马面裙|袄裙搭配、层次、马面裙|259",
            "汉·浅杏曲裾袍|女装|汉|曲裾袍|曲裾、浅杏、素雅|269|浅杏",
            "汉·黛蓝直裾袍|中性|汉|直裾袍|直裾、深色、稳重|259|黛蓝",
            "汉·茶金曲裾袍|女装|汉|曲裾袍|曲裾、茶金、层次|279|茶金",
            "宋·豆青褙子套裙|女装|宋|褙子套裙|褙子、豆青、清雅|219|豆青",
            "宋·藕荷围裹两片裙|女装|宋|围裹两片裙|围裹裙、藕荷、轻盈|189|藕荷",
            "宋·烟蓝褙子套裙|女装|宋|褙子套裙|褙子、烟蓝、素雅|229|烟蓝",
            "元·绀紫辫线袍|男装|元|辫线袍|蒙古族服饰设计参考、窄袖、辫线|299|绀紫",
            "元·黛蓝大袖袍|中性|元|大袖袍|元代多民族服饰设计参考、大袖、深色|329|黛蓝",
            "元·玄青辫线袍|男装|元|辫线袍|蒙古族服饰设计参考、辫线、深色|289|玄青",
            "明·豆绿短袖衫裙|女装|明|短袖衫裙|外短内长、豆绿、衫裙搭配|239|豆绿",
            "明·绛紫曳撒|中性|明|曳撒|交领右衽、腰下褶裥、稳重|289|绛紫",
            "明·杏黄长袖衫裙|女装|明|长袖衫裙|长袖衫、杏黄、褶裙|229|杏黄"
        };
        int i=1; for (String spec: specs) { String[] p=spec.split("\\|"); String id="p"+i++;
            if (p.length > 6 && !expansionEnabled) continue;
            if (expansionEnabled && !assetsReady.test(id)) throw new IllegalStateException("商品 " + id + " 缺少有效且独立的商品图或试穿参考。请补齐图片后启用 app.catalogue.expansion-enabled，或保持 false 使用已上线目录。");
            List<Models.Sku> sku=new ArrayList<>();
            String color = p[0].contains("玄黑")?"玄黑":p[0].contains("黛青")?"黛青":p[0].contains("苍青")?"苍青":p[0].contains("青灰")?"青灰":p[0].contains("白")?"月白":"暖红";
            if (id.equals("p11")) color = "绛红";
            if (id.equals("p12")) color = "玄色";
            if (id.equals("p13")) color = "赭金";
            if (p.length > 6) color = p[6];
            for(String size: new String[]{"S","M","L","XL"}) sku.add(new Models.Sku(id+"-"+size,color,size, size.equals("L")?8:12, new BigDecimal(p[5])));
            List<Models.SizeRow> chart=List.of(new Models.SizeRow("S",new Models.Range(150,160),new Models.Range(78,84),new Models.Range(60,68),new Models.Range(84,90)),new Models.SizeRow("M",new Models.Range(158,168),new Models.Range(84,90),new Models.Range(68,76),new Models.Range(90,96)),new Models.SizeRow("L",new Models.Range(166,176),new Models.Range(90,98),new Models.Range(76,84),new Models.Range(96,104)),new Models.SizeRow("XL",new Models.Range(174,185),new Models.Range(98,108),new Models.Range(84,94),new Models.Range(104,114)));
            List<String> suited = p[2].equals("唐") ? List.of("tang-furong","wall-dark","huaqing","dayanta") : List.of("shuyuan","wall-dark");
            String description = p[4] + "。现代演示商品与设计示意，朝代标签表示设计参考，不是馆藏实物或文物复原；颜色、模特和旅游场景不作为断代依据。";
            if (p.length > 6) {
                String reference = switch (id) {
                    case "p15", "p16", "p17" -> "形制参考：中国丝绸博物馆《汉魏印象》所述曲裾与直裾；配色及装饰为现代设计。";
                    case "p18", "p20" -> "形制参考：中国丝绸博物馆对南宋德安周氏墓褙子的研习资料；内搭、裙装与配色为现代组合。";
                    case "p19" -> "形制参考：中国丝绸博物馆对南宋德安周氏墓围裹式两片裙的研习资料；搭配的短衫为现代设计，不将两片裙等同于马面裙。";
                    case "p21", "p23" -> "形制参考：中国丝绸博物馆馆藏元代《辫线袍》；采用蒙古族服饰设计参考表述，不将元代所有服饰统称汉族形制。";
                    case "p22" -> "形制参考：中国丝绸博物馆《修复工作汇总》中的元代对鹰纹织金锦大袖袍；纹样为现代简化设计，不据此推定穿着者民族、性别或等级。";
                    case "p24", "p26" -> "形制参考：中国丝绸博物馆明初《绢短袖衫＆绮长袖衫＆绢裙》；按分层衫裙作现代组合，不将这套馆藏改称马面裙。";
                    case "p25" -> "形制参考：中国丝绸博物馆《明代曲水如意云纹暗花缎曳撒袍》；曳撒的蒙元影响不改变本站本款的明代参考归属。";
                    default -> throw new IllegalStateException("新增商品缺少来源说明：" + id);
                };
                description += reference + "本站标价、库存与尺码表为可体验购买流程的演示数据，非馆方商品或实测成衣规格。";
            }
            products.put(id,new Models.Product(id,p[0],p[1],p[2],p[3],description,suited,List.of(p[4].split("、")),List.of(color),List.of("/api/products/"+id+"/image"),p[2].equals("唐")&&!id.equals("p10")?List.of("p10"):List.of(),chart,sku)); }
        addArticle("a1","唐代服饰中的半臂与披帛","服饰史料","中国丝绸博物馆《胡汉之间：唐代丝绸服饰展》介绍，唐代女性可穿高腰裙，配短襦、半臂与披帛；男性服饰可见窄袖袍与半臂。这是展览所述服饰现象，不代表所有唐代场合统一穿法或礼仪规范。",Models.Kind.FACT,"中国丝绸博物馆｜胡汉之间：唐代丝绸服饰展｜https://www.chinasilkmuseum.com/yz/info_18.aspx?itemid=28162",List.of("唐","半臂","披帛"),"tang-exhibition-garments","layered-garments");
        addArticle("a4","汉代曲裾与直裾参考","服饰史料","中国丝绸博物馆《汉魏印象》展览资料介绍了上衣下裳相连的服装，并区分曲裾与直裾。目录中的汉风曲裾袍与直裾袍据此作设计参考；具体裁片、面料、纹样与穿着身份仍须逐件核对文物资料，不能仅凭颜色判断朝代。",Models.Kind.FACT,"中国丝绸博物馆｜汉魏印象｜https://www.chinasilkmuseum.com/zz/info_17.aspx?itemid=30762",List.of("汉","曲裾","直裾"),"han-design-reference","curved-and-straight-hem");
        addArticle("a5","南宋褙子实物研习","服饰史料","中国丝绸博物馆以南宋德安周氏墓出土褙子开展研习，提供了宋代服饰的实物参考。该资料不能直接证明本站青灰对襟长衫或茶白长衫的历史形制；两款均为宋风现代设计参考。",Models.Kind.FACT,"中国丝绸博物馆｜南宋德安周氏墓褙子研习｜https://www.chinasilkmuseum.com/yg/info_13.aspx?itemid=31106",List.of("宋","褙子"),"song-beizi-reference","dean-zhou-tomb");
        addArticle("a6","元代辫线袍馆藏参考","服饰史料","中国丝绸博物馆所藏元代《辫线袍》为交领右衽、窄袖，腰部装饰辫线。本站赭金辫线袍采用蒙古族服饰设计参考表述，不能把元代多民族服饰一概称为同一种汉服制度。",Models.Kind.FACT,"中国丝绸博物馆｜辫线袍｜https://www.chinasilkmuseum.com/zggd/info_21.aspx?itemid=1848；蒙古族服饰背景｜https://www.chinasilkmuseum.com/jz/info_15.aspx?itemid=27960",List.of("元","辫线袍","蒙古"),"yuan-braid-line-robe","narrow-sleeve-right-closing");
        addArticle("a7","明代曳撒的实物与图像证据","服饰史料","中国丝绸博物馆收藏有《明代曲水如意云纹暗花缎曳撒袍》。故宫博物院对《明宣宗行乐图》的介绍指出，画中宦官穿青绿等色曳撒，曳撒受到蒙元服饰影响。因此苍青曳撒归入本站明代设计参考目录，不归为唐制；颜色本身不能作为断代证据。",Models.Kind.FACT,"中国丝绸博物馆｜明代曲水如意云纹暗花缎曳撒袍｜https://www.chinasilkmuseum.com/zggd/info_21.aspx?itemid=1900；故宫博物院｜明宣宗行乐图｜https://www.dpm.org.cn/vr/stone-moat/wyd_39.html",List.of("明","曳撒","蒙元"),"yesa-catalogue-dynasty","ming");
        addArticle("a14","南宋围裹式两片裙的结构参考","服饰史料","中国丝绸博物馆介绍德安周氏墓出土的南宋穿枝飞鸟纹围裹式两片裙：两片裙片在穿着时相互交叠；同墓缠枝花纹罗镶边褙子为直领对襟、侧开衩。本站豆青与烟蓝褙子套裙、藕荷围裹两片裙属于据此提出的现代组合与配色，不是这些出土实物的复原。",Models.Kind.FACT,"中国丝绸博物馆｜南宋褙子、围裹式两片裙的裁剪与制作｜https://www.chinasilkmuseum.com/yg/info_13.aspx?itemid=31106",List.of("宋","围裹","两片裙","褙子"),"song-wrap-skirt-reference","dean-zhou-tomb-two-panels");
        addArticle("a15","元代大袖袍的修复记录","服饰史料","中国丝绸博物馆《修复工作汇总》在元代织金锦服饰修复项目中分别介绍鹦鹉纹织金锦辫线袍与对鹰纹织金锦大袖袍。这说明该组修复服饰中同时有大袖袍和辫线袍；不能仅以袖形判断穿着者民族、性别与礼仪等级。本站黛蓝大袖袍是现代演示设计，其配色和简化纹样不是文物复原。",Models.Kind.FACT,"中国丝绸博物馆｜修复工作汇总：元代织金锦服饰修复｜https://www.chinasilkmuseum.com/xscg/info_28.aspx?itemid=4312",List.of("元","大袖袍","织金锦"),"yuan-wide-sleeve-reference","museum-conservation-record");
        addArticle("a16","明初短袖衫、长袖衫与裙的组合","服饰史料","中国丝绸博物馆馆藏《绢短袖衫＆绮长袖衫＆绢裙》介绍一套明初女子服装：外穿短袖衫，内配长袖衫，下配绢裙。本站豆绿短袖衫裙与杏黄长袖衫裙以这类分层结构作为设计参考，颜色和搭配均属现代设计，不将馆藏直接改称袄马面裙。",Models.Kind.FACT,"中国丝绸博物馆｜绢短袖衫＆绮长袖衫＆绢裙｜https://www.chinasilkmuseum.com/zggd/info_21.aspx?itemid=1870",List.of("明","短袖衫","长袖衫","衫裙"),"ming-layered-shirt-skirt-reference","early-ming-layering");
    }
    private void addArticle(String id,String title,String topic,String content,Models.Kind kind,String source,List<String> keys,String ck,String cv){articles.put(id,new Models.Article(id,title,topic,content,kind,source,keys,ck,cv));}
    public Collection<Models.Product> products(String dynasty,String form,String scene,String size,String q){String supported=Dynasty.filter(dynasty);return (database==null?products.values():database.products(false)).stream().filter(p->supported==null||p.dynasty().equals(supported)).filter(p->form==null||p.form().contains(form)).filter(p->scene==null||p.scenes().contains(scene)).filter(p->size==null||p.sizeChart().stream().anyMatch(x->x.size().equalsIgnoreCase(size))).filter(p->q==null||p.name().contains(q)||p.tags().stream().anyMatch(q::contains)).sorted(Comparator.comparing(Models.Product::id)).toList();}
    public Models.Product product(String id){return database==null?Optional.ofNullable(products.get(id)).orElseThrow():database.product(id,false);}
    public Collection<Models.Scene> scenes(){return scenes.values();}
    public Collection<Models.Article> articles(){return database==null?articles.values():database.articles();}
    public List<Models.Article> references(String product) { return database==null?List.of():database.references(product); }
    public Models.KnowledgeResult searchKnowledge(String q){
        String query = q == null ? "" : q.trim();
        List<Models.KnowledgeHit> hits=articles().stream().map(a -> new Models.KnowledgeHit(a, knowledgeScore(a, query))).filter(h->h.score()>=.5)
            .sorted(Comparator.comparingDouble(Models.KnowledgeHit::score).reversed().thenComparing(h->h.article().id())).toList();
        boolean conflict=hits.stream().collect(Collectors.groupingBy(h->h.article().claimKey(),Collectors.mapping(h->h.article().claimValue(),Collectors.toSet()))).values().stream().anyMatch(v->v.size()>1);
        boolean abstain=hits.isEmpty()||conflict;
        return new Models.KnowledgeResult(abstain,abstain?(conflict?"知识冲突，暂不作答":"证据不足，暂不作答"):"",query.contains("正式")||query.contains("祭祀")||query.contains("婚礼"),hits);
    }
    private double knowledgeScore(Models.Article article, String query) {
        if (query.isBlank()) return 0;
        if (Dynasty.supports(query)) return article.keywords().contains(query) ? 1 : 0;
        if (Dynasty.explicitIn(query).filter(article.keywords()::contains).isPresent()) return 1;
        if (article.title().contains(query)) return 1;
        return article.keywords().stream().filter(query::contains).count() / (double) article.keywords().size();
    }
    public Models.Article add(Models.KnowledgeAdd in){String id="a_"+UUID.randomUUID(); Models.Article a=new Models.Article(id,in.title(),in.topic(),in.content(),in.kind(),in.source(),in.keywords(),in.claimKey(),in.claimValue());if(database==null)articles.put(id,a);else database.tx(()->{database.saveArticle(a);return null;}); trace("ops","KNOWLEDGE_ADDED",id); return a;}

    private final Map<String,Models.Body> bodies=new ConcurrentHashMap<>();
    public Models.Body body(String sid){return bodies.get(profileOwners.getOrDefault(WebSupport.session(sid),sid));}
    public void body(String sid,Models.Body b){body(sid,b,sid);}
    public void body(String owner,Models.Body b,String traceSession){WebSupport.session(traceSession);bodies.put(profileOwners.getOrDefault(WebSupport.session(owner),owner),b);trace(traceSession,"BODY_UPDATED","资料已更新，不记录数值");}
    public void deleteBody(String sid){deleteBody(sid,sid);}
    public void deleteBody(String owner,String traceSession){WebSupport.session(traceSession);bodies.remove(profileOwners.getOrDefault(WebSupport.session(owner),owner));trace(traceSession,"BODY_DELETED","资料已删除");}
    private Double number(String text,String pattern,Double old){var m=java.util.regex.Pattern.compile(pattern).matcher(text);return m.find()?Double.valueOf(m.group(1)):old;}
    public synchronized Models.AgentReply chat(Models.Chat in){
        String sid=WebSupport.session(in.sessionId()), text=in.message();
        Models.Slots old=slots.getOrDefault(sid,new Models.Slots(null,null,null,null,false,false));
        String scene=old.scene(), dynasty=old.dynasty(), style=old.style();
        String[] names={"芙蓉园","城墙","华清宫","书院门","大雁塔"};int n=0;
        for(var e:scenes.entrySet()){if(text.contains(names[n++])){scene=e.getKey();dynasty=e.getValue().dynasty();style=e.getValue().advice();}}
        String explicitDynasty = Dynasty.explicitIn(text).orElse(null);
        if (explicitDynasty != null) { dynasty = explicitDynasty; style = explicitDynasty + "代设计参考"; }
        boolean muted=old.muted()||text.contains("太亮")||text.contains("深色")||text.contains("低调")||"wall-dark".equals(scene);
        boolean slim=old.slim()||text.contains("显瘦");
        Boolean first=old.firstWear();
        if(text.contains("不是第一次")||text.contains("非首次"))first=Boolean.FALSE;
        else if(text.contains("第一次")||text.contains("首次"))first=Boolean.TRUE;
        Models.Body b=bodies.getOrDefault(sid,new Models.Body(null,null,null,null,null,false));
        Double h=number(text,"(?:身高\\s*)?(\\d{3}(?:\\.\\d+)?)\\s*(?:cm|厘米)",b.height());
        h=number(text,"身高\\s*(\\d{3}(?:\\.\\d+)?)",h);
        Double weight=number(text,"(\\d+(?:\\.\\d+)?)\\s*(?:kg|公斤)",b.weightKg());Double jin=number(text,"(\\d+(?:\\.\\d+)?)\\s*斤",null);if(jin!=null)weight=jin/2;
        Models.Body updated=new Models.Body(h,weight,number(text,"胸围\\s*(\\d+(?:\\.\\d+)?)",b.chest()),number(text,"腰围\\s*(\\d+(?:\\.\\d+)?)",b.waist()),number(text,"臀围\\s*(\\d+(?:\\.\\d+)?)",b.hip()),text.contains("宽松")||b.loose());
        if((h!=null&&(h<80||h>230))||(weight!=null&&(weight<20||weight>250)))throw new IllegalArgumentException("身高或体重超出支持范围");
        bodies.put(sid,updated);
        Models.Slots next=new Models.Slots(scene,dynasty,style,first,muted,slim);slots.put(sid,next);
        Models.KnowledgeResult kr=searchKnowledge(text);trace(sid,"AGENT_CHAT","离线决策");
        if(kr.abstained())trace(sid,"KNOWLEDGE_GAP","证据不足或冲突，不存储原始提问");if(kr.humanRequired())trace(sid,"HANDOFF","正式礼仪需人工核验");
        List<String> missing=new ArrayList<>();if(scene==null)missing.add("scene");if(first==null)missing.add("firstWear");if(h==null)missing.add("height");if(weight==null)missing.add("weightKg");
        List<Models.Product> all=products.values().stream().filter(p->!p.category().equals("配饰")).sorted(Comparator.comparing(Models.Product::id)).toList();
        if(!missing.isEmpty())return new Models.AgentReply(sid,"NEED_SLOT","请一次补充以下缺失信息："+String.join("、",missing),next,missing,new Models.Funnel(all.size(),0,0,0,0),List.of(),kr);
        final String sc=scene, dy=dynasty;
        // 换朝代是显式覆盖场景默认朝代；仍保留地点槽位并说明适配为通用建议。
        List<Models.Product> byScene=all.stream().filter(p->p.scenes().contains(sc)||!scenes.get(sc).dynasty().equals(dy)).toList();
        List<Models.Product> byStyle=byScene.stream().filter(p->p.dynasty().equals(dy)).filter(p->!muted||p.colors().stream().anyMatch(c->List.of("玄黑","玄色","黛青","苍青","青灰").contains(c))).filter(p->!slim||p.tags().stream().anyMatch(t->List.of("显瘦","深色","稳重","克制").contains(t))).toList();
        List<Models.Product> available=byStyle.stream().filter(p->p.skus().stream().anyMatch(s->s.stock()>0)).toList();
        List<Models.Recommendation> rec=available.stream().limit(3).map(p->new Models.Recommendation(p,advice(p,updated),p.accessoryIds().stream().map(products::get).toList(),List.of("商品标签："+p.tags(),"场景建议："+scenes.get(sc).advice(),"尺码为区间算法建议，非人体识别"))).toList();
        trace(sid,"RECOMMENDATION","推荐数量="+rec.size());
        return new Models.AgentReply(sid,kr.humanRequired()?"HANDOFF":"DONE",kr.humanRequired()?"正式礼仪已转人工；以下为商品参考":"离线算法推荐；缺少围度时请先核对尺码表",next,List.of(),new Models.Funnel(all.size(),byScene.size(),byStyle.size(),available.size(),rec.size()),rec,kr);
    }
    public Models.SizeAdvice advice(Models.Product p, Models.Body b){
        if(b==null)b=new Models.Body(null,null,null,null,null,false);
        List<String> missing=new ArrayList<>();if(b.height()==null)missing.add("height");if(b.chest()==null)missing.add("chest");if(b.waist()==null)missing.add("waist");if(b.hip()==null)missing.add("hip");
        final Models.Body v=b;
        Models.SizeRow best=p.sizeChart().stream().filter(s->(v.height()==null||s.height().contains(v.height()))&&(v.chest()==null||s.chest().contains(v.chest()))&&(v.waist()==null||s.waist().contains(v.waist()))&&(v.hip()==null||s.hip().contains(v.hip()))).findFirst().orElse(null);
        if(best==null||missing.size()==4)return new Models.SizeAdvice(null,"低","没有可验证的匹配尺码，请人工量体；不以体重推测围度",missing,false);
        int index=p.sizeChart().indexOf(best);if(b.loose())index=Math.min(index+1,p.sizeChart().size()-1);
        return new Models.SizeAdvice(p.sizeChart().get(index).size(),missing.isEmpty()?"高":"低","所有已知身高/胸腰臀须同时落入尺码区间，取最小匹配码；宽松可上调一级；非合身保证",missing,true);
    }

    public Models.Cart cart(String sid){if(database!=null)return database.cart(WebSupport.session(sid));List<Models.CartLine> lines=carts.getOrDefault(WebSupport.session(sid),List.of()); return cartOf(lines);}
    private Models.Cart cartOf(List<Models.CartLine> lines){return new Models.Cart(List.copyOf(lines),lines.stream().mapToInt(Models.CartLine::quantity).sum(),lines.stream().map(Models.CartLine::subtotal).reduce(BigDecimal.ZERO,BigDecimal::add));}
    public Models.Cart addCart(String sid,Models.CartAdd in){return addCart(sid,in,sid);}
    public synchronized Models.Cart addCart(String owner,Models.CartAdd in,String traceSession){
        if(database!=null)return database.addCart(owner,in);
        WebSupport.session(owner);WebSupport.session(traceSession);
        if(in.quantity()<1||in.quantity()>99)throw new IllegalArgumentException("数量须为1至99");
        Models.Product p=product(in.productId());Models.Sku s=p.skus().stream().filter(x->x.id().equals(in.skuId())).findFirst().orElseThrow();
        if(s.stock()<in.quantity())throw new IllegalStateException("库存不足");
        List<Models.CartLine> lines=new ArrayList<>(carts.getOrDefault(owner,List.of()));lines.removeIf(x->x.skuId().equals(s.id()));
        lines.add(new Models.CartLine(UUID.randomUUID().toString(),p.id(),p.name(),s.id(),s.color(),s.size(),in.quantity(),s.price(),s.price().multiply(BigDecimal.valueOf(in.quantity()))));
        carts.put(owner,lines);trace(traceSession,"CART_CHANGED","衣裳已加入衣囊");return cartOf(lines);
    }
    public Models.Cart updateCart(String sid,String lineId,int q){return updateCart(sid,lineId,q,sid);}
    public synchronized Models.Cart updateCart(String owner,String lineId,int q,String traceSession){
        if(database!=null)return database.updateCart(owner,lineId,q);
        WebSupport.session(owner);WebSupport.session(traceSession);
        if(q<1||q>99)throw new IllegalArgumentException("数量须为1至99");
        List<Models.CartLine> lines=new ArrayList<>(carts.getOrDefault(owner,List.of()));int idx=IntStream.indexOf(lines,lineId);if(idx<0)throw new NoSuchElementException();
        Models.CartLine line=lines.get(idx);Models.Sku sku=product(line.productId()).skus().stream().filter(s->s.id().equals(line.skuId())).findFirst().orElseThrow();
        if(sku.stock()<q)throw new IllegalStateException("库存不足");
        lines.set(idx,new Models.CartLine(line.id(),line.productId(),line.productName(),line.skuId(),line.color(),line.size(),q,sku.price(),sku.price().multiply(BigDecimal.valueOf(q))));
        carts.put(owner,lines);trace(traceSession,"CART_CHANGED","衣囊数量已更新");return cartOf(lines);
    }
    static final class IntStream {static int indexOf(List<Models.CartLine> l,String id){for(int i=0;i<l.size();i++)if(l.get(i).id().equals(id))return i;return -1;}}
    public void deleteCart(String sid,String id){deleteCart(sid,id,sid);}
    public synchronized void deleteCart(String owner,String id,String traceSession){
        if(database!=null){database.deleteCart(owner,id);return;}
        WebSupport.session(owner);WebSupport.session(traceSession);
        List<Models.CartLine> lines=new ArrayList<>(carts.getOrDefault(owner,List.of()));
        if(lines.removeIf(x->x.id().equals(id))){carts.put(owner,lines);trace(traceSession,"CART_CHANGED","衣裳已移出衣囊");}
    }

    public Models.Order order(String sid){return order(sid,sid,new Models.Checkout("演示收货人","00000000","演示地区","演示地址",UUID.randomUUID().toString()));}
    public Models.Order order(String accountId,String externalSession,Models.Checkout request){return order(accountId,externalSession,request,accountId);}
    public synchronized Models.Order order(String accountId,String externalSession,Models.Checkout request,String traceSession){
        if(database!=null)return database.checkout(accountId,externalSession,request);
        WebSupport.session(accountId); WebSupport.session(externalSession); WebSupport.session(traceSession);
        var saved=accountStore.byKey(accountId,request.idempotencyKey());
        if(saved!=null){
            var previous=saved.order();
            if(!previous.recipient().equals(request.recipient().trim())||!previous.phone().equals(request.phone().trim())||!previous.region().equals(request.region().trim())||!previous.address().equals(request.address().trim()))
                throw new IllegalStateException("相同提交标识对应了不同收货信息，请重新确认订单");
            return previous;
        }
        Models.Cart original=cart(accountId);if(original.lines().isEmpty())throw new IllegalStateException("购物车为空");
        List<Models.CartLine> checked=new ArrayList<>();
        Map<String,Models.Product> nextProducts=new HashMap<>();
        for(var line:original.lines()){
            var p=nextProducts.getOrDefault(line.productId(),product(line.productId()));
            var sku=p.skus().stream().filter(s->s.id().equals(line.skuId())).findFirst().orElseThrow();
            if(sku.stock()<line.quantity())throw new IllegalStateException("库存不足，请调整购物车");
            checked.add(new Models.CartLine(line.id(),p.id(),p.name(),sku.id(),sku.color(),sku.size(),line.quantity(),sku.price(),sku.price().multiply(BigDecimal.valueOf(line.quantity()))));
            nextProducts.put(p.id(),withReducedStock(p,sku.id(),line.quantity()));
        }
        Instant now=Instant.now();String id=UUID.randomUUID().toString();
        String number="HYJ"+java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(now)+id.substring(0,8).toUpperCase(Locale.ROOT);
        var order=new Models.Order(id,externalSession,"PENDING_PAYMENT",true,cartOf(checked),now,number,request.recipient().trim(),request.phone().trim(),request.region().trim(),request.address().trim(),null,null,null);
        accountStore.addOrder(new AccountStore.StoredOrder(accountId,request.idempotencyKey(),order));
        // All potentially failing validation/serialization/I/O is complete before these in-memory updates.
        products.putAll(nextProducts);carts.remove(accountId);
        trace(traceSession,"ORDER_CREATED","订单已创建，等待选择模拟支付方式");return order;
    }
    public Models.Order payOrder(String accountId,String id,Models.Payment payment){return payOrder(accountId,id,payment,accountId);}
    public synchronized Models.Order payOrder(String accountId, String id, Models.Payment payment, String traceSession) {
        if(database!=null)return database.transitionOrder(accountId,id,payment==null?null:payment.paymentMethod(),false);
        WebSupport.session(traceSession);
        var previous = orderById(accountId, id);
        if (payment == null || payment.paymentMethod() == null || !Set.of("ALIPAY","WECHAT","BALANCE").contains(payment.paymentMethod()))
            throw new IllegalArgumentException("请选择支付宝、微信支付或余额支付");
        // The persisted order itself is the idempotency key: a retry cannot charge or deduct stock twice.
        if ("DEMO_PAID".equals(previous.status())) return previous;
        if (!"PENDING_PAYMENT".equals(previous.status())) throw new IllegalStateException("订单已取消，无法继续支付");
        var paid = new Models.Order(previous.id(), previous.sessionId(), "DEMO_PAID", true, previous.cart(),
            previous.createdAt(), previous.orderNumber(), previous.recipient(), previous.phone(), previous.region(),
            previous.address(), payment.paymentMethod(), Instant.now(), null);
        accountStore.updateOrder(accountId, paid);
        trace(traceSession, "ORDER_DEMO_PAID", "模拟支付成功，无实际扣款");
        return paid;
    }
    public Models.Order cancelOrder(String accountId,String id){return cancelOrder(accountId,id,accountId);}
    public synchronized Models.Order cancelOrder(String accountId, String id, String traceSession) {
        if(database!=null)return database.transitionOrder(accountId,id,null,true);
        WebSupport.session(traceSession);
        var previous = orderById(accountId, id);
        if ("CANCELLED".equals(previous.status())) return previous;
        if (!"PENDING_PAYMENT".equals(previous.status())) throw new IllegalStateException("订单已完成模拟支付，不能取消待支付订单");
        var nextProducts = new HashMap<String, Models.Product>();
        for (var line : previous.cart().lines()) {
            var product = nextProducts.getOrDefault(line.productId(), product(line.productId()));
            nextProducts.put(product.id(), withReducedStock(product, line.skuId(), -line.quantity()));
        }
        var cancelled = new Models.Order(previous.id(), previous.sessionId(), "CANCELLED", true, previous.cart(),
            previous.createdAt(), previous.orderNumber(), previous.recipient(), previous.phone(), previous.region(),
            previous.address(), null, null, Instant.now());
        accountStore.updateOrder(accountId, cancelled);
        products.putAll(nextProducts);
        trace(traceSession, "ORDER_CANCELLED", "待支付订单已取消，预留库存已释放");
        return cancelled;
    }
    private Models.Product withReducedStock(Models.Product p,String skuId,int quantity){
        var skus=p.skus().stream().map(s->s.id().equals(skuId)?new Models.Sku(s.id(),s.color(),s.size(),Math.max(0,s.stock()-quantity),s.price()):s).toList();
        return new Models.Product(p.id(),p.name(),p.category(),p.dynasty(),p.form(),p.description(),p.scenes(),p.tags(),p.colors(),p.images(),p.accessoryIds(),p.sizeChart(),skus);
    }
    private void replaceStock(Models.Product product,String skuId,int quantity){products.put(product.id(),withReducedStock(product,skuId,quantity));}
    public List<Models.Order> orders(String accountId){return accountStore.orders(WebSupport.session(accountId));}
    public Models.Order orderById(String accountId,String id){return orders(accountId).stream().filter(o->o.id().equals(id)).findFirst().orElseThrow();}
    public Models.TraceEvent trace(String sid,String type,String summary){return bus.publish(sid,type,summary);}
    public List<Models.TraceEvent> traces(String sid){return bus.events(sid,0);}
}
