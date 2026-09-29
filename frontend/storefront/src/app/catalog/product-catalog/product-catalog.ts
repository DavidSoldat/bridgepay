import { Component } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PRODUCTS, Product } from '../products';
import { orderTotals } from '../pricing';
import { BridgepayMark } from '../../shared/ui/bridgepay-mark/bridgepay-mark';

@Component({
  selector: 'app-product-catalog',
  imports: [DecimalPipe, RouterLink, BridgepayMark],
  templateUrl: './product-catalog.html',
  styleUrl: './product-catalog.css',
})
export class ProductCatalog {
  protected readonly products = PRODUCTS;

  /** "or 4 × $X": the installment on the financed total, so the card shows what checkout will charge. */
  protected installment(product: Product): number {
    return orderTotals(product.price).installment;
  }
}
