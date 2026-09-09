package io.mosip.idrepository.anonymousprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.mosip.idrepository.anonymousprofile.client.KeyManagerDecryptClient;

@EnabledIfSystemProperty(named = "runKeyManagerIntegrationTest", matches = "true")
class AnonymousProfileRebuildIntegrationTest {

	private static final String UIN_REF_ID = "11111111-1111-1111-1111-111111111111";
	private static final String UIN = "1000000001";
	private static final String SOURCE_URL =
			"jdbc:h2:mem:anonymous_profile_source;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
	private static final String TARGET_URL =
			"jdbc:h2:mem:anonymous_profile_target;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

	private final ObjectMapper mapper = new ObjectMapper();
	private JdbcTemplate sourceJdbc;
	private JdbcTemplate targetJdbc;

	@BeforeEach
	void setUp() {
		sourceJdbc = new JdbcTemplate(dataSource(SOURCE_URL));
		targetJdbc = new JdbcTemplate(dataSource(TARGET_URL));
		createSourceSchema();
		createTargetSchema();

		System.setProperty("rebuild.source.datasource.jdbc-url", SOURCE_URL);
		System.setProperty("rebuild.source.datasource.username", "sa");
		System.setProperty("rebuild.source.datasource.password", "");
		System.setProperty("rebuild.source.datasource.driver-class-name", "org.h2.Driver");
		System.setProperty("rebuild.target.datasource.jdbc-url", TARGET_URL);
		System.setProperty("rebuild.target.datasource.username", "sa");
		System.setProperty("rebuild.target.datasource.password", "");
		System.setProperty("rebuild.target.datasource.driver-class-name", "org.h2.Driver");
		System.setProperty("rebuild.dry-run", "false");
	}

	@AfterEach
	void clearH2Overrides() {
		for (String property : new String[] {
				"rebuild.source.datasource.jdbc-url", "rebuild.source.datasource.username",
				"rebuild.source.datasource.password", "rebuild.source.datasource.driver-class-name",
				"rebuild.target.datasource.jdbc-url", "rebuild.target.datasource.username",
				"rebuild.target.datasource.password", "rebuild.target.datasource.driver-class-name",
				"rebuild.dry-run" }) {
			System.clearProperty(property);
		}
	}

	@Test
	void insertsOneIdentityUpdatesItFourTimesAndRunsTheActualApplication() throws Exception {
		KeyManagerDecryptClient keyManager = keyManagerClient();
		LocalDateTime effectiveTime = LocalDateTime.of(2026, 1, 1, 10, 0);

		for (int version = 1; version <= 5; version++) {
			byte[] storedCiphertext = doubleEncrypt(keyManager, identity(version));
			if (version == 1) {
				sourceJdbc.update("INSERT INTO idrepo.uin "
								+ "(uin_ref_id, uin, uin_data, reg_id, is_deleted) VALUES (?, ?, ?, ?, FALSE)",
						UIN_REF_ID, UIN, storedCiphertext, "TEST-REG-1");
				System.out.println("addIdentity: inserted idrepo.uin for UIN " + UIN);
			} else {
				sourceJdbc.update("UPDATE idrepo.uin SET uin_data = ?, reg_id = ? WHERE uin_ref_id = ?",
						storedCiphertext, "TEST-REG-" + version, UIN_REF_ID);
				System.out.printf("updateIdentity #%d: updated idrepo.uin for UIN %s%n", version - 1, UIN);
			}

			sourceJdbc.update("INSERT INTO idrepo.uin_h "
							+ "(uin_ref_id, eff_dtimes, reg_id, uin_data, is_deleted) VALUES (?, ?, ?, ?, FALSE)",
					UIN_REF_ID, effectiveTime.plusDays(version - 1), "TEST-REG-" + version, storedCiphertext);
		}

		printSourceTables();
		System.out.println("Starting AnonymousProfileRebuildApplication.main(...)");
		AnonymousProfileRebuildApplication.main(new String[0]);
		printAnonymousProfilesAndAssert();
	}

