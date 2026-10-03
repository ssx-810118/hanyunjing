package com.hanyunjing;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 外部契约统一集中，所有金额单位为人民币元，围度单位为厘米。 */
public final class Models {
    private Models() {}
    public record Api<T>(int code, String message, T data) {
        public static <T> Api<T> ok(T data) { return new Api<>(0, "ok", data); }
    }
    public record Range(double min, double max) { public boolean contains(double n) { return n >= min && n <= max; } }
    public record SizeRow(String size, Range height, Range chest, Range waist, Range hip) {}
    public record Sku(String id, String color, String size, int stock, BigDecimal price) {}
    public record Product(String id, String name, String category, String dynasty, String form, String description,
                          List<String> scenes, List<String> tags, List<String> colors, List<String> images,
                          List<String> accessoryIds, List<SizeRow> sizeChart, List<Sku> skus) {}
    public record Scene(String id, String name, String dynasty, String advice) {}
    public enum Kind { FACT, COMMON }
    public record Article(String id, String title, String topic, String content, Kind kind, String source,
                          List<String> keywords, String claimKey, String claimValue) {}
    public record KnowledgeHit(Article article, double score) {}
    public record KnowledgeResult(boolean abstained, String reason, boolean humanRequired, List<KnowledgeHit> hits) {}
    public record KnowledgeAdd(@NotBlank @Size(max=120) String title, @NotBlank String topic,
                               @NotBlank @Size(max=6000) String content, @NotNull Kind kind,
                               @NotBlank String source, @NotEmpty List<@NotBlank String> keywords,
                               @NotBlank String claimKey, @NotBlank String claimValue) {}
    public record Body(@DecimalMin("80") @DecimalMax("230") Double height,
                       @DecimalMin("20") @DecimalMax("250") Double weightKg,
                       @DecimalMin("40") @DecimalMax("180") Double chest,
                       @DecimalMin("35") @DecimalMax("180") Double waist,
                       @DecimalMin("40") @DecimalMax("200") Double hip, boolean loose) {}
    public record SizeAdvice(String size, String confidence, String algorithm, List<String> missingFields, boolean inRange) {}
    public record Chat(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String sessionId,
                       @NotBlank @Size(max=2000) String message, RetailRules.Requirements requirements) {
        public Chat(String sessionId,String message){this(sessionId,message,null);}
    }
    public record Slots(String scene, String dynasty, String style, Boolean firstWear, boolean muted, boolean slim) {}
    public record Recommendation(Product product, SizeAdvice sizeAdvice, List<Product> accessories, List<String> reasons,
                                 List<Sku> eligibleSkus,List<Article> evidence) {
        public Recommendation(Product p,SizeAdvice s,List<Product> a,List<String> r){this(p,s,a,r,List.of(),List.of());}
    }
    public record Funnel(int total, int sceneMatched, int styleMatched, int available, int returned) {}
    public record AgentReply(String sessionId, String status, String message, Slots slots, List<String> missingFields,
                             Funnel funnel, List<Recommendation> recommendations, KnowledgeResult knowledge,
                             RetailRules.Requirements requirements,String workflowId) {
        public AgentReply(String s,String state,String m,Slots slots,List<String> missing,Funnel f,List<Recommendation> r,KnowledgeResult k){this(s,state,m,slots,missing,f,r,k,RetailRules.Requirements.empty(),null);}
    }
    public record TraceEvent(long id, String sessionId, String type, String summary, Instant timestamp) {}
    public record Portrait(String id, String sessionId, int width, int height, String format, Instant expiresAt) {}
    public record Generate(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String sessionId,
                           @NotBlank String portraitId, @NotBlank String productId, @NotBlank String skuId) {}
    public record TryOn(String id, String sessionId, String productId, String skuId, String status, String stage,
                        int attempts, boolean demo, String resultUrl, List<String> checks, Instant expiresAt,
                        String originalUrl, String error) {}
    public record CartAdd(@NotBlank String productId, @NotBlank String skuId, @Min(1) @Max(99) int quantity) {}
    public record Quantity(@Min(1) @Max(99) int quantity) {}
    public record CartLine(String id, String productId, String productName, String skuId, String color, String size,
                           int quantity, BigDecimal unitPrice, BigDecimal subtotal) {}
    public record Cart(List<CartLine> lines, int quantity, BigDecimal total) {}
    public record User(String id, String username, String displayName) {}
    public record AuthSession(boolean authenticated, User user, String csrfToken) {}
    public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,32}") String username,
                           @NotBlank @Size(min=8,max=72) String password, @Size(max=40) String displayName) {}
    public record Login(@NotBlank @Size(max=32) String username, @NotBlank @Size(max=72) String password) {}
    public record Checkout(@NotBlank @Size(max=40) String recipient,
                           @NotBlank @Pattern(regexp="[+0-9() -]{6,24}") String phone,
                           @NotBlank @Size(max=100) String region,
                           @NotBlank @Size(max=200) String address,
                           @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String idempotencyKey) {}
    public record Payment(@NotBlank @Pattern(regexp="ALIPAY|WECHAT|BALANCE") String paymentMethod) {}
    public record Order(String id, String sessionId, String status, boolean demo, Cart cart, Instant createdAt,
                        String orderNumber, String recipient, String phone, String region, String address,
                        String paymentMethod, Instant paidAt, Instant cancelledAt) {}
    public record Handoff(@NotBlank @Size(max=300) String reason) {}
}
