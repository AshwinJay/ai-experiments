{{ config(materialized='table', schema='analytics') }}
SELECT c.customer_id, c.first_name, c.last_name,
       COUNT(o.order_id) AS order_count, SUM(o.amount) AS lifetime_value
FROM {{ ref('stg_customers') }} c
LEFT JOIN {{ ref('orders') }} o ON c.customer_id = o.customer_id
GROUP BY 1, 2, 3
