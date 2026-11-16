package com.dadacore.lore.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiExceptionHandlerTests {

    @Test
    void aFileTooLargeReadOutsideTheImportStatesNoLimitItCannotKnow() {
        // The import answers itself with the limit of the organization; this safety net does not know it.
        ResponseEntity<Object> response = new ApiExceptionHandler().handleMaxUploadSizeExceededException(
                new MaxUploadSizeExceededException(1024), new HttpHeaders(), HttpStatus.PAYLOAD_TOO_LARGE, null);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problem -> assertThat(problem.getProperties())
                .containsEntry("code", "file_too_large")
                .doesNotContainKey("maxMegabytes"));
    }
}
