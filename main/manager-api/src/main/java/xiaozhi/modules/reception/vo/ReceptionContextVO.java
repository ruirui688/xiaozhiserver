package xiaozhi.modules.reception.vo;

import java.util.Date;
import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReceptionContextVO {
    private String deviceId;
    private String workspaceId;
    private Date generatedAt;
    private String companyName;
    private String companyIntroText;
    private String productsJson;
    private String faqJson;
    private String seatsJson;
    private String stageShowJson;
    private String serviceContactText;
    private long configVersion;
    private List<PersonContext> people;

    @Data
    @Builder
    public static class PersonContext {
        private String personId;
        private String personName;
        private String gender;
        private String ageGroup;
        private String customerStage;
        private String companyName;
        private String hobbiesJson;
        private String foodPreferencesJson;
        private String seatPreferencesJson;
        private String receptionNotes;
        private String sensitiveNotes;
        private int visitCount;
        private boolean appointment;
        private String appointmentId;
        private int appointmentPartySize;
        private String appointmentSeatRequest;
    }
}
