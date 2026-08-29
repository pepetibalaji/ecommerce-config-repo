# ecommerce-config-repo

## Environments

`dev/` is for local containers. Load `dev/.env` when starting local services. Supply
its database credentials and any optional provider credentials through the runtime
environment; no secrets are stored in Git.

`stage` is for the hosted staging deployment. It deliberately has no cloud-provider
or secret values checked into Git. Configure the values below in the hosting platform.

| Services | Required stage environment variables |
| --- | --- |
| All services | `AUTH_ISSUER_URI`, `AUTH_JWK_SET_URI`, `KAFKA_BOOTSTRAP_SERVERS`, `OTEL_TRACES_ENDPOINT` (if tracing is enabled) |
| PostgreSQL services | Service-specific `AUTH_DB_*`, `INVENTORY_DB_*`, `ORDER_DB_*`, and `PAYMENT_DB_*` |
| Product service | `PRODUCT_MONGODB_URI` and `PRODUCT_MONGODB_DATABASE` |
| Redis services | `REDIS_HOST`, `REDIS_PASSWORD` (and `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_SSL_ENABLED` if needed) |
| Gateway | `AUTH_SERVICE_URI`, `PRODUCT_SERVICE_URI`, `INVENTORY_SERVICE_URI`, `CART_SERVICE_URI`, `ORDER_SERVICE_URI`, `PAYMENT_SERVICE_URI` |
| Order service | `INVENTORY_GRPC_HOST`, `PRODUCT_SERVICE_URI` |
| Inventory service | `PRODUCT_SERVICE_URI` |
| Payment service | `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `PAYMENT_CHECKOUT_SUCCESS_URL`, `PAYMENT_CHECKOUT_CANCEL_URL` |

The dev database variable names are service-specific: `AUTH_DB_*`,
`PRODUCT_MONGODB_URI`, `PRODUCT_MONGODB_DATABASE`, `INVENTORY_DB_*`,
`ORDER_DB_*`, and `PAYMENT_DB_*`.

Cart Service defaults customer-cart expiry to seven days and guest-cart expiry
to 30 days. Override them per environment with `CART_CUSTOMER_TTL` and
`CART_GUEST_TTL` respectively (for example, `14d` or `12h`).

Load `stage/.env` for a staging deployment and configure
`SPRING_PROFILES_ACTIVE=stage`.

Production configuration is in `prod/application-prod.yml` and the service-specific
`prod/*-service-prod.yml` files. Load `prod/.env` only through a secure deployment
mechanism, then move its values into the cloud secret manager before production use.

## Repository layout

Each environment owns its configuration files and local environment file:

- `dev/`
- `stage/`
- `prod/`
Centralized environment configuration repository for Spring Cloud Config.
