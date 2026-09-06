import { apiClient } from "./client";
import type { CartLine, CartPreviewResponse, CheckoutResult, DeliveryAddress } from "../types";

export async function previewCart(items: CartLine[], destinationPincode: string): Promise<CartPreviewResponse> {
  const res = await apiClient.post<CartPreviewResponse>("/v1/cart/preview", { items, destinationPincode });
  return res.data;
}

// Authenticated now (buyer accounts) - apiClient's own interceptor attaches
// the buyer's token automatically, same as every other /v1/buyers/** call.
export async function checkout(
  buyerEmail: string,
  lines: CartLine[],
  destinationPincode: string,
  address: DeliveryAddress,
): Promise<CheckoutResult> {
  const res = await apiClient.post<CheckoutResult>("/v1/checkout", {
    buyerEmail,
    lines,
    destinationPincode,
    recipientName: address.recipientName,
    recipientPhone: address.recipientPhone,
    addressLine1: address.addressLine1,
    addressLine2: address.addressLine2,
    city: address.city,
    state: address.state,
  });
  return res.data;
}

export async function verifyPayment(
  orderId: string,
  razorpayOrderId: string,
  razorpayPaymentId: string,
  razorpaySignature: string,
): Promise<void> {
  await apiClient.post(`/v1/checkout/${orderId}/verify`, { razorpayOrderId, razorpayPaymentId, razorpaySignature });
}
