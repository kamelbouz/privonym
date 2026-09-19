package com.privonym.application.service;

/** Parses one SQL dialect into Privonym's technology-neutral inventory model. */
public interface SqlDialectParser {
    String technology();
    SqlInventoryPreview parse(String sql);
}
