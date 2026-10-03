package com.hanyunjing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/support")
public class SupportController {
    private final SupportTicketService tickets;
    private final CoreService core;
    private final AccountStore accounts;
    public SupportController(SupportTicketService tickets, CoreService core, AccountStore accounts) { this.tickets=tickets; this.core=core; this.accounts=accounts; }
    public record Inquiry(@NotBlank @Size(max=64) String clientId, @NotBlank @Size(max=30) String category, @Size(max=40) String productId, @Size(max=60) String orderId, @NotBlank @Size(min=5,max=1000) String message) {}
    private Models.User user() { var user=AuthService.currentUser(); if(user==null) throw new AuthService.Failure(401,"请先登录后再查看或提交留言"); return user; }
    @GetMapping("/tickets") public Models.Api<List<SupportTicketService.Ticket>> list() { return Models.Api.ok(tickets.list(user().id())); }
    @PostMapping("/tickets") public Models.Api<SupportTicketService.Ticket> submit(@Valid @RequestBody Inquiry input) {
        var user=user();
        String productId=input.productId()==null || input.productId().isBlank()?null:input.productId().trim();
        String orderId=input.orderId()==null || input.orderId().isBlank()?null:input.orderId().trim();
        if(productId!=null) core.product(productId);
        if(orderId!=null && accounts.orders(user.id()).stream().noneMatch(order->order.id().equals(orderId))) throw new AuthService.Failure(404,"未找到当前账号的关联订单");
        return Models.Api.ok(tickets.create(user.id(),input.clientId(),input.category(),productId,orderId,input.message()));
    }
}
