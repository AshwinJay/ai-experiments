{{ config(materialized='table', schema='analytics') }}
WITH orders AS (SELECT * FROM {{ ref('stg_orders') }}),
customers AS (SELECT * FROM {{ ref('stg_customers') }}),
payments AS (SELECT order_id, SUM(amount) AS total FROM {{ ref('stg_payments') }} GROUP BY 1)
SELECT o.order_id, o.customer_id, c.first_name, c.last_name,
       o.order_date, o.status, COALESCE(p.total, 0) AS amount
FROM orders o
LEFT JOIN customers c ON o.customer_id = c.customer_id
LEFT JOIN payments p ON o.order_id = p.order_id
