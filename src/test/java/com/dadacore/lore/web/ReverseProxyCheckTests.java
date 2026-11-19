package com.dadacore.lore.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** LORE_TRUSTED_PROXIES: a list without its "/" stops the start, a proxy left out is written in the logs. */
@ExtendWith(OutputCaptureExtension.class)
class ReverseProxyCheckTests {

    @Test
    void aListWithoutAnySlashOrAHostNameStopsTheStart() {
        for (String value : new String[] {"10.0.0.5", "10.0.0.5, 10.0.0.6", "10.0.0.5,10.0.0.6", "fd00::5", "nginx",
                "traefik, nginx", "proxy-1.dada.group", "reverse_proxy"}) {
            assertThatThrownBy(() -> ReverseProxyCheck.check(value)).as(value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LORE_TRUSTED_PROXIES");
        }
        for (String value : new String[] {null, "", "127.0.0.0/8, ::1/128", "10.0.0.5/32,10.0.0.6", "172.16.0.0/12",
                "10\\.0\\.0\\.\\d+", "10\\.0\\.0\\.5|10\\.0\\.0\\.6"}) {
            assertThatCode(() -> ReverseProxyCheck.check(value)).as(String.valueOf(value)).doesNotThrowAnyException();
        }
    }

    @Test
    void aProxyLeftOutIsWrittenInTheLogsOncePerAddress(CapturedOutput output) throws Exception {
        ReverseProxyCheck check = new ReverseProxyCheck(new MockEnvironment());
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
            request.setRemoteAddr("172.18.0.1");
            request.addHeader("X-Forwarded-For", "203.0.113.7");
            check.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/api/v1/session");
        direct.setRemoteAddr("172.18.0.9");
        check.doFilter(direct, new MockHttpServletResponse(), new MockFilterChain());
        // Through a declared proxy, from a machine of the network that sent its own header.
        MockHttpServletRequest forwarded = new MockHttpServletRequest("GET", "/api/v1/session");
        forwarded.setRemoteAddr("192.168.1.30");
        forwarded.addHeader("X-Forwarded-For", "203.0.113.7");
        forwarded.setAttribute(ReverseProxyCheck.FORWARDED, Boolean.TRUE);
        check.doFilter(forwarded, new MockHttpServletResponse(), new MockFilterChain());

        String logs = output.getOut();
        assertThat(logs.split("Requests from 172.18.0.1 carry", -1)).hasSize(2);
        assertThat(logs).doesNotContain("172.18.0.9").doesNotContain("192.168.1.30");
    }

    @Test
    void onlyAPrivateAddressLooksLikeAProxy() {
        for (String address : new String[] {"127.0.0.1", "10.1.2.3", "172.18.0.1", "192.168.1.20", "::1", "fe80::1", "fd12::3"}) {
            assertThat(ReverseProxyCheck.isPrivate(address)).as(address).isTrue();
        }
        for (String address : new String[] {"203.0.113.7", "2001:db8::1", "invalid", null}) {
            assertThat(ReverseProxyCheck.isPrivate(address)).as(String.valueOf(address)).isFalse();
        }
    }
}
