package com.verdant.assistant.model;

import java.util.List;
import java.util.UUID;

/** What the model is allowed to produce: which of the ALREADY-decided available listings it picked, which retrieved chunks it's citing for any cultural/occasion claim, and the explanation text. GiftConciergeService.validate() checks both id lists against what was actually offered before trusting this at all. */
public record GiftSelection(List<UUID> listingIds, List<UUID> citations, String explanation) {
}
