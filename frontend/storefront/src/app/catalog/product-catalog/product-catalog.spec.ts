import { TestBed } from '@angular/core/testing';
import { ProductCatalog } from './product-catalog';

describe('ProductCatalog', () => {
  it('renders every product with its name and price', () => {
    const fixture = TestBed.createComponent(ProductCatalog);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Basin Rain Jacket');
    expect(text).toContain('198.00');
    expect(text).toContain('Ridgeline Trail Pack');
  });

  it('emits the clicked product\'s id on Pay in 4', () => {
    const fixture = TestBed.createComponent(ProductCatalog);
    fixture.detectChanges();
    const emitted: string[] = [];
    fixture.componentInstance.payInFour.subscribe((id) => emitted.push(id));

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    (buttons[0] as HTMLButtonElement).click();

    expect(emitted).toEqual(['basin-rain-jacket']);
  });

  it('shows the Pay in 4 installment for each product, branded BridgePay', () => {
    const fixture = TestBed.createComponent(ProductCatalog);
    fixture.detectChanges();
    const firstCard = (fixture.nativeElement as HTMLElement).querySelector('article')!;

    expect(firstCard.textContent).toContain('or 4 × $49.50');
    expect(firstCard.querySelector('app-bridgepay-mark')).not.toBeNull();
  });
});
