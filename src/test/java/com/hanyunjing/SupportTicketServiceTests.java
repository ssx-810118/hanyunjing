package com.hanyunjing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class SupportTicketServiceTests {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @Test void persistsAndIsolatesAccounts() {
        Path path=directory.resolve("support.json"); var service=new SupportTicketService(json,path);
        var ticket=service.create("a","once","商品咨询","p1",null,"想了解这件衣服如何搭配。");
        assertEquals("RECORDED",ticket.status()); assertEquals(0,service.list("b").size());
        assertEquals(ticket,new SupportTicketService(json,path).list("a").get(0));
    }
    @Test void repeatedSubmissionIsIdempotentButChangedContentIsRejected() {
        var service=new SupportTicketService(json,(Path)null);
        var first=service.create("a","once","尺码选择","p1",null,"不确定哪一个尺码适合我。");
        assertEquals(first,service.create("a","once","尺码选择","p1",null,"不确定哪一个尺码适合我。"));
        assertEquals(1,service.list("a").size());
        assertThrows(AuthService.Failure.class,()->service.create("a","once","尺码选择","p1",null,"这个问题已经变成其他内容。"));
    }
    @Test void validationAndCorruptedStoreDoNotFakeSuccess() throws Exception {
        var service=new SupportTicketService(json,(Path)null);
        assertThrows(IllegalArgumentException.class,()->service.create("a","once","unknown",null,null,"一段有效长度的问题描述"));
        assertThrows(IllegalArgumentException.class,()->service.create("a","once","其他问题",null,null,"短"));
        Path broken=directory.resolve("broken.json"); Files.writeString(broken,"broken");
        assertThrows(IllegalStateException.class,()->new SupportTicketService(json,broken));
        assertEquals("broken",Files.readString(broken));
    }
}
