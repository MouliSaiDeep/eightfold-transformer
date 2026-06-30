package com.eightfold.model;

import java.util.List;

public record Links(
    String linkedin,
    String github,
    String portfolio,
    List<String> other
) {}
