export interface OrderTotals {
  subtotal: number;
  shipping: number;
  tax: number;
  total: number;
  installment: number; // 1 of 4, rounded half-up like application-service
}

const FREE_SHIPPING_OVER_CENTS = 10000;
const SHIPPING_CENTS = 695;
const TAX_RATE_BASIS_POINTS = 825; // 8.25% sales tax on the subtotal, not on shipping

// Computed in integer cents so 42 * 8.25% is exactly 3.465 -> 3.47, not a float 3.4649999.
export function orderTotals(price: number): OrderTotals {
  const subtotal = Math.round(price * 100);
  const shipping = subtotal > FREE_SHIPPING_OVER_CENTS ? 0 : SHIPPING_CENTS;
  const tax = Math.round((subtotal * TAX_RATE_BASIS_POINTS) / 10000);
  const total = subtotal + shipping + tax;
  return {
    subtotal: subtotal / 100,
    shipping: shipping / 100,
    tax: tax / 100,
    total: total / 100,
    installment: Math.round(total / 4) / 100,
  };
}

/** Display-only due dates: today, then one per calendar week (setDate, so DST never shifts the day). */
export function weeklySchedule(start: Date, count: number): Date[] {
  return Array.from(
    { length: count },
    (_, i) => new Date(start.getFullYear(), start.getMonth(), start.getDate() + 7 * i),
  );
}
