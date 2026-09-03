package com.verdant.logistics;

import com.verdant.logistics.domain.SizeTierDimensions.Dimensions;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Real Shiprocket integration, active everywhere except the "test" profile
 * (see StubCourierGateway) - same split as RazorpayGateway/StubPaymentGateway.
 *
 * Shiprocket has no official Java SDK (unlike Razorpay) - plain REST calls
 * via Spring's RestClient against their v1 external API. Two real calls
 * per booking: create an adhoc order, then a second call to actually
 * assign a courier and generate the AWB (Shiprocket's own API is two-step:
 * an order can exist without a courier/AWB assigned yet). Both go through
 * a shared bearer token, authenticated lazily and re-fetched once on a 401
 * (Shiprocket tokens are long-lived but do expire).
 *
 * NOT YET LIVE-VERIFIED as of writing - built against Shiprocket's
 * documented v1 API shape, pending real test-mode credentials (same
 * situation Razorpay was in before Day 23's real signup: built correctly
 * first, verified for real once credentials existed). Revisit and correct
 * against the real API the moment credentials are available - do not trust
 * this as verified until then.
 *
 * Billing fields Shiprocket's order-create API requires but this project
 * has no real capture for (full name, phone, street address - checkout
 * only ever asked for an email and a destination pincode) are filled with
 * clearly-labeled placeholders, not fabricated as if real. A real platform
 * would collect these at checkout before this integration could go live.
 */
@Component
@Profile("!test")
public class ShiprocketGateway implements CourierGateway {

    private static final String BASE_URL = "https://apiv2.shiprocket.in/v1/external";

    private final ShiprocketProperties props;
    private final RestClient http;
    private final ReentrantLock authLock = new ReentrantLock();
    private volatile String token;

    public ShiprocketGateway(ShiprocketProperties props) {
        this.props = props;
        this.http = RestClient.create(BASE_URL);
    }

    @Override
    public Booking createShipment(String idempotencyKey, String originPincode, String destinationPincode,
                                   int billableGrams, Dimensions dimensions, List<String> labels) {
        String shipmentId = createOrder(idempotencyKey, destinationPincode, billableGrams, dimensions);
        return assignAwb(shipmentId);
    }

    private String createOrder(String idempotencyKey, String destinationPincode, int billableGrams, Dimensions dimensions) {
        JSONObject item = new JSONObject()
            .put("name", "Live plant order")
            .put("sku", idempotencyKey)
            .put("units", 1)
            .put("selling_price", 1); // Shiprocket needs a nonzero declared value; actual order value isn't wired through here

        JSONObject body = new JSONObject()
            .put("order_id", idempotencyKey) // our idempotency key doubles as Shiprocket's own - a retried call resolves to the same order, not a duplicate
            .put("order_date", LocalDate.now().toString())
            .put("pickup_location", "Primary")
            .put("billing_customer_name", "Verdant Buyer") // no full billing capture exists yet - see class doc comment
            .put("billing_last_name", "")
            .put("billing_address", "Address on file")
            .put("billing_city", "")
            .put("billing_pincode", destinationPincode)
            .put("billing_state", "")
            .put("billing_country", "India")
            .put("billing_email", "orders@verdant.example")
            .put("billing_phone", "9999999999")
            .put("shipping_is_billing", true)
            .put("order_items", new JSONArray().put(item))
            .put("payment_method", "Prepaid") // Razorpay always captures before this gateway is ever called
            .put("sub_total", 1)
            .put("length", dimensions.lengthCm())
            .put("breadth", dimensions.widthCm())
            .put("height", dimensions.heightCm())
            .put("weight", billableGrams / 1000.0); // Shiprocket wants kilograms

        String raw = postAuthorized("/orders/create/adhoc", body);
        return String.valueOf(new JSONObject(raw).get("shipment_id"));
    }

    private Booking assignAwb(String shipmentId) {
        JSONObject body = new JSONObject().put("shipment_id", shipmentId);
        String raw = postAuthorized("/courier/assign/awb", body);

        JSONObject data = new JSONObject(raw).getJSONObject("response").getJSONObject("data");
        return new Booking(data.getString("courier_name"), data.getString("awb_code"), data.optString("label_url", null));
    }

    private String postAuthorized(String path, JSONObject body) {
        ensureAuthenticated();
        try {
            return post(path, body);
        } catch (HttpClientErrorException.Unauthorized e) {
            reauthenticate();
            return post(path, body);
        }
    }

    private String post(String path, JSONObject body) {
        return http.post().uri(path)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .body(body.toString())
            .retrieve()
            .body(String.class);
    }

    private void ensureAuthenticated() {
        if (token == null) {
            reauthenticate();
        }
    }

    private void reauthenticate() {
        authLock.lock();
        try {
            JSONObject body = new JSONObject().put("email", props.email()).put("password", props.password());
            String response = http.post().uri("/auth/login")
                .header("Content-Type", "application/json")
                .body(body.toString())
                .retrieve()
                .body(String.class);
            this.token = new JSONObject(response).getString("token");
        } finally {
            authLock.unlock();
        }
    }
}
