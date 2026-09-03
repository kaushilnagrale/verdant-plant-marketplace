package com.verdant.orders.preview;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same /v1/cart/** prefix as com.verdant.cart.CartController (already permitAll in SecurityConfig) - no separate security rule needed. */
@RestController
@RequestMapping("/v1/cart")
public class CartPreviewController {

    private final CartPreviewService previewService;

    public CartPreviewController(CartPreviewService previewService) {
        this.previewService = previewService;
    }

    @PostMapping("/preview")
    public CartPreviewResponse preview(@RequestBody CartPreviewRequest request) {
        return previewService.preview(request.items(), request.destinationPincode());
    }
}
