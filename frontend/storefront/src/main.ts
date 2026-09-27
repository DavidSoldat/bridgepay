import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

fetch('/config.json')
  .then((res) => res.json() as Promise<{ keycloakUrl: string; paddleClientToken?: string }>)
  .then((config) => bootstrapApplication(App, appConfig(config.keycloakUrl, config.paddleClientToken ?? '')))
  .catch((err) => console.error(err));
