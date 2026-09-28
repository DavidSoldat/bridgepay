import { TestBed } from '@angular/core/testing';
import { SkeletonRows } from './skeleton-rows';

describe('SkeletonRows', () => {
  it('renders the requested number of placeholder bars inside a status region', () => {
    const fixture = TestBed.createComponent(SkeletonRows);
    fixture.componentRef.setInput('rows', 3);
    fixture.detectChanges();
    const root = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="skeleton"]')!;
    expect(root.getAttribute('role')).toBe('status');
    expect(root.querySelectorAll('[data-bar]').length).toBe(3);
    expect(root.textContent).toContain('Loading');
  });
});
