package com.dadacore.lore.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dadacore.lore.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Through a declared reverse proxy, the address of the person is kept and nothing is written in the logs. */
@ExtendWith(OutputCaptureExtension.class)
class ReverseProxyApiTests extends ApiTest {

    @Test
    void aDeclaredProxyIsNotTakenForOneLeftOut(CapturedOutput output) {
        // The test server trusts its own machine, as Lore does by default: these requests come through it.
        browser().from("192.168.1.20").get("/api/v1/session");
        browser().from("10.0.0.7").get("/api/v1/session");
        // A machine of the network that sends its own header: the proxy adds the address it sees.
        browser().from("203.0.113.7, 192.168.1.30").get("/api/v1/session");

        assertThat(output.getOut()).doesNotContain("carry an X-Forwarded-For");
    }
}
