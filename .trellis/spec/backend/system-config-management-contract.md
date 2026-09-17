# System Configuration Management Contract

## Purpose

`/api/system/configs` is the reusable management boundary for system-wide configuration. New integrations add configuration metadata rows rather than new environment variables, singleton tables, or feature-specific settings pages.

## Data Model

`system_config_item` stores one item per `(group_code, config_key)`:

- group metadata: `group_code`, `group_name`, `group_description`
- item metadata: `config_key`, `config_name`, `config_description`, `value_type`, `sort_order`
- policy: `is_sensitive`, `is_required`, `status`
- value and audit: `config_value`, `updated_by`, timestamps

Supported value types are `STRING`, `PASSWORD`, `BOOLEAN`, `URL`, and `NUMBER`.

## API

- `GET /api/system/configs` -> configuration group summaries; permission `system:config:list`.
- `GET /api/system/configs/{groupCode}` -> metadata plus non-sensitive values. Sensitive items return `value=null` and `configured=true|false`.
- `PUT /api/system/configs/{groupCode}` body `{ "values": { "config.key": "value" } }`; permission `system:config:edit`. The actor comes from `@RequestAttribute("userId")`.
- `POST /api/system/configs/email-integration/deepseek/test` and `/wecom/test` validate the saved DB configuration.

## Sensitive Values

- Sensitive values use AES-256-GCM with the infrastructure root key.
- Blank sensitive input preserves existing ciphertext.
- API responses, logs, and validation messages never contain plaintext or ciphertext.
- The root encryption key remains outside the database; configuration business credentials remain in the database.

## Migration and Extension

- V29 migrates existing V28 email-integration values, including ciphertext without decrypting it.
- To add a future configuration group, seed `system_config_item` metadata and consume it through `SystemConfigService`; do not create a separate settings page unless the interaction cannot be represented by typed configuration items.

## Good / Base / Bad Cases

- Good: management changes a URL and replaces a secret; subsequent consumers use the new value.
- Base: a sensitive field is blank during update, preserving the existing secret.
- Bad: unknown keys, invalid booleans/numbers/URLs, or unauthorized access are rejected; sensitive values never appear in API output.
