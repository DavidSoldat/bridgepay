import { orderTotals } from './pricing';

describe('orderTotals', () => {
  it('charges flat shipping below the free-shipping threshold and taxes only the subtotal', () => {
    expect(orderTotals(58)).toEqual({ subtotal: 58, shipping: 6.95, tax: 4.79, total: 69.74, installment: 17.44 });
  });

  it('ships free above the threshold', () => {
    expect(orderTotals(198)).toEqual({ subtotal: 198, shipping: 0, tax: 16.34, total: 214.34, installment: 53.59 });
  });

  it('still charges shipping at exactly the threshold', () => {
    expect(orderTotals(100).shipping).toBe(6.95);
  });

  it('rounds tax to the cent', () => {
    // 42 * 0.0825 = 3.465 -> 3.47
    expect(orderTotals(42).tax).toBe(3.47);
  });

  it('splits the total into 4 installments rounded half-up to the cent, like application-service', () => {
    // 214.34 / 4 = 53.585 -> 53.59 (BigDecimal HALF_UP)
    expect(orderTotals(198).installment).toBe(53.59);
  });
});
