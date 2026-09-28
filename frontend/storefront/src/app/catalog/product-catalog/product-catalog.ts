import { Component, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { PRODUCTS, Product } from '../products';
import { BridgepayMark } from '../../shared/ui/bridgepay-mark/bridgepay-mark';

@Component({
  selector: 'app-product-catalog',
  imports: [DecimalPipe, BridgepayMark],
  templateUrl: './product-catalog.html',
  styleUrl: './product-catalog.css',
})
export class ProductCatalog {
  protected readonly products = PRODUCTS;
  payInFour = output<string>();

  /** "or 4 × $X" on the card: the product price split in four; checkout finances the full total. */
  protected installment(product: Product): number {
    return Math.round((product.price * 100) / 4) / 100;
  }
}