	private byte[] identity(int version) throws Exception {
		ObjectNode identity = mapper.createObjectNode();
		identity.put("version", version);
		identity.put("fullName", "Integration Test Identity " + version);
		return mapper.writeValueAsBytes(identity);
	}

	private byte[] doubleEncrypt(KeyManagerDecryptClient keyManager, byte[] identity) throws Exception {
		return keyManager.encrypt(keyManager.encrypt(identity));
	}

	private void printSourceTables() {
		String currentUinData = sourceJdbc.queryForObject(
				"SELECT uin_data FROM idrepo.uin WHERE uin_ref_id = ?",
				(resultSet, rowNumber) -> new String(resultSet.getBytes(1)), UIN_REF_ID);
		System.out.printf("idrepo.uin: uin=%s, uin_data=%s%n", UIN, currentUinData);

		sourceJdbc.query("SELECT eff_dtimes, reg_id, uin_data FROM idrepo.uin_h ORDER BY eff_dtimes",
				resultSet -> System.out.printf("idrepo.uin_h: effective=%s, regId=%s, uin_data=%s%n",
						resultSet.getTimestamp("eff_dtimes"), resultSet.getString("reg_id"),
						new String(resultSet.getBytes("uin_data"))));
	}

	private void printAnonymousProfilesAndAssert() throws Exception {
		List<JsonNode> profiles = new ArrayList<>();
		for (String profile : targetJdbc.queryForList(
				"SELECT profile FROM idrepo.anonymous_profile", String.class)) {
			profiles.add(mapper.readTree(profile));
		}
		profiles.sort(Comparator.comparingInt(
				profile -> profile.path("newProfile").path("version").asInt()));

		assertEquals(5, profiles.size());
		for (int index = 0; index < profiles.size(); index++) {
			JsonNode profile = profiles.get(index);
			int version = index + 1;
			assertEquals(version == 1 ? "New" : "Update", profile.path("processName").asText());
			assertEquals(version, profile.path("newProfile").path("version").asInt());
			if (version > 1) {
				assertEquals(version - 1, profile.path("oldProfile").path("version").asInt());
			}
			System.out.printf("anonymous_profile #%d: %s%n", version, profile);
		}
	}

	private JdbcDataSource dataSource(String url) {
		JdbcDataSource dataSource = new JdbcDataSource();
		dataSource.setURL(url);
		dataSource.setUser("sa");
		return dataSource;
	}

	private void createSourceSchema() {
		sourceJdbc.execute("CREATE SCHEMA IF NOT EXISTS idrepo");
		sourceJdbc.execute("CREATE TABLE idrepo.uin ("
				+ "uin_ref_id VARCHAR(36) PRIMARY KEY, uin VARCHAR(20), "
				+ "uin_data BINARY VARYING, reg_id VARCHAR(39), is_deleted BOOLEAN)");
		sourceJdbc.execute("CREATE TABLE idrepo.uin_h ("
				+ "uin_ref_id VARCHAR(36), eff_dtimes TIMESTAMP, reg_id VARCHAR(39), "
				+ "uin_data BINARY VARYING, is_deleted BOOLEAN)");
	}

	private void createTargetSchema() {
		targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS idrepo");
		targetJdbc.execute("CREATE TABLE idrepo.anonymous_profile ("
				+ "id VARCHAR(36), profile CLOB, cr_by VARCHAR(256), "
				+ "cr_dtimes TIMESTAMP, is_deleted BOOLEAN)");
	}

	private KeyManagerDecryptClient keyManagerClient() {
		return new KeyManagerDecryptClient(mapper,
				URI.create(requiredProperty("mosip.kernel.keymanager.decrypt-url")),
				URI.create(requiredProperty("mosip.kernel.keymanager.encrypt-url")),
				URI.create(requiredProperty("mosip.authmanager.token-url")),
				requiredProperty("mosip.authmanager.client-id"),
				requiredProperty("mosip.authmanager.client-secret"),
				requiredProperty("mosip.authmanager.app-id"),
				"ID_REPO", "identity_data");
	}

	private String requiredProperty(String name) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Set -D" + name + " before running this integration test");
		}
		return value;
	}
}
