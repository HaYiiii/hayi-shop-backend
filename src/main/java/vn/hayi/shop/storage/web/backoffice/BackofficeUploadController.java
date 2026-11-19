package vn.hayi.shop.storage.web.backoffice;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.spec.backoffice.api.UploadsApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.UploadFinalizedDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.UploadIntentConstraintsDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.UploadIntentDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.UploadIntentRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.UploadIntentUploadDto;
import vn.hayi.shop.storage.application.UploadService;

/**
 * Backoffice image uploads (API 06 §1, Decision AF). The request schema limits purpose, type, size and checksum
 * format (AF1), so anything else is a {@code 400} before it reaches the service. Images are catalog master data, so
 * {@code CATALOG_MANAGE} at any scope is enough (M1).
 */
@RestController
class BackofficeUploadController implements UploadsApi {

	private final UploadService uploads;

	BackofficeUploadController(UploadService uploads) {
		this.uploads = uploads;
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<UploadIntentDto> createUpload(UploadIntentRequestDto body, String xCsrfToken) {
		UploadService.UploadIntent intent = this.uploads.createIntent(body.getPurpose().getValue(), body.getFileName(),
				body.getContentType().getValue(), body.getSizeBytes(), body.getChecksumSha256(),
				AuthenticatedSession.require().userId());
		return ResponseEntity.created(URI.create("/api/backoffice/v1/uploads/" + intent.storedObjectId()))
			.body(new UploadIntentDto(intent.storedObjectId(),
					new UploadIntentUploadDto(UploadIntentUploadDto.MethodEnum.PUT, intent.url(), intent.headers(),
							intent.expiresAt().atOffset(ZoneOffset.UTC)),
					new UploadIntentConstraintsDto(intent.maxSizeBytes(), intent.allowedMimeTypes())));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<UploadFinalizedDto> finalizeUpload(UUID storedObjectId, String xCsrfToken) {
		UploadService.FinalizedUpload done = this.uploads.finalizeUpload(storedObjectId,
				AuthenticatedSession.require().userId());
		return ResponseEntity.ok(new UploadFinalizedDto(done.storedObjectId(),
				UploadFinalizedDto.StatusEnum.fromValue(done.status()), done.detectedMimeType(), done.sizeBytes(),
				done.width(), done.height(), done.publicUrl()));
	}

}
