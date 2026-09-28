import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { EmptyState } from './empty-state';

@Component({
  imports: [EmptyState],
  template: `<app-empty-state icon="inbox" heading="Nothing here" text="Come back later"><button>Act</button></app-empty-state>`,
})
class Host {}

describe('EmptyState', () => {
  it('shows the heading, the text, an icon and the projected action', () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Nothing here');
    expect(el.textContent).toContain('Come back later');
    expect(el.querySelector('svg')).not.toBeNull();
    expect(el.querySelector('button')?.textContent).toBe('Act');
  });
});
