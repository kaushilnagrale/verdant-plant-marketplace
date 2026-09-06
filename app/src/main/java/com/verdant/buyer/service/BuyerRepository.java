package com.verdant.buyer.service;

import com.verdant.buyer.domain.Buyer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Same real pattern as SellerUserRepository (JWT subject -> local identity row), plain JdbcTemplate rather than JPA, matching this project's own more recent convention for every domain added since Week 9. */
@Repository
public class BuyerRepository {

    private final JdbcTemplate jdbc;

    public BuyerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Buyer map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Buyer((UUID) rs.getObject("id"), rs.getString("subject_id"),
            rs.getString("email"), rs.getString("name"), rs.getString("phone"));
    }

    public Optional<Buyer> findBySubjectId(String subjectId) {
        return jdbc.query("SELECT * FROM buyers WHERE subject_id = ?", BuyerRepository::map, subjectId)
            .stream().findFirst();
    }

    public Optional<Buyer> findByEmail(String email) {
        return jdbc.query("SELECT * FROM buyers WHERE email = ?", BuyerRepository::map, email)
            .stream().findFirst();
    }

    public UUID insert(String subjectId, String email, String name, String phone) {
        return jdbc.queryForObject("""
            INSERT INTO buyers (subject_id, email, name, phone) VALUES (?, ?, ?, ?) RETURNING id
            """, UUID.class, subjectId, email, name, phone);
    }

    /** name/phone only - email is the Keycloak username (changing it is a real, separate cross-system operation, not done here), and there's no password field on this side to update at all (Keycloak owns credentials entirely). */
    public void updateProfile(UUID id, String name, String phone) {
        jdbc.update("UPDATE buyers SET name = ?, phone = ? WHERE id = ?", name, phone, id);
    }
}
