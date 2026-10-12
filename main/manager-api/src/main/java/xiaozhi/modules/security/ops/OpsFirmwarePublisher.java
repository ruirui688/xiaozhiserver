package xiaozhi.modules.security.ops;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.OtaDao;
import xiaozhi.modules.device.entity.OtaEntity;

@Service @RequiredArgsConstructor
public class OpsFirmwarePublisher {
    private final OtaDao ota;
    private final JdbcTemplate jdbc;
    private final OpsApprovalService approvals;
    public Map<String,Object> prepare(OtaEntity input) {
        OpsAccess.require("ota.publish","PLATFORM","firmware");
        if(input==null||input.getType()==null||!input.getType().matches("[a-zA-Z0-9_.-]{1,50}")||input.getVersion()==null||!input.getVersion().matches("[a-zA-Z0-9_.-]{1,50}")||input.getFirmwareName()==null||input.getFirmwareName().isBlank()||input.getFirmwareName().length()>100||(input.getRemark()!=null&&input.getRemark().length()>500)||(input.getSort()!=null&&input.getSort()<0))throw new RenException("固件名称、类型或版本无效");
        try {
            Path root=Path.of("uploadfile").toRealPath(),path=Path.of(input.getFirmwarePath()).toRealPath();
            if(path.toString().length()>255||!path.startsWith(root)||!Files.isRegularFile(path)||Files.size(path)>100L*1024*1024)throw new RenException("固件必须是已上传的受限文件");
            String name=path.getFileName().toString();if(!name.endsWith(".bin")&&!name.endsWith(".apk"))throw new RenException("固件类型无效");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(var stream=Files.newInputStream(path)){byte[] buffer=new byte[8192];int size;while((size=stream.read(buffer))!=-1)hash.update(buffer,0,size);}
            Map<String,Object> payload=new LinkedHashMap<>();
            payload.put("firmwareName",input.getFirmwareName());payload.put("type",input.getType());payload.put("version",input.getVersion());
            payload.put("firmwarePath",path.toString());payload.put("size",Files.size(path));payload.put("sha256",HexFormat.of().formatHex(hash.digest()));
            payload.put("remark",Objects.toString(input.getRemark(),""));payload.put("sort",input.getSort()==null?0:input.getSort());
            return payload;
        }catch(RenException e){throw e;}catch(Exception e){throw new RenException("无法验证固件文件");}
    }
    public String publish(OtaEntity input) {
        Map<String,Object> payload=prepare(input);
        return approvals.executeCurrent("ota.publish",input.getType(),input.getVersion(),payload,()->{
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_ota WHERE type=? AND version=?",Integer.class,input.getType(),input.getVersion())!=0)throw new RenException("固件版本已存在，不允许覆盖");
            OtaEntity record=new OtaEntity();record.setId(UUID.randomUUID().toString().replace("-", ""));record.setFirmwareName(input.getFirmwareName());record.setType(input.getType());record.setVersion(input.getVersion());
            jdbc.update("INSERT INTO ops_ota_release(hardware_type,version,ota_id) VALUES(?,?,?)",record.getType(),record.getVersion(),record.getId());
            record.setFirmwarePath(payload.get("firmwarePath").toString());record.setSize(((Number)payload.get("size")).longValue());record.setRemark(input.getRemark());record.setSort(input.getSort());
            record.setCreateDate(new Date());record.setUpdateDate(new Date());ota.insert(record);
            jdbc.update("INSERT INTO ops_ota_channel(hardware_type,ota_id,revision) VALUES(?,?,1) ON DUPLICATE KEY UPDATE ota_id=VALUES(ota_id),revision=revision+1,updated_at=CURRENT_TIMESTAMP",record.getType(),record.getId());
            return record.getId();
        });
    }
}
