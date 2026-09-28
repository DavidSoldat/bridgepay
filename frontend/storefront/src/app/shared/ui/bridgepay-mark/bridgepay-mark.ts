import { Component, input } from '@angular/core';

/** The BridgePay glyph (assets/brand/bridgepay-mark.svg) with the wordmark as real text. */
@Component({
  selector: 'app-bridgepay-mark',
  templateUrl: './bridgepay-mark.html',
  styleUrl: './bridgepay-mark.css',
})
export class BridgepayMark {
  size = input<'sm' | 'md'>('md');
}
