package com.dadacore.lore.shared;

import java.util.List;

/**
 * One page of a list. {@code page} starts at 0; {@code total} only counts what the reader can see.
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {
}
