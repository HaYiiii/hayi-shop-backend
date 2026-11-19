package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * A flexible label for Products (Catalog §5.3, Decision AL2). ERD 05 gives it no version, so edits are last write
 * wins; no archive in MVP.
 */
@Entity
@Table(name = "cat_tag")
public class Tag {

	public static final int MAX_PER_PRODUCT = 20;

	@Id
	private UUID id;

	private String slug;

	private String name;

	@Column(updatable = false)
	private Instant createdAt;

	protected Tag() {
	}

	public static Tag create(String slug, String name, Instant now) {
		Tag tag = new Tag();
		tag.id = UuidV7.generate();
		tag.slug = slug;
		tag.name = name;
		tag.createdAt = now;
		return tag;
	}

	/** Returns what changed. */
	public FieldDiff edit(String newName, String newSlug) {
		FieldDiff diff = new FieldDiff();
		this.name = diff.track("name", this.name, newName);
		this.slug = diff.track("slug", this.slug, newSlug);
		return diff;
	}

	public UUID getId() {
		return this.id;
	}

	public String getSlug() {
		return this.slug;
	}

	public String getName() {
		return this.name;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
