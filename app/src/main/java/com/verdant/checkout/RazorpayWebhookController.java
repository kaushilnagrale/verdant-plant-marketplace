package com.verdant.checkout;

import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import com.verdant.payment.RazorpayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Backup confirmation path: Razorpay's own servers call this directly, so a
 * buyer closing the tab right after paying doesn't leave us never knowing
 * payment succeeded (see PaymentVerificationService.markPaidByRazorpayOrderId).
 *
 * Reads the raw request bytes ourselves via HttpServletRequest rather than
 * binding @RequestBody String - Spring's StringHttpMessageConverter doesn't
 * claim application/json by default, and even where binding a raw string
 * does work, going through a message converter risks a charset decode/
 * re-encode round trip that could subtly change the bytes the signature
 * was actually computed over. Reading the stream directly avoids both.
 */
@RestController
@RequestMapping("/v1/webhooks/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final PaymentVerificationService verification;
    private final RazorpayProperties razorpayProps;

    public RazorpayWebhookController(PaymentVerificationService verification, RazorpayProperties razorpayProps) {
        this.verification = verification;
        this.razorpayProps = razorpayProps;
    }

    @PostMapping
    public ResponseEntity<Void> handle(HttpServletRequest httpRequest,
                                        @RequestHeader("X-Razorpay-Signature") String signature) throws IOException {
        String rawBody = new String(httpRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean signatureValid;
        try {
            signatureValid = Utils.verifyWebhookSignature(rawBody, signature, razorpayProps.webhookSecret());
        } catch (RazorpayException e) {
            signatureValid = false;
        }
        if (!signatureValid) {
            log.warn("Rejected a Razorpay webhook call: invalid signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JSONObject payload = new JSONObject(rawBody);
        String event = payload.optString("event", "");

        if ("payment.captured".equals(event)) {
            JSONObject payment = payload.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
            verification.markPaidByRazorpayOrderId(payment.getString("order_id"), payment.getString("id"));
        }
        // Other event types (payment.failed, refund.*, etc.) are accepted
        // (200, so Razorpay doesn't retry) but not acted on yet.

        return ResponseEntity.ok().build();
    }
}
