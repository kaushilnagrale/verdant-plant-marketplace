import { apiClient } from "./client";
import type { CartLine, CartPreviewResponse, CheckoutResult } from "../types";

export async function previewCart(items: CartLine[], destinationPincode: string): Promise<CartPreviewResponse> {
  const res = await apiClient.post<CartPreviewResponse>("/v1/cart/preview", { items, destinationPincode });
  return res.data;
}

export async function checkout(buyerEmail: string, lines: CartLine[]): Promise<CheckoutResult> {
  const res = await apiClient.post<CheckoutResult>("/v1/checkout", { buyerEmail, lines });
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
