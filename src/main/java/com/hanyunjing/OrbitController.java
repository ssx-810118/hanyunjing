package com.hanyunjing;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/tryon")
public class OrbitController {
    private final OrbitService service;
    public OrbitController(OrbitService service){this.service=service;}
    @GetMapping("/orbit/status") public Models.Api<OrbitService.Status> status(){return Models.Api.ok(service.status());}
    @PostMapping("/tasks/{id}/orbit") public Models.Api<OrbitService.Task> generate(@PathVariable String id,@RequestParam String sessionId,@RequestBody OrbitService.Generate in){return Models.Api.ok(service.generate(id,AuthService.scope(sessionId),in));}
    @GetMapping("/tasks/{id}/orbit") public Models.Api<OrbitService.Task> latest(@PathVariable String id,@RequestParam String sessionId){return Models.Api.ok(service.latest(id,AuthService.scope(sessionId)));}
    @GetMapping("/orbit/tasks/{id}") public Models.Api<OrbitService.Task> get(@PathVariable String id,@RequestParam String sessionId){return Models.Api.ok(service.get(id,AuthService.scope(sessionId)));}
    @PostMapping("/orbit/tasks/{id}/cancel") public Models.Api<Void> cancel(@PathVariable String id,@RequestParam String sessionId){service.cancel(id,AuthService.scope(sessionId));return Models.Api.ok(null);}
    @GetMapping("/orbit/tasks/{id}/model") public ResponseEntity<byte[]> model(@PathVariable String id,@RequestParam String sessionId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("model/gltf-binary")).header("X-Content-Type-Options","nosniff").body(service.model(id,AuthService.scope(sessionId)));}
}
