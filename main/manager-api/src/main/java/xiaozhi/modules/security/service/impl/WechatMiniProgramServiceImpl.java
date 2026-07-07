package xiaozhi.modules.security.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.TokenDTO;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.utils.HttpContextUtils;
import xiaozhi.common.utils.IpUtils;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.service.SysUserTokenService;
import xiaozhi.modules.security.service.WechatMiniProgramService;
import xiaozhi.modules.sys.dto.SysUserDTO;
import xiaozhi.modules.sys.service.SysUserService;

@Slf4j
@Service
@RequiredArgsConstructor
public class WechatMiniProgramServiceImpl implements WechatMiniProgramService {

    private static final String CODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";

    private final RestTemplate restTemplate;
    private final RedisUtils redisUtils;
    private final SysUserService sysUserService;
    private final SysUserTokenService sysUserTokenService;

    @Value("${wechat.mini-program.app-id:}")
    private String appId;

    @Value("${wechat.mini-program.app-secret:}")
    private String appSecret;

    @Override
    public Result<TokenDTO> loginByCode(String code) {
        checkRateLimit();
        if (StringUtils.isAnyBlank(appId, appSecret)) {
            throw new RenException("微信小程序登录未配置WECHAT_MINI_PROGRAM_APP_ID或WECHAT_MINI_PROGRAM_APP_SECRET");
        }

        JSONObject json = code2Session(code);
        String openid = getOpenid(json);

        SysUserDTO user = sysUserService.getByWxOpenid(openid);
        if (user == null) {
            user = sysUserService.createWechatUser(openid, json.getStr("unionid"));
        }
        return sysUserTokenService.createToken(user.getId());
    }

    @Override
    public Result<Void> bindByCode(Long userId, String code) {
        checkRateLimit();
        if (StringUtils.isAnyBlank(appId, appSecret)) {
            throw new RenException("微信小程序登录未配置WECHAT_MINI_PROGRAM_APP_ID或WECHAT_MINI_PROGRAM_APP_SECRET");
        }

        JSONObject json = code2Session(code);
        sysUserService.bindWechatUser(userId, getOpenid(json), json.getStr("unionid"));
        return new Result<>();
    }

    private JSONObject code2Session(String code) {
        String body;
        try {
            body = restTemplate.getForObject(CODE2SESSION_URL, String.class, appId, appSecret, code);
        } catch (Exception e) {
            throw new RenException("微信登录服务暂不可用", e);
        }

        JSONObject json = JSONUtil.parseObj(body);
        Integer errCode = json.getInt("errcode");
        if (errCode != null && errCode != 0) {
            log.warn("微信code2session失败: errcode={}, errmsg={}", errCode, json.getStr("errmsg"));
            throw new RenException("微信登录失败，请重新进入小程序");
        }
        return json;
    }

    private String getOpenid(JSONObject json) {
        String openid = json.getStr("openid");
        if (StringUtils.isBlank(openid)) {
            throw new RenException("微信登录失败，未获取到openid");
        }
        return openid;
    }

    private void checkRateLimit() {
        String ip = "unknown";
        if (HttpContextUtils.getHttpServletRequest() != null) {
            ip = IpUtils.getIpAddr(HttpContextUtils.getHttpServletRequest());
        }
        Long count = redisUtils.increment("security:wx-login:" + ip, 60);
        if (count != null && count > 20) {
            throw new RenException("微信登录请求过于频繁，请稍后重试");
        }
    }
}
