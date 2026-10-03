package com.hanyunjing;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Product-specific chart matching; no model, image provider, or profile writes. */
@RestController
@RequestMapping("/api/products")
public class SizeAssistantController {
    private final CoreService core;
    public SizeAssistantController(CoreService core) { this.core = core; }
    public record SizeRequest(@Valid Models.Body measurements, boolean useSavedProfile) {}

    @PostMapping("/{productId}/size-advice")
    public ResponseEntity<Models.Api<Models.SizeAdvice>> advise(
            @PathVariable String productId, @RequestParam String sessionId,
            @Valid @RequestBody SizeRequest in) {
        String scoped = AuthService.scope(sessionId);
        if (in.useSavedProfile() && in.measurements() != null)
            throw new IllegalArgumentException("请选择本次填写或已保存资料中的一种来源");
        if (!in.useSavedProfile() && in.measurements() == null)
            throw new IllegalArgumentException("请填写已知尺寸，或选择使用已保存资料");
        Models.Body measurements = in.useSavedProfile()
            ? core.body(AuthService.owner(sessionId)) : in.measurements();
        Models.SizeAdvice result = core.advice(core.product(productId), measurements);
        core.trace(scoped, "SIZE_ADVICE", "已核对当前商品尺码表，不记录身材数值");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Models.Api.ok(result));
    }
}
