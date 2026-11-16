package com.dadacore.lore.storage;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * When the folder of the files cannot be used, Lore stops with a message that names the variable
 * to check, as it does for the database.
 */
public final class FilesPathFailureAnalyzer extends AbstractFailureAnalyzer<FilesPathException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, FilesPathException cause) {
        String reason = cause.getCause() == null ? "" : " (" + cause.getCause().getMessage() + ")";
        return new FailureAnalysis(
                "Lore cannot write the imported files to " + cause.path() + reason + ".",
                "Check LORE_FILES_PATH: the folder must exist, or its parent must allow creating it, and the "
                        + "user running Lore must be able to write to it. With the packages, a folder outside "
                        + "/var/lib/lore must also be added to ReadWritePaths in a systemd override.",
                cause);
    }
}
