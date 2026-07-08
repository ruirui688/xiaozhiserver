package xiaozhi.modules.security.service;

import xiaozhi.common.page.TokenDTO;
import xiaozhi.common.utils.Result;

public interface WechatMiniProgramService {

    Result<TokenDTO> loginByCode(String code);

    Result<Void> bindByCode(Long userId, String code);
}
