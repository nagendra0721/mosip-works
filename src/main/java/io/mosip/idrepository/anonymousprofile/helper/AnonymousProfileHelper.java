package io.mosip.idrepository.anonymousprofile.helper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.mosip.idrepository.anonymousprofile.client.KeyManagerDecryptClient;
import io.mosip.idrepository.anonymousprofile.repository.UinHistoryRepository;
import io.mosip.idrepository.anonymousprofile.repository.UinHistoryRepository.UinHistoryRecord;

/**
 * Rebuilds anonymous-profile rows from ordered UIN history data.
 *
 * <p>Decryption is intentionally not implemented in this initial project. Configure the
 * Key Manager endpoint below and add the approved client implementation before a real run.</p>
 */
@Component
public class AnonymousProfileHelper {

	private static final String INSERT_PROFILE = "INSERT INTO idrepo.anonymous_profile "
			+ "(id, profile, cr_by, cr_dtimes, is_deleted) VALUES (?, ?, ?, ?, FALSE)";

	private final UinHistoryRepository uinHistoryRepository;
	private final JdbcTemplate targetJdbcTemplate;
	private final ObjectMapper mapper;
	private final KeyManagerDecryptClient keyManagerDecryptClient;
	private final boolean dryRun;
	private final String createdBy;

	public AnonymousProfileHelper(UinHistoryRepository uinHistoryRepository,
			@Qualifier("targetDataSource") DataSource targetDataSource, ObjectMapper mapper,
			KeyManagerDecryptClient keyManagerDecryptClient,
			@Value("${rebuild.dry-run:true}") boolean dryRun,
			@Value("${rebuild.created-by:anonymous-profile-rebuild-job}") String createdBy) {
		this.uinHistoryRepository = uinHistoryRepository;
		this.targetJdbcTemplate = new JdbcTemplate(targetDataSource);
		this.mapper = mapper;
		this.keyManagerDecryptClient = keyManagerDecryptClient;
		this.dryRun = dryRun;
		this.createdBy = createdBy;
	}

	public void rebuild() throws Exception {
		List<UinHistoryRecord> records = uinHistoryRepository.findAllOrdered();
		System.out.println("Rebuilding " + records.size() + " records");
		String activeUinRefId = null;
		JsonNode previousIdentity = null;
		int processed = 0;
		int stored = 0;
		int rejected = 0;

		for (UinHistoryRecord record : records) {
			if (!record.getUinRefId().equals(activeUinRefId)) {
				clear(previousIdentity);
				activeUinRefId = record.getUinRefId();
				previousIdentity = null;
			}

			JsonNode currentIdentity = null;
			try {
				currentIdentity = decryptAndDecode(record.getEncryptedUinData());
				String profile = buildProfile(previousIdentity, currentIdentity, record.getEffectiveDateTime());
				processed++;
				if (!dryRun) {
					targetJdbcTemplate.update(INSERT_PROFILE, UUID.randomUUID().toString(), profile, createdBy,
							LocalDateTime.now());
					stored++;
				}
				clear(previousIdentity);
				previousIdentity = currentIdentity;
			} catch (Exception exception) {
				rejected++;
				System.err.println("Skipped one UIN-history record: " + exception.getMessage());
			} finally {
				clear(currentIdentity);
				clear(record.getEncryptedUinData());
			}
		}
		clear(previousIdentity);
		System.out.printf("Rebuild complete — processed: %d, stored: %d, rejected: %d, dry-run: %s%n",
				processed, stored, rejected, dryRun);
	}

	private JsonNode decryptAndDecode(byte[] encryptedUinData) throws Exception {
		return keyManagerDecryptClient.decrypt(encryptedUinData);
	}

	private String buildProfile(JsonNode previousIdentity, JsonNode currentIdentity, LocalDateTime effectiveDateTime)
			throws Exception {
		ObjectNode profile = mapper.createObjectNode();
		profile.put("processName", previousIdentity == null ? "New" : "Update");
		profile.put("date", effectiveDateTime.toLocalDate().toString());
		profile.set("oldProfile", previousIdentity == null ? mapper.getNodeFactory().nullNode() : previousIdentity);
		profile.set("newProfile", currentIdentity);
		return mapper.writeValueAsString(profile);
	}

	private void clear(JsonNode ignored) {
		// Jackson JsonNode has no secure-wipe API. Keep each plaintext value scoped to one loop iteration.
	}

	private void clear(byte[] data) {
		if (data != null) {
			Arrays.fill(data, (byte) 0);
		}
	}
}
