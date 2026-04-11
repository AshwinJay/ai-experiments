{{ config(materialized='view') }}
SELECT id AS payment_id, order_id, payment_method, amount / 100.0 AS amount
FROM {{ source('stripe', 'payments') }}
