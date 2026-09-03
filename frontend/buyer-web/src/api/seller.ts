import { apiClient } from "./client";
import type { SellerOrderSummary } from "../types";

export async function myOrders(): Promise<SellerOrderSummary[]> {
  const res = await apiClient.get<SellerOrderSummary[]>("/v1/seller/orders");
  return res.data;
}

export async function packOrder(orderId: string): Promise<void> {
  await apiClient.post(`/v1/seller/orders/${orderId}/pack`);
}

export async function dispatchOrder(orderId: string): Promise<void> {
  await apiClient.post(`/v1/seller/orders/${orderId}/dispatch`);
}
