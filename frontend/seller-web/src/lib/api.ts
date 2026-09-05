import axios from 'axios';
import { getToken } from './auth';

// Same-origin in a real deployment (served behind the same gateway as
// the API); VITE_API_BASE_URL for local dev against a real bootRun
// instance on 8080, matching every other real local-verification step
// this project has used all along.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export const api = axios.create({ baseURL: BASE_URL });

api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export interface SellerOrderItem {
  listingId: string;
  productName: string;
  quantity: number;
  lineTotal: string;
}

export interface SellerOrderSummary {
  orderId: string;
  orderStatus: string;
  buyerEmail: string;
  items: SellerOrderItem[];
  shipmentStatus: string;
  createdAt: string;
}

export interface QualityComponent {
  numerator: number;
  denominator: number;
  description: string;
  value: number;
}

export interface SellerQualityView {
  score: number;
  tier: 'PREFERRED' | 'STANDARD' | 'PROBATION' | 'RESTRICTED';
  components: Record<string, QualityComponent>;
  orderCount: number;
  isProvisional: boolean;
}

export async function fetchMySellerId(): Promise<string> {
  const res = await api.get<{ sellerId: string }>('/v1/seller/orders/me');
  return res.data.sellerId;
}

export async function fetchMyOrders(): Promise<SellerOrderSummary[]> {
  const res = await api.get<SellerOrderSummary[]>('/v1/seller/orders');
  return res.data;
}

export async function markPacked(orderId: string): Promise<void> {
  await api.post(`/v1/seller/orders/${orderId}/pack`);
}

export async function markDispatched(orderId: string): Promise<void> {
  await api.post(`/v1/seller/orders/${orderId}/dispatch`);
}

export async function createListing(rawTitle: string, priceAmount: string): Promise<{ id: string }> {
  const res = await api.post('/v1/listings', { rawTitle, priceAmount });
  return res.data;
}

export interface MyListing {
  id: string;
  rawTitle: string;
  priceAmount: string;
  active: boolean;
}

export async function fetchMyListings(): Promise<MyListing[]> {
  const res = await api.get<MyListing[]>('/v1/listings');
  return res.data;
}

export async function adjustStock(listingId: string, delta: number, reason: string): Promise<void> {
  await api.post(`/v1/listings/${listingId}/stock-adjust`, { delta, reason });
}

export async function fetchQuality(sellerId: string): Promise<SellerQualityView> {
  const res = await api.get<SellerQualityView>(`/v1/sellers/${sellerId}/quality`);
  return res.data;
}

export async function setVacationMode(sellerId: string, onVacation: boolean): Promise<void> {
  await api.post(`/v1/sellers/${sellerId}/vacation-mode`, { onVacation });
}
