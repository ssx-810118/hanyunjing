package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.util.*;

/** Versioned, sourced editorial changes. Live prices and stock are never reset. */
final class CatalogueRevision {
    record ProductEdit(String id,String name,String form,String color,String category,List<String> tags,String reference) {}
    record Revision(List<ProductEdit> products,List<Models.Article> articles) {}
    static Revision load() {
        try(var in=new ClassPathResource("catalogue-v4.json").getInputStream()) {
            return new ObjectMapper().readValue(in,Revision.class);
        }catch(Exception e){throw new IllegalStateException("服饰史料修订数据读取失败",e);}
    }
    static void apply(Map<String,Models.Product> products,Map<String,Models.Article> articles) {
        var revision=load();
        for(var e:revision.products()) {
            var p=products.get(e.id());if(p==null)continue;
            String description=e.reference()+" 现代商品设计示意，不是馆藏实物或文物复原；颜色不作为断代依据。标价、库存与尺码表沿用本站演示数据，并非实测文物参数。";
            var skus=p.skus().stream().map(s->new Models.Sku(s.id(),e.color(),s.size(),s.stock(),s.price())).toList();
            products.put(p.id(),new Models.Product(p.id(),e.name(),e.category(),p.dynasty(),e.form(),description,p.scenes(),e.tags(),List.of(e.color()),p.images(),p.accessoryIds(),p.sizeChart(),skus));
        }
        for(var a:revision.articles()) articles.put(a.id(),a);
    }
}
