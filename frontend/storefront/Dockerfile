# Build stage
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
# node:22's bundled npm (10.x) reads this lockfile (written by npm 11.6.1,
# pinned in package.json's "packageManager") as missing optional-dep entries
# and refuses `npm ci` — install the matching npm major first.
RUN npm install -g npm@11.6.1
RUN npm ci
COPY . .
RUN npx ng build

# Run stage
FROM nginx:alpine
COPY --from=build /app/dist/storefront/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
