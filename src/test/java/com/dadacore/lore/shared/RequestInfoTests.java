package com.dadacore.lore.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The client address kept for the sign-in counters and the audit log. */
class RequestInfoTests {

    @Test
    void anIpAddressIsKept() {
        assertThat(RequestInfo.ipAddress("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RequestInfo.ipAddress("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(RequestInfo.ipAddress("2001:DB8::1")).isEqualTo("2001:db8::1");
        assertThat(RequestInfo.ipAddress("::ffff:192.0.2.1")).isEqualTo("::ffff:192.0.2.1");
        assertThat(RequestInfo.ipAddress("ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255")).hasSize(45);
        assertThat(RequestInfo.ipAddress("fe80::1%eth0")).isEqualTo("fe80::1");
        assertThat(RequestInfo.ipAddress("::")).isEqualTo("::");
        // Followed by its port, as some proxies write it: one address, whatever the port.
        assertThat(RequestInfo.ipAddress("203.0.113.7:5678")).isEqualTo("203.0.113.7");
        assertThat(RequestInfo.ipAddress("[2001:DB8::1]:5678")).isEqualTo("2001:db8::1");
        assertThat(RequestInfo.ipAddress("[2001:db8::1]")).isEqualTo("2001:db8::1");
        assertThat(RequestInfo.ipAddress("[fe80::1%eth0]:443")).isEqualTo("fe80::1");
    }

    @Test
    void anythingElseIsNeitherLookedUpNorKept() {
        for (String address : new String[] {null, "", "localhost", "lore.example.com", "dead.beef", "1234",
                "203.0.113.7 ", "203.0.113.7, 10.0.0.1", "<script>", "%eth0", "a".repeat(3000),
                "1:2:3:4:5:6:7:8:9:10:11:12:13:14:15:16:17:18:19",
                // Dots and colons alone, numbers out of range, a group too long, two gaps.
                "...", ":::", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.4:", "1.2.3.4:99999", "1.2.3.4:5:6",
                "12345::1", "1::2::3", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7",
                "[2001:db8::1", "[2001:db8::1]:", "[2001:db8::1]x", "[]:80",
                // An IPv4 part anywhere but at the end.
                "1.2.3.4::", "1.2.3.4::1", "::1.2.3.4:1",
                // Digits of other scripts, which Java also counts as digits.
                "\u0661.2.3.4"}) {
            assertThat(RequestInfo.ipAddress(address)).as(String.valueOf(address)).isEqualTo(RequestInfo.INVALID_IP);
        }
    }
}
