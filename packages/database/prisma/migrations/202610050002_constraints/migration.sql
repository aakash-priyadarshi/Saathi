ALTER TABLE "ReliefRequest" ADD CONSTRAINT "request_quantity_bounds" CHECK ("requestedQuantity" > 0 AND "receivedQuantity" >= 0 AND "committedQuantity" >= "receivedQuantity" AND "committedQuantity" <= "requestedQuantity");
ALTER TABLE "DonationCommitment" ADD CONSTRAINT "commitment_quantity_bounds" CHECK ("quantity" > 0 AND "receivedQuantity" >= 0 AND "receivedQuantity" <= "quantity");
ALTER TABLE "Delivery" ADD CONSTRAINT "delivery_quantity_positive" CHECK ("quantity" > 0);
ALTER TABLE "ReliefPoint" ADD CONSTRAINT "coordinate_bounds" CHECK (("latitude" IS NULL OR "latitude" BETWEEN -90 AND 90) AND ("longitude" IS NULL OR "longitude" BETWEEN -180 AND 180));
CREATE FUNCTION prevent_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Audit events are append-only';
END;
$$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON "AuditEvent" FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
CREATE FUNCTION preserve_request_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Relief requests must be archived, never deleted';
END;
$$;
CREATE TRIGGER request_no_delete BEFORE DELETE ON "ReliefRequest" FOR EACH ROW EXECUTE FUNCTION preserve_request_history();
