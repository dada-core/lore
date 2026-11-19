package com.dadacore.lore.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The text of a viewer, as libraries log the arguments of a call at TRACE: never the session identifier. */
class ViewerTests {

    @Test
    void itsTextHidesTheSessionIdentifier() {
        String session = "Y2Q3ZjVhNjQtNmQ5Yi00YjkzLWE2ZjMtM2M1ZDk4ZTQxYjFh";
        Viewer viewer = new Viewer(UUID.randomUUID(), UUID.randomUUID(), OrganizationRole.MEMBER, "Eli Brou",
                "eli.brou@dada.group", "fr", session, "203.0.113.7");

        assertThat(viewer.toString()).doesNotContain(session).contains("203.0.113.7");
    }
}
