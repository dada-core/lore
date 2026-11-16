package com.dadacore.lore.storage;

import java.nio.file.Path;

/** The folder of the files cannot be made or written to: Lore does not start. */
public final class FilesPathException extends IllegalStateException {

    private final transient Path path;

    FilesPathException(Path path, Throwable cause) {
        super("The folder of the files " + path + " cannot be written to", cause);
        this.path = path;
    }

    public Path path() {
        return path;
    }
}
