package com.verdant.assistant.service;

import com.verdant.assistant.domain.SymptomDetection;

public interface VisionService {
    SymptomDetection detectSymptoms(String imageKey);
}
