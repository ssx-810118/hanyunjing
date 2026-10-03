package com.hanyunjing;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic purchase constraints, not an AI answer generator. */
public final class RetailRules {
    private RetailRules() {}
    public record Requirements(BigDecimal budget, String size, String color, int quantity) {
        public static Requirements empty() { return new Requirements(null,null,null,1); }
    }
    public static Requirements validate(Requirements in) {
        if(in==null)return Requirements.empty();
        if(in.budget()!=null && (in.budget().signum()<=0 || in.budget().compareTo(new BigDecimal("999999.99"))>0 || in.budget().stripTrailingZeros().scale()>2))
            throw new IllegalArgumentException("预算须大于0，最多两位小数");
        if(in.quantity()<1||in.quantity()>99)throw new IllegalArgumentException("购买数量须为1至99件");
        String size=clean(in.size()),color=clean(in.color());
        if(size!=null && !size.matches("[A-Za-z0-9一均码-]{1,20}"))throw new IllegalArgumentException("请选择或填写商品尺码标签");
        if(color!=null && !color.matches("[\\p{IsHan}A-Za-z -]{1,20}"))throw new IllegalArgumentException("颜色请填写20字以内的颜色名称");
        return new Requirements(in.budget(),size==null?null:size.toUpperCase(Locale.ROOT),color,in.quantity());
    }
    static String clean(String text) { return text==null||text.isBlank()?null:text.trim(); }
    // Conservative text guard: explicit money/size changes override previous form values.
    // Other preferences remain model decisions; unsupported monetary syntax must be confirmed in the form.
    public static Requirements resolve(String text, Requirements previous, Requirements form) {
        Requirements base=validate(form!=null?form:previous);
        String q=Normalizer.normalize(text,Normalizer.Form.NFKC);
        BigDecimal budget=base.budget();String size=base.size(),color=base.color();int quantity=base.quantity();
        boolean unlimited=q.matches("(?s).*(预算不限|不限预算|取消预算|不限制预算|(?:没|未|尚未)确定预算|预算未定).*");
        if(unlimited)budget=null;
        var amounts=Pattern.compile("(?:预算|不超过|最多花|上限|最多)(?:改为|改成|调整为|是|为|到|约|大概|只要|只有|不超过|最多|控制在|在|:|：|\\s)*([0-9]+(?:\\.[0-9]+)?)(?:\\s*(?:元|块|人民币))?|([0-9]+(?:\\.[0-9]+)?)\\s*(?:元|块)(?:以内|以下|之内|封顶)").matcher(q);
        if (q.matches("(?s).*(预算|元|块|上限|不超过).*") && Pattern.compile("[0-9]+(?:\\.[0-9]+)?\\s*(?:元|块)?\\s*(?:-|~|—|–|至|到)\\s*[0-9]+(?:\\.[0-9]+)?").matcher(q).find())
            throw new IllegalArgumentException("预算区间请在预算栏填写一个本款衣裳的总金额");
        if(q.matches("(?s).*(?:衣裳和配饰|含配饰|整套合计|配饰合计).*(?:预算|元).*"))
            throw new IllegalArgumentException("当前预算仅核对本款衣裳，请单独填写衣裳预算；配饰需另选");
        while(amounts.find())budget=new BigDecimal(amounts.group(1)==null?amounts.group(2):amounts.group(1));
        if(q.matches("(?s).*(尺码不限|不限尺码|取消尺码).*"))size=null;
        var sizes=Pattern.compile("(?i)(?<![A-Z])((?:X{0,3}[SML])|均码)\\s*码|尺码(?:改为|改成|是|为|:|：|\\s)*((?:X{0,3}[SML])|均码)(?![A-Z])").matcher(q);
        while(sizes.find())size=(sizes.group(1)==null?sizes.group(2):sizes.group(1)).toUpperCase(Locale.ROOT);
        if(q.matches("(?s).*(颜色不限|不限颜色|取消颜色).*"))color=null;
        var count=Pattern.compile("(?:买|购买|数量(?:为|是|:|：)?|要)\\s*([0-9]{1,3})\\s*件").matcher(q);
        while(count.find())quantity=Integer.parseInt(count.group(1));
        // Never quietly ignore an explicit but unparsed budget, e.g. Chinese numerals or ranges.
        if(q.contains("预算") && !unlimited) {
            amounts.reset();
            if(!amounts.find() && !q.matches("(?s).*(保留.{0,4}预算|预算不变|预算内|不超预算|低于预算).*"))throw new IllegalArgumentException("请将金额写成“预算300元”，或只在预算栏填写具体金额后继续");
        }
        return validate(new Requirements(budget,size,color,quantity));
    }
    public static List<Models.Sku> eligible(Models.Product p,Requirements requirements,Models.SizeAdvice local) {
        Requirements r=validate(requirements);
        String size=r.size()!=null?r.size():local!=null&&local.inRange()?local.size():null;
        if(r.size()==null && local!=null && !local.inRange() && local.missingFields().size()<4)return List.of();
        return p.skus().stream().filter(s->s.stock()>=r.quantity())
            .filter(s->size==null||s.size().equalsIgnoreCase(size))
            .filter(s->r.color()==null||s.color().equalsIgnoreCase(r.color()))
            .filter(s->r.budget()==null||s.price().multiply(BigDecimal.valueOf(r.quantity())).compareTo(r.budget())<=0)
            .sorted(Comparator.comparing(Models.Sku::price).thenComparing(Models.Sku::id)).toList();
    }
}
