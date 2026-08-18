package xiaozhi.modules.reception.service;

import xiaozhi.modules.reception.dto.ReceptionContextRequestDTO;
import xiaozhi.modules.reception.vo.ReceptionContextVO;

public interface ReceptionContextService {
    ReceptionContextVO resolve(ReceptionContextRequestDTO request);
}
