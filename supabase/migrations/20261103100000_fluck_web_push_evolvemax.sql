-- ============================================================================
-- fluck_web_push: evolvemax.com is a portal origin too
-- ============================================================================
--
-- evolvemax.com (Optimist's Max portal) frames the same Max web chats as fluck.ai and now owns a
-- Web Push subscription for its own origin, saved through the owner's hosted Max as the signed-in
-- user (fluck_web_push_subscribe, unchanged). fluck-web's notify sends to it like any other portal.
-- Only the allowed-origin list changes; every other rule on the row stays as it was.
-- ============================================================================

ALTER TABLE public.fluck_web_push_subscriptions
    DROP CONSTRAINT IF EXISTS fluck_web_push_subscriptions_portal_origin_check;

ALTER TABLE public.fluck_web_push_subscriptions
    ADD CONSTRAINT fluck_web_push_subscriptions_portal_origin_check
    CHECK (portal_origin IN ('https://fluck.risaboss.com', 'https://fluck.ai', 'https://evolvemax.com'));
