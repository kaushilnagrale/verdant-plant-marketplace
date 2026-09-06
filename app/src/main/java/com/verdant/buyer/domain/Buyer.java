package com.verdant.buyer.domain;

import java.util.UUID;

public record Buyer(UUID id, String subjectId, String email, String name, String phone) { }
