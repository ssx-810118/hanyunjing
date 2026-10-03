package com.hanyunjing;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class RetailController {
    private final RetailWorkflow workflow;private final AdminAccess access;private final OnlineAgent agent;
    public RetailController(RetailWorkflow workflow,AdminAccess access,OnlineAgent agent){this.workflow=workflow;this.access=access;this.agent=agent;}
    private String owner(){var user=AuthService.currentUser();if(user==null)throw new AuthService.Failure(401,"请先登录");return user.id();}
    @GetMapping("/retail/runs") public Models.Api<List<Map<String,Object>>> mine(){return Models.Api.ok(workflow.list(owner()));}
    @PostMapping("/retail/runs/{id}/confirm") public Models.Api<Models.Cart> confirm(@PathVariable String id,@RequestBody RetailWorkflow.Confirm input){return Models.Api.ok(workflow.confirm(id,owner(),input));}
    @PostMapping("/retail/runs/{id}/help") public Models.Api<Void> help(@PathVariable String id){workflow.requestHelp(id,owner());return Models.Api.ok(null);}
    @GetMapping("/admin/retail/runs") public Models.Api<List<Map<String,Object>>> runs(){access.require();return Models.Api.ok(workflow.list(null));}
    @GetMapping("/admin/retail/metrics") public Models.Api<Map<String,Object>> metrics(){access.require();return Models.Api.ok(workflow.metrics());}
    @PostMapping("/admin/retail/evaluate") public Models.Api<Models.AgentReply> evaluate(@jakarta.validation.Valid @RequestBody Models.Chat input){
        String account=access.require();
        return Models.Api.ok(agent.chat(new Models.Chat(AuthService.scope(input.sessionId()),input.message(),input.requirements()),account,true));
    }
    @PutMapping("/admin/retail/cases/{id}") public Models.Api<Void> resolve(@PathVariable String id,@RequestBody RetailWorkflow.Resolve input){workflow.resolve(id,access.require(),input);return Models.Api.ok(null);}
}
