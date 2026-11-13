package com.dadacore.lore.identity.security;

import java.util.List;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives controllers the {@link Viewer} of the request as a method parameter.
 */
@Configuration(proxyBeanMethods = false)
class ViewerArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    static {
        // The parameter is filled by the server: it is not part of the API description.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Viewer.class);
    }

    private final Viewers viewers;

    ViewerArgumentResolver(Viewers viewers) {
        this.viewers = viewers;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Viewer.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return viewers.current(request).orElseThrow(ApiException::unauthenticated);
    }
}
