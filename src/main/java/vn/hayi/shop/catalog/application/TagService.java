package vn.hayi.shop.catalog.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.catalog.domain.CatalogErrorCode;
import vn.hayi.shop.catalog.domain.FieldDiff;
import vn.hayi.shop.catalog.domain.Tag;
import vn.hayi.shop.catalog.domain.TagRepository;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.patch.Change;

/** Creates, edits and lists Tags (Catalog §5.3, API 08 §1.10, Decision AL2); audited (W8). */
@Service
public class TagService {

	private static final String SLUG_UNIQUE_KEY = "uk_cat_tag__slug";

	private final TagRepository tags;

	private final AuditRecorder audit;

	private final Clock clock;

	TagService(TagRepository tags, AuditRecorder audit, Clock clock) {
		this.tags = tags;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public TagView create(String slug, String name, CatalogClient client) {
		if (this.tags.existsBySlug(slug)) {
			throw slugTaken(slug);
		}
		Tag tag = save(Tag.create(slug, name, this.clock.instant()));
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("slug", slug);
		values.put("name", name);
		record(client, "TAG_CREATE", tag, null, values);
		return TagView.of(tag);
	}

	/** Merge Patch without a version (ERD 05 has none): last write wins (AL2). */
	@Transactional
	public TagView update(UUID tagId, Change<String> name, Change<String> slug, CatalogClient client) {
		Tag tag = this.tags.findById(tagId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_TAG_NOT_FOUND, "No Tag " + tagId));
		String newSlug = slug.applyTo(tag.getSlug());
		if (!newSlug.equals(tag.getSlug()) && this.tags.existsBySlugAndIdNot(newSlug, tagId)) {
			throw slugTaken(newSlug);
		}
		FieldDiff diff = tag.edit(name.applyTo(tag.getName()), newSlug);
		if (!diff.isEmpty()) {
			save(tag);
			record(client, "TAG_UPDATE", tag, diff.oldValues(), diff.newValues());
		}
		return TagView.of(tag);
	}

	@Transactional(readOnly = true)
	public List<TagView> list() {
		return this.tags.findAllByOrderBySlugAsc().stream().map(TagView::of).toList();
	}

	private Tag save(Tag tag) {
		try {
			return this.tags.saveAndFlush(tag);
		}
		catch (DataIntegrityViolationException ex) {
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains(SLUG_UNIQUE_KEY)) {
				throw slugTaken(tag.getSlug());
			}
			throw ex;
		}
	}

	private void record(CatalogClient client, String action, Tag tag, Map<String, ?> oldValue,
			Map<String, ?> newValue) {
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, action, "TAG", tag.getId())
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(client.ipAddress(), client.userAgent()));
	}

	private static DomainException slugTaken(String slug) {
		return new DomainException(CatalogErrorCode.CATALOG_SLUG_TAKEN, "Slug taken: " + slug, slug);
	}

}
