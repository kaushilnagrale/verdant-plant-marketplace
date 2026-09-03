import { apiClient } from "./client";
import type { CatalogListingDetail, CatalogListingSummary } from "../types";

export async function browseCatalog(): Promise<CatalogListingSummary[]> {
  const res = await apiClient.get<CatalogListingSummary[]>("/v1/catalog");
  return res.data;
}

export async function getListing(id: string): Promise<CatalogListingDetail> {
  const res = await apiClient.get<CatalogListingDetail>(`/v1/catalog/${id}`);
  return res.data;
}
