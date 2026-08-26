package xiaozhi.modules.person.service;

import java.util.List;
import java.util.Map;

import xiaozhi.modules.person.entity.PersonAgentBindingEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.person.vo.PersonAgentOptionVO;

public interface PersonFacePublicationService {
    List<PersonAgentOptionVO> listWorkspaceAgents(String workspaceId);

    void replaceDesiredBindings(PersonProfileEntity profile, List<String> requestedAgentIds, List<String> legacyDeviceIds);

    List<String> publish(PersonProfileEntity profile);

    List<String> prepareDelete(PersonProfileEntity profile);

    Map<String, List<PersonAgentBindingEntity>> bindingsByPerson(List<String> personIds, String workspaceId);

    Map<String, String> agentNames(String workspaceId);

    boolean hasUnpublishedChanges(PersonProfileEntity profile, List<PersonAgentBindingEntity> bindings);
}
