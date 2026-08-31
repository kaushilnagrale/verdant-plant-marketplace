package com.verdant.identity.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@Profile({"local", "test"})
public class StubBankVerificationGateway implements BankVerificationGateway {
    @Override public String initiatePennyDrop(String accountNumber, String ifsc) {
        return "VRD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
