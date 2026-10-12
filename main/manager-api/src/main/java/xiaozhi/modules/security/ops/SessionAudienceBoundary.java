package xiaozhi.modules.security.ops;

import java.util.Arrays;
import java.util.LinkedHashSet;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.user.SecurityUser;

@Configuration
public class SessionAudienceBoundary implements WebMvcConfigurer, HandlerInterceptor {
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        if(!(handler instanceof HandlerMethod method)||"OPTIONS".equals(request.getMethod())) return true;
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(ServiceIdentity.isVerifiedRequest()) return true;
        var user=SecurityUser.getUser();
        if(user==null||user.getAudience()==null) return true; // Shiro handles authentication/anonymous endpoints.
        boolean ops="OPS".equals(user.getAudience());
        if(path.startsWith("/ops/")) { if(!ops) deny(); return true; }
        var actions=new LinkedHashSet<String>();
        RequiresPermissions classPermissions=AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(),RequiresPermissions.class);
        RequiresPermissions methodPermissions=AnnotatedElementUtils.findMergedAnnotation(method.getMethod(),RequiresPermissions.class);
        if(classPermissions!=null) actions.addAll(Arrays.asList(classPermissions.value()));
        if(methodPermissions!=null) actions.addAll(Arrays.asList(methodPermissions.value()));
        boolean operations=actions.stream().anyMatch(OpsPermissionCatalog.actions()::contains);
        if(operations) {
            if(!ops) deny();
            for(String action:actions) {
                if(!OpsPermissionCatalog.actions().contains(action)) deny();
                boolean allowed=OpsPermissionCatalog.TEMPLATES.values().stream().anyMatch(t->t.permissions().contains(action)
                        && OpsAccess.has(action,"PLATFORM",t.domain()));
                if(!allowed) deny();
            }
            return true;
        }
        boolean shared=AnnotatedElementUtils.hasAnnotation(method.getMethod(),SharedOfficialEndpoint.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(),SharedOfficialEndpoint.class);
        if(ops&&!shared) deny();
        return true;
    }
    private static void deny() { throw new RenException(ErrorCode.NO_PERMISSION); }
}
