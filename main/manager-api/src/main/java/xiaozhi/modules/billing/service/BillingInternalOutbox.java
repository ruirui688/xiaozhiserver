package xiaozhi.modules.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xiaozhi.modules.billing.service.BillingUsageRecorder.InternalUsage;

/** Local durable retry for Java calls when the ledger DB is temporarily unavailable. */
@Component @Slf4j
public class BillingInternalOutbox {
    private final ObjectMapper mapper;
    private final ObjectProvider<BillingUsageService> usage;
    private final Path root;
    private final java.util.concurrent.atomic.AtomicLong persistenceFailures=new java.util.concurrent.atomic.AtomicLong();
    public BillingInternalOutbox(ObjectMapper mapper,ObjectProvider<BillingUsageService> usage,
            @Value("${xiaozhi.billing.outbox-root:uploadfile/billing-outbox}") String directory) {
        this.mapper=mapper;this.usage=usage;this.root=Path.of(directory).toAbsolutePath().normalize();
    }
    public synchronized void store(InternalUsage event) {
        try {
            prepare();
            String name=event.getEventId();
            if(name==null || !name.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException();
            Path target=root.resolve(name+".json");
            byte[] payload=mapper.writeValueAsBytes(event);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                if(Files.isSymbolicLink(target) || !Arrays.equals(Files.readAllBytes(target),payload)) throw new IllegalStateException();
                return;
            }
            try(var files=Files.list(root)) { if(files.filter(p->p.getFileName().toString().endsWith(".json")).limit(100_000).count()>=100_000) throw new IllegalStateException(); }
            Path temp=Files.createTempFile(root,"pending-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(payload);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
                Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
            log.warn("模型用量已进入持久化重试队列，当前统计可能尚未完整");
        } catch(Exception e) { persistenceFailures.incrementAndGet();log.error("模型用量持久化失败，计量覆盖不完整；请检查数据库与计量目录");throw new IllegalStateException("计量持久化失败"); }
    }
    @Scheduled(fixedDelayString="${xiaozhi.billing.outbox-retry-ms:30000}")
    public synchronized void retry() {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) return;
        try(var files=Files.list(root)) {
            List<Path> pending=files.filter(p->p.getFileName().toString().endsWith(".json")).limit(100).toList();
            for(Path path:pending) {
                try {
                    if(Files.isSymbolicLink(path) || Files.size(path)>256*1024) throw new IllegalArgumentException();
                    InternalUsage event=mapper.readValue(path.toFile(),InternalUsage.class);
                    usage.getObject().persistInternal(event);
                    Files.delete(path);
                } catch(org.springframework.dao.DataIntegrityViolationException e) {
                    Files.move(path,path.resolveSibling(path.getFileName()+".rejected"));
                    log.error("一条计量记录不满足账本约束，已隔离并继续其他重试");
                } catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException e) {
                    log.warn("模型用量数据库仍不可用，保留重试队列");break;
                } catch(Exception e) {
                    Files.move(path,path.resolveSibling(path.getFileName()+".rejected"));
                    log.error("一条计量记录已隔离，需检查计量队列；其余记录继续重试");
                }
            }
        } catch(Exception e) { log.warn("模型用量重试尚未完成，保留持久化记录"); }
    }
    public Map<String,Object> status() {
        try {
            List<Path> entries;
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) entries=List.of();
            else try(var files=Files.list(root)){entries=files.toList();}
            long pending=entries.stream().filter(p->p.getFileName().toString().endsWith(".json")).count();
            long rejected=entries.stream().filter(p->p.getFileName().toString().endsWith(".rejected")).count();
            return Map.of("pendingCount",pending,"rejectedCount",rejected,"persistenceFailureCount",persistenceFailures.get(),"status",
                    persistenceFailures.get()>0 || rejected>0 ? "INCOMPLETE" : pending>0 ? "PENDING" : "READY");
        } catch(Exception e) { return Map.of("status","UNAVAILABLE"); }
    }
    private void prepare() throws Exception {
        Files.createDirectories(root);
        if(Files.isSymbolicLink(root) || !root.toRealPath().equals(root)) throw new IllegalStateException();
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
    }
}
