package com.dadacore.lore.access;

/**
 * Role in a space, each including the previous one. A reader consults; a contributor also
 * organizes and imports; a maintainer also manages the members, the settings, the archiving and
 * the deletion of the space.
 */
public enum SpaceRole {
    READER,
    CONTRIBUTOR,
    MAINTAINER;

    public boolean includes(SpaceRole other) {
        return compareTo(other) >= 0;
    }
}
