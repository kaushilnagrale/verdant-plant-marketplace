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

// Mirrors SearchHit/SearchResults/FacetCounts (com.verdant.search.query) -
// the real, location-aware endpoint (/v1/search) the plain catalog grid
// above doesn't use at all. Deliberately narrower than the catalog
// summary: search doesn't return a seller name (the index doesn't carry
// one), so results show product/price/origin only, not fabricated.
export interface SearchHit {
  listingId: string;
  sellerId: string;
  displayName: string;
  priceAmount: number;
  inStock: boolean;
  originZone: string;
  score: number;
}

export interface FacetCounts {
  lightRequirement: Record<string, number>;
  sizeTier: Record<string, number>;
  petSafe: Record<string, number>;
  priceRange: Record<string, number>;
  deliveryDays: Record<string, number>;
}

export interface SearchResults {
  hits: SearchHit[];
  facets: FacetCounts;
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

export interface DeliveryAddress {
  recipientName: string;
  recipientPhone: string;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  state: string;
}

export interface Buyer {
  id: string;
  subjectId: string;
  email: string;
  name: string;
  phone: string | null;
}

export interface BuyerOrderItem {
  listingId: string;
  productName: string;
  sellerBusinessName: string;
  quantity: number;
  lineTotal: number;
}

export interface BuyerOrderSummary {
  orderId: string;
  status: string;
  totalAmount: number;
  createdAt: string;
  items: BuyerOrderItem[];
}

export interface ShipmentTrackingEvent {
  status: string;
  location: string | null;
  remark: string | null;
  occurredAt: string;
}

export interface ShipmentTracking {
  shipmentId: string;
  sellerBusinessName: string;
  status: string;
  courier: string | null;
  awbNumber: string | null;
  transitDays: number | null;
  dispatchedAt: string | null;
  deliveredAt: string | null;
  events: ShipmentTrackingEvent[];
}

export interface BuyerOrderDetail {
  orderId: string;
  status: string;
  totalAmount: number;
  createdAt: string;
  recipientName: string;
  recipientPhone: string;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  state: string;
  pincode: string;
  items: BuyerOrderItem[];
  shipments: ShipmentTracking[];
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
