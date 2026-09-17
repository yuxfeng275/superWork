export interface QuotationPolicy {
  id: number
  name: string
  type: string
  taxMode: string
  version: number
  status: string
  effectiveDate?: string
  expiryDate?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
  items?: QuotationPolicyItem[]
}

export interface QuotationPolicyItem {
  id: number
  policyId: number
  section: string
  category?: string
  itemKey: string
  itemName: string
  description?: string
  priceDescription?: string
  isRequired: number
  unitPrice?: number
  taxRate?: number
  chargeMethod?: string
  chargeUnit?: string
  remark?: string
  sortOrder: number
}

export interface Quotation {
  id: number
  quotationNo: string
  policyId?: number
  opportunityId?: number
  opportunityName?: string
  taxMode: string
  customerName?: string
  contactPerson?: string
  contactPhone?: string
  contactEmail?: string
  deliveryPeriod?: string
  quoteDate: string
  validityDays: number
  currency: string
  invoiceType: string
  firstYearTotalExTax?: number
  firstYearTotalInclTax?: number
  subsequentYearTotalExTax?: number
  subsequentYearTotalInclTax?: number
  quotationNote?: string
  status: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
  lineItems?: QuotationLineItem[]
  brandScopes?: QuotationBrandScope[]
}

export interface QuotationLineItem {
  id: number
  quotationId: number
  policyItemId?: number
  section: string
  category?: string
  itemName: string
  description?: string
  priceDescription?: string
  isSelected: number
  quantity: number
  unitPriceExTax?: number
  taxRate?: number
  discountRate: number
  subtotalExTax?: number
  subtotalInclTax?: number
  chargeMethod?: string
  remark?: string
  sortOrder: number
}

export interface QuotationBrandScope {
  id: number
  quotationId: number
  brand?: string
  store?: string
  description?: string
  target?: string
  sortOrder: number
}

export interface QuotationListVO {
  id: number
  quotationNo: string
  customerName?: string
  firstYearTotalInclTax?: number
  status: string
  quoteDate: string
  opportunityName?: string
}

export interface QuotationGenerateRequest {
  policyId: number
  opportunityId?: number
  customerName: string
  contactPerson?: string
  contactPhone?: string
  contactEmail?: string
  deliveryPeriod?: string
  lineItemOverrides: LineItemOverride[]
}

export interface LineItemOverride {
  policyItemId: number
  isSelected: boolean
  quantity?: number
  discountRate?: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}