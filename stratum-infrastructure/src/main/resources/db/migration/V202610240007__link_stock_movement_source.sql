ALTER TABLE erp_stock_movement
    ADD CONSTRAINT fk_stock_movement_source
    FOREIGN KEY (posting_id, order_line_id, order_id)
    REFERENCES erp_fulfillment_line (posting_id, order_line_id, order_id);
