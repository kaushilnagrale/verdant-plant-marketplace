import { apiClient } from "./client";
import type { BuyerOrderDetail, BuyerOrderSummary } from "../types";

export async function myOrders(): Promise<BuyerOrderSummary[]> {
  const res = await apiClient.get<BuyerOrderSummary[]>("/v1/buyers/me/orders");
  return res.data;
}

export async function orderDetail(orderId: string): Promise<BuyerOrderDetail> {
  const res = await apiClient.get<BuyerOrderDetail>(`/v1/buyers/me/orders/${orderId}`);
  return res.data;
}
