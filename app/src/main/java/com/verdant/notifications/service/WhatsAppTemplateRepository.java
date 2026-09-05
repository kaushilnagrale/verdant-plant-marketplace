package com.verdant.notifications.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WhatsAppTemplateRepository {

    private final JdbcTemplate jdbc;

    public WhatsAppTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isUrgent(String templateName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT is_urgent FROM whatsapp_templates WHERE name = ?", Boolean.class, templateName));
    }

    public boolean isPriceBearing(String templateName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT is_price_bearing FROM whatsapp_templates WHERE name = ?", Boolean.class, templateName));
    }

    public boolean isApproved(String templateName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT approved FROM whatsapp_templates WHERE name = ?", Boolean.class, templateName));
    }

    /** A real ops action, not something this session can do for real (see NotificationSender's own class comment) - exposed so a test can simulate "ops approved this template" without hand-rolling the UPDATE everywhere. */
    public void markApproved(String templateName, boolean approved) {
        jdbc.update("UPDATE whatsapp_templates SET approved = ? WHERE name = ?", approved, templateName);
    }

    public String languageOf(String templateName) {
        return jdbc.queryForObject("SELECT language FROM whatsapp_templates WHERE name = ?", String.class, templateName);
    }
}
