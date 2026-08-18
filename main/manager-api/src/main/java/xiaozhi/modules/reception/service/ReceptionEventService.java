package xiaozhi.modules.reception.service;

import xiaozhi.modules.reception.dto.ReceptionVisitEventDTO;

public interface ReceptionEventService {
    boolean recordVisit(ReceptionVisitEventDTO dto);
}
