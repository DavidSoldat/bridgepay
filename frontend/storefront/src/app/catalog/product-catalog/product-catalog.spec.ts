import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ProductCatalog } from './product-catalog';

describe('ProductCatalog', () => {
  function render() {
    TestBed.configureTestingModule({ imports: [ProductCatalog], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(ProductCatalog);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders every product with its name, price and photo', () => {
    const el = render();
    expect(el.textContent).toContain('Basin Rain Jacket');
    expect(el.textContent).toContain('198.00');
    expect(el.textContent).toContain('Ridgeline Trail Pack');
    const img = el.querySelector('article img')!;
    expect(img.getAttribute('src')).toBe('/products/basin-rain-jacket.webp');
    expect(img.getAttribute('loading')).toBe('lazy');
  });

  it('links each card to its product page', () => {
    const hrefs = Array.from(render().querySelectorAll('article a')).map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual([
      '/products/basin-rain-jacket',
      '/products/ridgeline-trail-pack',
      '/products/camp-multitool',
      '/products/insulated-field-bottle',
    ]);
  });

  it('shows Pay in 4 on the financed total, branded BridgePay', () => {
    const cards = render().querySelectorAll('article');
    expect(cards[0].textContent).toContain('or 4 × $53.59');
    expect(cards[3].textContent).toContain('or 4 × $13.11');
    expect(cards[0].querySelector('app-bridgepay-mark')).not.toBeNull();
  });
});
