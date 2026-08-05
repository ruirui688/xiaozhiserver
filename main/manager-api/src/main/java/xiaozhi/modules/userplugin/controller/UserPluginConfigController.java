package xiaozhi.modules.userplugin.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.userplugin.dto.UserPluginConfigSaveDTO;
import xiaozhi.modules.userplugin.service.UserPluginConfigService;
import xiaozhi.modules.userplugin.vo.UserPluginConfigVO;
import xiaozhi.modules.userplugin.vo.UserPluginTestVO;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user/plugin-configs")
public class UserPluginConfigController {
    private final UserPluginConfigService configService;

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<UserPluginConfigVO>> listConfigs() {
        return new Result<List<UserPluginConfigVO>>().ok(configService.listConfigs(SecurityUser.getUserId()));
    }

    @GetMapping("/{providerCode}")
    @RequiresPermissions("sys:role:normal")
    public Result<UserPluginConfigVO> getConfig(@PathVariable String providerCode) {
        return new Result<UserPluginConfigVO>().ok(
                configService.getConfig(SecurityUser.getUserId(), providerCode));
    }

    @PutMapping("/{providerCode}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> saveConfig(
            @PathVariable String providerCode,
            @Valid @RequestBody UserPluginConfigSaveDTO dto) {
        configService.saveConfig(SecurityUser.getUserId(), providerCode, dto);
        return new Result<Void>().ok(null);
    }

    @PostMapping("/{providerCode}/test")
    @RequiresPermissions("sys:role:normal")
    public Result<UserPluginTestVO> testConfig(
            @PathVariable String providerCode,
            @Valid @RequestBody UserPluginConfigSaveDTO dto) {
        return new Result<UserPluginTestVO>().ok(
                configService.testConfig(SecurityUser.getUserId(), providerCode, dto));
    }

    @DeleteMapping("/{providerCode}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> deleteConfig(@PathVariable String providerCode) {
        configService.deleteConfig(SecurityUser.getUserId(), providerCode);
        return new Result<Void>().ok(null);
    }
}
