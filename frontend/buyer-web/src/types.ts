// Mirrors the backend DTOs exactly (see the Java records under
// app/src/main/java/com/verdant/catalog, .../cart, .../orders/preview,
// .../checkout, .../seller) - kept in sync by hand since this is a single
// small frontend, not a generated client.

export interface CatalogListingSummary {
  id: string;
  title: string;
  productName: string | null;
  priceAmount: number;
  sellerId: string;
  sellerName: string;
}

export interface CatalogListingDetail extends CatalogListingSummary {
  description: string | null;
  handlingTimeHours: number;
}

export interface CartLine {
  listingId: string;
  quantity: number;
}

export interface Money {
  amount: number;
  currency: string;
}

export interface AllocatedLinePreview {
  listingId: string;
  productName: string;
  quantity: number;
}

export interface ShipmentPreview {
  sellerId: string;
  lines: AllocatedLinePreview[];
  transitDays: number;
  estimatedDelivery: string;
  shippingCost: Money;
}

export interface UnfulfillableLinePreview {
  listingId: string;
  productName: string;
  reason: string;
}

export interface CartPreviewResponse {
  shipments: ShipmentPreview[];
  unfulfillable: UnfulfillableLinePreview[];
  totalShippingCost: Money;
  requiresBuyerConfirmation: boolean;
}

export interface CheckoutResult {
  orderId: string;
  razorpayOrderId: string;
  razorpayKeyId: string;
  totalAmount: Money;
}

export interface SellerOrderItem {
  listingId: string;
  productName: string;
  quantity: number;
  lineTotal: number;
}

export interface SellerOrderSummary {
  orderId: string;
  orderStatus: string;
  buyerEmail: string;
  items: SellerOrderItem[];
  shipmentStatus: string | null;
  createdAt: string;
}
