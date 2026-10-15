package xiaozhi.modules.sys.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.AllArgsConstructor;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.common.utils.SpringContextUtils;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.platform.service.PlatformAccountService;
import xiaozhi.modules.platform.service.PlatformTrialGrantService;
import xiaozhi.modules.security.password.PasswordUtils;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.dto.AdminPageUserDTO;
import xiaozhi.modules.sys.dto.PasswordDTO;
import xiaozhi.modules.sys.dto.SysUserDTO;
import xiaozhi.modules.sys.entity.SysUserEntity;

import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserService;
import xiaozhi.modules.sys.vo.AdminPageUserVO;
import xiaozhi.modules.workspace.service.WorkspaceService;

/**
 * 系统用户
 */
@AllArgsConstructor
@Service
public class SysUserServiceImpl extends BaseServiceImpl<SysUserDao, SysUserEntity> implements SysUserService {
    private final SysUserDao sysUserDao;

    private final DeviceService deviceService;

    private final SysParamsService sysParamsService;

    private final WorkspaceService workspaceService;

    @Override
    public SysUserDTO getByUsername(String username) {
        QueryWrapper<SysUserEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", username);
        List<SysUserEntity> users = sysUserDao.selectList(queryWrapper);
        if (users == null || users.isEmpty()) {
            return null;
        }
        SysUserEntity entity = users.get(0);
        return ConvertUtils.sourceToTarget(entity, SysUserDTO.class);
    }

