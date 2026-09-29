import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ProductDetail } from './product-detail';

describe('ProductDetail', () => {
  function render(id: string) {
    TestBed.configureTestingModule({ imports: [ProductDetail], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(ProductDetail);
    fixture.componentRef.setInput('id', id);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the product with its photo, copy and features', () => {
    const el = render('basin-rain-jacket');
    expect(el.querySelector('h1')?.textContent).toContain('Basin Rain Jacket');
    const img = el.querySelector('img')!;
    expect(img.getAttribute('src')).toBe('/products/basin-rain-jacket.webp');
    expect(img.getAttribute('alt')!.length).toBeGreaterThan(10);
    expect(el.textContent).toContain('198.00');
    expect(el.textContent).toContain('Pit zips for venting on climbs');
  });

  it('prices Pay in 4 on the financed total, spelled out', () => {
    const el = render('basin-rain-jacket');
    expect(el.querySelector('[data-testid="installment"]')?.textContent).toContain('4 × $53.59');
    expect(el.querySelector('[data-testid="total-line"]')?.textContent?.replace(/\s+/g, ' ').trim())
      .toBe('$214.34 total incl. $16.34 tax · free shipping');
    expect(el.querySelector('app-bridgepay-mark')).not.toBeNull();
  });

  it('names the shipping charge under the free-shipping threshold', () => {
    const el = render('insulated-field-bottle');
    expect(el.querySelector('[data-testid="installment"]')?.textContent).toContain('4 × $13.11');
    expect(el.querySelector('[data-testid="total-line"]')?.textContent?.replace(/\s+/g, ' ').trim())
      .toBe('$52.42 total incl. $3.47 tax · $6.95 shipping');
  });

  it('links the checkout button to this product', () => {
    const el = render('camp-multitool');
    const link = el.querySelector('[data-testid="checkout"]')!;
    expect(link.getAttribute('href')).toBe('/checkout/camp-multitool');
    expect(link.textContent?.trim()).toBe('Check out with Pay in 4');
  });

  it('shows Product not found with a way back for an unknown id', () => {
    const el = render('nope');
    expect(el.textContent).toContain('Product not found');
    expect(el.querySelector('a[href="/"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="checkout"]')).toBeNull();
  });
});
