package com.verdant.seller;

import com.verdant.identity.SellerUserRepository;
import com.verdant.quality.service.SellerQualityScoreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Day 56's own two remaining real gaps: "Payouts and quality" (the score
 * screen, real read against QualityScorer's own output - Week 10) and
 * vacation mode (real, missing infrastructure the runbook's own seller
 * app assumes exists - V36). {sellerId} in the path is checked against
 * the caller's own JWT-mapped seller, same manual-ownership style as
 * ListingController - a seller can never read or toggle another
 * seller's account by guessing an id.
 */
@RestController
@RequestMapping("/v1/sellers/{sellerId}")
public class SellerAccountController {

    private final SellerUserRepository sellerUsers;
    private final SellerQualityScoreRepository qualityScores;
    private final JdbcTemplate jdbc;

    public SellerAccountController(SellerUserRepository sellerUsers, SellerQualityScoreRepository qualityScores,
                                    JdbcTemplate jdbc) {
        this.sellerUsers = sellerUsers;
        this.qualityScores = qualityScores;
        this.jdbc = jdbc;
    }

    @GetMapping("/quality")
    public SellerQualityScoreRepository.SellerQualityView quality(@AuthenticationPrincipal Jwt jwt,
                                                                    @PathVariable UUID sellerId) {
        requireOwnSeller(jwt, sellerId);
        return qualityScores.findFullBySellerId(sellerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No quality score computed yet for this seller"));
    }

    @PostMapping("/vacation-mode")
    public void setVacationMode(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sellerId,
                                 @RequestBody VacationModeRequest request) {
        requireOwnSeller(jwt, sellerId);
        jdbc.update("UPDATE sellers SET on_vacation = ? WHERE id = ?", request.onVacation(), sellerId);
    }

    private void requireOwnSeller(Jwt jwt, UUID sellerId) {
        UUID callerSellerId = sellerUsers.findBySubjectId(jwt.getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No seller mapped to this account"))
            .getSellerId();
        if (!callerSellerId.equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your seller account");
        }
    }

    public record VacationModeRequest(boolean onVacation) { }
}
