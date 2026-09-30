import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { loadConfig } from './app/config';

loadConfig()
  .then((config) => bootstrapApplication(App, appConfig(config)))
  .catch((err) => console.error(err));
