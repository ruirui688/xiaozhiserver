package xiaozhi.modules.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xiaozhi.modules.platform.dto.PlatformRequests.Settle;

/** Only permit references and usage quantities are persisted, never supplier credentials. */
@Component @Slf4j
public class PlatformSettlementOutbox {
    private final ObjectMapper json;
    private final PlatformAdmissionService admission;
    private final Path root;
    private String retryCursor="";
    public PlatformSettlementOutbox(ObjectMapper json,PlatformAdmissionService admission,@Value("${xiaozhi.platform.outbox-root:uploadfile/platform-settlements}") String directory) {
        this.json=json;this.admission=admission;this.root=Path.of(directory).toAbsolutePath().normalize();
    }
    public synchronized void store(Settle event) {
        try {
            Files.createDirectories(root);if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IllegalStateException();
            Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
            Path target=path(event.reservationId());if(Files.isSymbolicLink(target))throw new IllegalStateException();
            try(var files=Files.list(root)){if(files.limit(100001).count()>100000)throw new IllegalStateException();}
            Path temp=Files.createTempFile(root,"pending-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(json.writeValueAsBytes(event));while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
                Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally {Files.deleteIfExists(temp);}
        }catch(Exception failure){throw new IllegalStateException("PLATFORM_OUTBOX_UNAVAILABLE");}
    }
    public synchronized void remove(String id) {try{Files.deleteIfExists(path(id));}catch(Exception failure){throw new IllegalStateException("PLATFORM_OUTBOX_UNAVAILABLE");}}
    public synchronized void settle(Settle event) {
        try {store(event);admission.settle(event);remove(event.reservationId());}
        catch(RuntimeException failure){log.error("Platform settlement queued or held for review, errorType={}",failure.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelayString="${xiaozhi.platform.outbox-retry-ms:30000}")
    public synchronized void retry() {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return;
        try(var files=Files.list(root)) {
            var pending=files.filter(file->file.getFileName().toString().endsWith(".json")).sorted(java.util.Comparator.comparing(path->path.getFileName().toString())).toList();
            var ordered=new java.util.ArrayList<Path>();pending.stream().filter(path->path.getFileName().toString().compareTo(retryCursor)>0).forEach(ordered::add);pending.stream().filter(path->path.getFileName().toString().compareTo(retryCursor)<=0).forEach(ordered::add);
            for(Path path:ordered.stream().limit(100).toList()) {
                retryCursor=path.getFileName().toString();
                try {if(Files.isSymbolicLink(path)||Files.size(path)>65536)continue;Settle event=json.readValue(path.toFile(),Settle.class);admission.settle(event);remove(event.reservationId());}
                catch(Exception unavailable){log.warn("Platform settlement remains pending");}
            }
        }catch(Exception unavailable){log.warn("Platform settlement retry directory unavailable");}
    }
    private Path path(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{8,64}"))throw new IllegalArgumentException();return root.resolve(id+".json");}
}
