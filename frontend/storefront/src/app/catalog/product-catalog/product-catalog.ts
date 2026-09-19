import { Component, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { PRODUCTS } from '../products';

@Component({
  selector: 'app-product-catalog',
  imports: [DecimalPipe],
  templateUrl: './product-catalog.html',
  styleUrl: './product-catalog.css',
})
export class ProductCatalog {
  protected readonly products = PRODUCTS;
  payInFour = output<string>();
}
