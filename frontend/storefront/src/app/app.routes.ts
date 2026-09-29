import { Routes } from '@angular/router';
import { ProductCatalog } from './catalog/product-catalog/product-catalog';
import { ProductDetail } from './catalog/product-detail/product-detail';
import { Checkout } from './checkout/checkout';
import { Account } from './account/account';

export const routes: Routes = [
  { path: '', component: ProductCatalog },
  { path: 'products/:id', component: ProductDetail },
  { path: 'checkout/:id', component: Checkout },
  { path: 'account', component: Account },
  { path: '**', redirectTo: '' },
];
