package com.verdant.notifications.domain;

/** RECIPIENT is distinct from BUYER on purpose - a gift order's buyer pays and gets the receipt; the recipient gets delivery updates and never the price. See NotificationService's own gift-suppression guard. */
public enum RecipientType {
    BUYER, RECIPIENT, SELLER, OPS
}