    @Override
    public SysUserDTO getByWxOpenid(String wxOpenid) {
        if (StringUtils.isBlank(wxOpenid)) {
            return null;
        }
        QueryWrapper<SysUserEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("wx_openid", wxOpenid);
        List<SysUserEntity> users = sysUserDao.selectList(queryWrapper);
        if (users == null || users.isEmpty()) {
            return null;
        }
        return ConvertUtils.sourceToTarget(users.get(0), SysUserDTO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysUserDTO createWechatUser(String wxOpenid, String wxUnionid) {
        SysUserDTO existing = getByWxOpenid(wxOpenid);
        if (existing != null) {
            return existing;
        }

        SysUserEntity entity = new SysUserEntity();
        entity.setUsername("wx_" + DigestUtil.sha256Hex(wxOpenid).substring(0, 24));
        entity.setPassword(PasswordUtils.encode(UUID.randomUUID() + "Aa1!"));
        entity.setWxOpenid(wxOpenid);
        entity.setWxUnionid(wxUnionid);

        entity.setStatus(1);
        insert(entity);
        provisionPlatformAccount(entity.getId());
        return ConvertUtils.sourceToTarget(entity, SysUserDTO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindWechatUser(Long userId, String wxOpenid, String wxUnionid) {
        if (userId == null || StringUtils.isBlank(wxOpenid)) {
            throw new RenException(ErrorCode.TOKEN_INVALID);
        }

        SysUserDTO existing = getByWxOpenid(wxOpenid);
        if (existing != null && !userId.equals(existing.getId())) {
            throw new RenException("该微信账号已绑定其他用户");
        }

        SysUserEntity entity = new SysUserEntity();
        entity.setId(userId);
        entity.setWxOpenid(wxOpenid);
        entity.setWxUnionid(wxUnionid);
        updateById(entity);
    }

    @Override
    public SysUserDTO getByUserId(Long userId) {
        SysUserEntity sysUserEntity = sysUserDao.selectById(userId);

        return ConvertUtils.sourceToTarget(sysUserEntity, SysUserDTO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(SysUserDTO dto) {
        SysUserEntity entity = ConvertUtils.sourceToTarget(dto, SysUserEntity.class);

        // 密码强度
        if (!isStrongPassword(entity.getPassword())) {
            throw new RenException(ErrorCode.PASSWORD_WEAK_ERROR);
        }

        // 密码加密
        String password = PasswordUtils.encode(entity.getPassword());
        entity.setPassword(password);

        entity.setStatus(1);

        insert(entity);
        provisionPlatformAccount(entity.getId());
    }

    /**
     * Every registration path provisions the allowance account and default workspace,
     * independently of supplier credentials, followed by a durable one-time registration grant intent.
     * The intent commits with registration; an exhausted pool delays delivery without rolling the user back.
     */
    private void provisionPlatformAccount(Long userId) {
        if (userId == null) {
            return;
        }
        SpringContextUtils.getBean(PlatformAccountService.class).ensureUser(userId);
        workspaceService.ensureDefaultWorkspace(userId);
        SpringContextUtils.getBean(PlatformTrialGrantService.class).issue(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Long id) {
        workspaceService.prepareUserDeletion(id);
        // 删除用户
        baseDao.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, PasswordDTO passwordDTO) {
        SysUserEntity sysUserEntity = sysUserDao.selectById(userId);

        if (null == sysUserEntity) {
            throw new RenException(ErrorCode.TOKEN_INVALID);
        }

        // 判断旧密码是否正确
        if (!PasswordUtils.matches(passwordDTO.getPassword(), sysUserEntity.getPassword())) {
            throw new RenException(ErrorCode.OLD_PASSWORD_ERROR);
        }

        // 新密码强度
        if (!isStrongPassword(passwordDTO.getNewPassword())) {
            throw new RenException(ErrorCode.PASSWORD_WEAK_ERROR);
        }

        // 密码加密
        String password = PasswordUtils.encode(passwordDTO.getNewPassword());
        sysUserEntity.setPassword(password);

        updateById(sysUserEntity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePasswordDirectly(Long userId, String password) {
        // 新密码强度
        if (!isStrongPassword(password)) {
            throw new RenException(ErrorCode.PASSWORD_WEAK_ERROR);
        }
        SysUserEntity sysUserEntity = new SysUserEntity();
        sysUserEntity.setId(userId);
        sysUserEntity.setPassword(PasswordUtils.encode(password));
        updateById(sysUserEntity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(Long userId) {
        throw new RenException("密码仅可由用户本人通过已验证渠道恢复");
    }

    private Long getUserCount() {
        QueryWrapper<SysUserEntity> queryWrapper = new QueryWrapper<>();
        return baseDao.selectCount(queryWrapper);
    }

    @Override
    public PageData<AdminPageUserVO> page(AdminPageUserDTO dto) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put(Constant.PAGE, dto.getPage());
        params.put(Constant.LIMIT, dto.getLimit());
        IPage<SysUserEntity> page = baseDao.selectPage(
                getPage(params, "id", true),
                new QueryWrapper<SysUserEntity>().like(StringUtils.isNotBlank(dto.getMobile()), "username",
                        dto.getMobile()));
        // 循环处理page获取回来的数据，返回需要的字段
        List<AdminPageUserVO> list = page.getRecords().stream().map(user -> {
            AdminPageUserVO adminPageUserVO = new AdminPageUserVO();
            adminPageUserVO.setUserid(user.getId().toString());
            adminPageUserVO.setMobile(user.getUsername());
            String deviceCount = deviceService.selectCountByUserId(user.getId()).toString();
            adminPageUserVO.setDeviceCount(deviceCount);
            adminPageUserVO.setStatus(user.getStatus());
            adminPageUserVO.setCreateDate(user.getCreateDate());
            return adminPageUserVO;
        }).toList();
        return new PageData<>(list, page.getTotal());
    }

    private boolean isStrongPassword(String password) {
        // 弱密码的正则表达式
        String weakPasswordRegex = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z]).+$";
        Pattern pattern = Pattern.compile(weakPasswordRegex);
        Matcher matcher = pattern.matcher(password);
        return matcher.matches();
    }

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()";
    private static final Random random = new Random();

    /**
     * 生成随机密码
     * 
     * @return 随机生成的密码
     */
    private String generatePassword() {
        StringBuilder password = new StringBuilder();

        // 确保包含至少一个数字
        password.append("0123456789".charAt(random.nextInt(10)));
        // 确保包含至少一个小写字母
        password.append("abcdefghijklmnopqrstuvwxyz".charAt(random.nextInt(26)));
        // 确保包含至少一个大写字母
        password.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(random.nextInt(26)));
        // 确保包含至少一个特殊符号
        password.append("!@#$%^&*()".charAt(random.nextInt(10)));

        // 生成剩余的8个字符
        for (int i = 4; i < 12; i++) {
            password.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }

        // 打乱密码中字符的顺序
        char[] passwordChars = password.toString().toCharArray();
        for (int i = 0; i < passwordChars.length; i++) {
            int randomIndex = random.nextInt(passwordChars.length);
            char temp = passwordChars[i];
            passwordChars[i] = passwordChars[randomIndex];
            passwordChars[randomIndex] = temp;
        }

        return new String(passwordChars);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Integer status, String[] userIds) {
        if (userIds == null || userIds.length == 0) {
            return;
        }
        List<Long> normalizedUserIds = java.util.Arrays.stream(userIds)
                .map(Long::parseLong)
                .distinct()
                .sorted()
                .toList();
        if (Integer.valueOf(0).equals(status)) {
            workspaceService.prepareUsersDisabled(normalizedUserIds);
        }
        for (Long userId : normalizedUserIds) {
            SysUserEntity entity = new SysUserEntity();
            entity.setId(userId);
            entity.setStatus(status);
            updateById(entity);
        }
    }

    @Override
    public boolean getAllowUserRegister() {
        String allowUserRegister = sysParamsService.getValue(Constant.SERVER_ALLOW_USER_REGISTER, true);
        return "true".equals(allowUserRegister);
    }
}
