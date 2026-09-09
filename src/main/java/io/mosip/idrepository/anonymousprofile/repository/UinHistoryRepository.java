package io.mosip.idrepository.anonymousprofile.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UinHistoryRepository {

	private static final String HISTORY_QUERY = "SELECT uin_ref_id, eff_dtimes, reg_id, uin_data "
			+ "FROM idrepo.uin_h "
			+ "WHERE COALESCE(is_deleted, FALSE) = FALSE "
			+ "ORDER BY uin_ref_id ASC, eff_dtimes ASC";

	private final JdbcTemplate sourceJdbcTemplate;

	public UinHistoryRepository(@Qualifier("sourceDataSource") DataSource sourceDataSource) {
		this.sourceJdbcTemplate = new JdbcTemplate(sourceDataSource);
	}

	public List<UinHistoryRecord> findAllOrdered() {
		return sourceJdbcTemplate.query(HISTORY_QUERY, new UinHistoryRowMapper());
	}

	public static final class UinHistoryRecord {
		private final String uinRefId;
		private final LocalDateTime effectiveDateTime;
		private final String registrationId;
		private final byte[] encryptedUinData;

		public UinHistoryRecord(String uinRefId, LocalDateTime effectiveDateTime, String registrationId,
				byte[] encryptedUinData) {
			this.uinRefId = uinRefId;
			this.effectiveDateTime = effectiveDateTime;
			this.registrationId = registrationId;
			this.encryptedUinData = encryptedUinData;
		}

		public String getUinRefId() { return uinRefId; }
		public LocalDateTime getEffectiveDateTime() { return effectiveDateTime; }
		public String getRegistrationId() { return registrationId; }
		public byte[] getEncryptedUinData() { return encryptedUinData; }
	}

	private static final class UinHistoryRowMapper implements RowMapper<UinHistoryRecord> {
		@Override
		public UinHistoryRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
			return new UinHistoryRecord(resultSet.getString("uin_ref_id"),
					resultSet.getTimestamp("eff_dtimes").toLocalDateTime(), resultSet.getString("reg_id"),
					resultSet.getBytes("uin_data"));
		}
	}
}
