package vn.hayi.shop.catalog.application;

import vn.hayi.shop.shared.actor.ActorContext;

/** Who asked for a Catalog change, for Audit (Decision W8). */
public record CatalogClient(ActorContext actor, String ipAddress, String userAgent) {
}
